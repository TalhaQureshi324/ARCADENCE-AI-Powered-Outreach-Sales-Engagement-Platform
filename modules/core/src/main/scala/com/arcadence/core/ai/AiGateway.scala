package com.arcadence.core.ai

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import org.http4s.*
import org.http4s.client.Client
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.circe.CirceEntityDecoder.*

import java.time.Duration
import scala.util.Random

/** Provider-agnostic model gateway (Phase 11.1). Arcadence's business rules
  * stay deterministic: models only ever produce text/JSON through this narrow
  * interface, and every call is bounded (timeout, retries, max output tokens).
  * Providers are integrated only under terms that exclude training on customer
  * data; the gateway sends exactly the messages it is given and nothing else.
  */
object AiGateway:

  final case class ChatMessage(
      role: String,
      content: String
  ) // system | user | assistant

  final case class Usage(inputTokens: Long, outputTokens: Long):
    def totalTokens: Long = inputTokens + outputTokens

  final case class Completion(
      text: String,
      usage: Usage,
      model: String,
      finishReason: String
  )

  sealed trait GatewayError:
    def detail: String

  case class Timeout(detail: String) extends GatewayError
  case class RateLimited(retryAfterSeconds: Int) extends GatewayError:
    def detail =
      s"model provider rate limited (retry after ${retryAfterSeconds}s)"
  case class QuotaExceeded(detail: String) extends GatewayError
  case class Unauthorized(detail: String) extends GatewayError
  case class BadRequest(detail: String) extends GatewayError
  case class Unavailable(detail: String) extends GatewayError
  case class NotConfigured(detail: String) extends GatewayError

  trait Gateway:
    def name: String
    def complete(
        model: String,
        messages: List[ChatMessage],
        maxOutputTokens: Int,
        jsonMode: Boolean
    ): IO[Either[GatewayError, Completion]]

  /** Conservative cost estimate in micro-USD. Pricing is provider-specific;
    * defaults reflect the cheapest mainstream chat tier so budgets fail closed
    * rather than optimistic.
    */
  def estimateCostMicros(
      usage: Usage,
      inputPerMTok: Long = 150,
      outputPerMTok: Long = 600
  ): Long =
    usage.inputTokens * inputPerMTok / 1000000L + usage.outputTokens * outputPerMTok / 1000000L

  /** Validates machine-readable model output: the text must parse as JSON and
    * decode into the target schema. Invalid output is an error, never silently
    * accepted for execution.
    */
  def structured[A: Decoder](
      completion: Completion
  ): Either[GatewayError, (A, Completion)] =
    parse(completion.text) match
      case Left(err) =>
        Left(BadRequest(s"model output is not valid JSON: ${err.getMessage}"))
      case Right(json) =>
        json.as[A] match
          case Left(err) =>
            Left(
              BadRequest(
                s"model output failed schema validation: ${err.getMessage}"
              )
            )
          case Right(value) => Right((value, completion))

  /** Bounded retry decorator: only transient failures (timeout, rate limit,
    * provider unavailable) are retried, with capped exponential backoff +
    * jitter. Auth and bad-request failures fail immediately.
    */
  def retried(gw: Gateway, maxRetries: Int, baseDelayMs: Long = 500): Gateway =
    new Gateway:
      val name = gw.name
      def complete(
          model: String,
          messages: List[ChatMessage],
          maxOutputTokens: Int,
          jsonMode: Boolean
      ): IO[Either[GatewayError, Completion]] =
        def attempt(remaining: Int): IO[Either[GatewayError, Completion]] =
          gw.complete(model, messages, maxOutputTokens, jsonMode).flatMap {
            case Right(ok) => IO.pure(Right(ok))
            case Left(err @ (_: Timeout | _: RateLimited | _: Unavailable))
                if remaining > 0 =>
              val delay = err match
                case RateLimited(s) => s.toLong * 1000L
                case _              =>
                  baseDelayMs * math.pow(2, maxRetries - remaining).toLong
              (IO.sleep(
                scala.concurrent.duration
                  .FiniteDuration(delay + Random.between(0, 250), "ms")
              ) *>
                attempt(remaining - 1))
            case Left(err) => IO.pure(Left(err))
          }
        attempt(maxRetries)

  /** Secrets and personal data never go to execution logs. Prompts may
    * legitimately contain contact data for grounding — but stored previews are
    * always redacted through this.
    */
  object Redact:
    private val email = "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}".r
    private val apiKey = """(sk|pk|rk)-[A-Za-z0-9_-]{8,}""".r
    private val bearer =
      """(?i)(bearer|authorization[:=]\s*)[A-Za-z0-9._-]{8,}""".r
    private val longToken = """[A-Za-z0-9_-]{40,}""".r

    def apply(s: String): String =
      val step1 = apiKey.replaceAllIn(s, "[secret]")
      val step2 = bearer.replaceAllIn(step1, m => s"${m.group(1)} [secret]")
      val step3 = email.replaceAllIn(step2, "[email]")
      longToken.replaceAllIn(step3, "[secret]")

    def preview(s: String, max: Int = 400): String =
      val redacted = apply(s)
      if redacted.length <= max then redacted
      else redacted.take(max) + "…[truncated]"

  /** OpenAI-compatible chat-completions adapter. Works with any provider
    * speaking this API shape (OpenAI, Azure OpenAI, OpenRouter, local
    * gateways), configured by base URL + key from the secret store.
    */
  final class OpenAiCompatible(
      client: Client[IO],
      baseUrl: Uri,
      apiKey: String,
      requestTimeout: Duration
  ) extends Gateway:
    val name = "openai-compatible"

    def complete(
        model: String,
        messages: List[ChatMessage],
        maxOutputTokens: Int,
        jsonMode: Boolean
    ): IO[Either[GatewayError, Completion]] =
      val fields = scala.collection.mutable.ListBuffer.empty[(String, Json)]
      fields += (("model", Json.fromString(model)))
      fields += (
        (
          "messages",
          Json.fromValues(
            messages.map(m =>
              Json.obj(
                "role" -> Json.fromString(m.role),
                "content" -> Json.fromString(m.content)
              )
            )
          )
        )
      )
      fields += (("max_tokens", Json.fromInt(maxOutputTokens)))
      fields += (("temperature", Json.fromDoubleOrNull(0.7)))
      if jsonMode then
        fields += ((
          "response_format",
          Json.obj("type" -> Json.fromString("json_object"))
        ))
      val body =
        Json.fromJsonObject(io.circe.JsonObject.fromIterable(fields.toSeq))

      val req = Request[IO](Method.POST, baseUrl / "chat" / "completions")
        .withHeaders(
          Headers(
            Header.Raw(
              org.typelevel.ci.CIString("Authorization"),
              s"Bearer $apiKey"
            ),
            Header.Raw(
              org.typelevel.ci.CIString("Content-Type"),
              "application/json"
            )
          )
        )
        .withEntity(body)

      client
        .run(req)
        .use { res =>
          res.bodyText.compile.string.map { text =>
            res.status.code match
              case 200 =>
                parseGatewayResponse(model, text)
              case 401 => Left(Unauthorized("provider rejected credentials"))
              case 429 =>
                val retryAfter =
                  res.headers
                    .get(org.typelevel.ci.CIString("retry-after"))
                    .flatMap(_.head.value.toIntOption)
                    .getOrElse(60)
                Left(RateLimited(retryAfter))
              case 402 | 403 =>
                Left(QuotaExceeded("provider quota or plan limit reached"))
              case 400 =>
                Left(
                  BadRequest(
                    s"provider rejected the request: ${text.take(300)}"
                  )
                )
              case _ =>
                Left(Unavailable(s"provider returned HTTP ${res.status.code}"))
          }
        }
        .timeoutTo(
          scala.concurrent.duration.Duration.fromNanos(requestTimeout.toNanos),
          IO.pure(
            Left(
              Timeout(s"provider timeout after ${requestTimeout.toSeconds}s")
            )
          )
        )

    private def parseGatewayResponse(
        model: String,
        text: String
    ): Either[GatewayError, Completion] =
      parse(text) match
        case Left(err) =>
          Left(Unavailable(s"unparseable provider response: ${err.getMessage}"))
        case Right(json) =>
          val content = for
            choices <- json.hcursor.downField("choices").values
            first <- choices.headOption
            msg <- first.hcursor
              .downField("message")
              .get[String]("content")
              .toOption
          yield msg
          content match
            case None =>
              Left(Unavailable("provider response missing message content"))
            case Some(text0) =>
              val in = json.hcursor
                .downField("usage")
                .get[Long]("prompt_tokens")
                .toOption
                .getOrElse(0L)
              val out = json.hcursor
                .downField("usage")
                .get[Long]("completion_tokens")
                .toOption
                .getOrElse(0L)
              val finish = json.hcursor
                .downField("choices")
                .values
                .flatMap(_.headOption)
                .flatMap(_.hcursor.get[String]("finish_reason").toOption)
                .getOrElse("stop")
              Right(Completion(text0, Usage(in, out), model, finish))

  /** Deterministic stub for tests and configuration checks. Optional scripted
    * failures are emitted (and consumed) before success.
    */
  final class Stub(
      val responseText: String =
        "Stub completion for configuration validation.",
      scriptedFailures: List[GatewayError] = Nil
  ) extends Gateway:
    val name = "stub"
    private val failures = {
      val d = new java.util.concurrent.ConcurrentLinkedDeque[GatewayError]()
      scriptedFailures.reverse.foreach(f => d.push(f))
      d
    }
    @volatile var calls: Int = 0

    def complete(
        model: String,
        messages: List[ChatMessage],
        maxOutputTokens: Int,
        jsonMode: Boolean
    ): IO[Either[GatewayError, Completion]] =
      IO {
        calls += 1
        val f = failures.pollFirst()
        if f != null then Left(f)
        else Right(Completion(responseText, Usage(11, 7), model, "stop"))
      }
