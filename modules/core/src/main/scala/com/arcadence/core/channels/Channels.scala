package com.arcadence.core.channels

import cats.effect.IO
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.time.Duration
import java.util.UUID

/** Optional multichannel interface (Phase 12.5). SMS and calling are pluggable
  * adapters behind this one contract; email remains fully independent. Every
  * send is gated on the contact's channel-specific consent — the adapter
  * refuses to run when consent is absent, and the caller (Scala service) checks
  * consent BEFORE invoking the adapter. No platform scraping, impersonation, or
  * third-party ToS violations.
  */
object Channels:

  def consentAllows(consent: String): Boolean = consent == "opted_in"

  sealed trait ChannelResult
  final case class Sent(providerMessageId: String) extends ChannelResult
  final case class Blocked(reason: String) extends ChannelResult
  final case class Failed(reason: String) extends ChannelResult

  /** Minimal SMS adapter contract. Providers (Twilio, MessageBird, ...) plug in
    * by implementing send; base URL injected for stub tests.
    */
  trait SmsAdapter:
    def send(
        fromNumber: String,
        toNumber: String,
        text: String
    ): IO[ChannelResult]

  /** Generic HTTP JSON SMS adapter matching the common provider shape (POST
    * {from, to, text} -> {sid/message_id}). Point base-url at the configured
    * provider; auth header style is provider-specific.
    */
  final class JsonSms(
      client: Client[IO],
      baseUrl: String,
      apiKey: String,
      authHeader: String = "Authorization",
      requestTimeout: Duration = Duration.ofSeconds(10)
  ) extends SmsAdapter:
    def send(
        fromNumber: String,
        toNumber: String,
        text: String
    ): IO[ChannelResult] =
      val body = Json.obj(
        "from" -> Json.fromString(fromNumber),
        "to" -> Json.fromString(toNumber),
        "text" -> Json.fromString(text)
      )
      val req = Request[IO](Method.POST, Uri.unsafeFromString(baseUrl))
        .withEntity(body)
        .withContentType(`Content-Type`(MediaType.application.json))
        .putHeaders(Header.Raw(CIString(authHeader), apiKey))
      client
        .run(req)
        .use(res => res.bodyText.compile.string.map(t => (res.status.code, t)))
        .timeoutTo(
          scala.concurrent.duration.Duration.fromNanos(requestTimeout.toNanos),
          IO.pure((-1, "timeout"))
        )
        .map {
          case (code, _) if code >= 200 && code < 300  => Sent(code.toString)
          case (code, _) if code == 401 || code == 403 =>
            Blocked(s"provider rejected credentials (HTTP $code)")
          case (code, text) => Failed(s"HTTP $code: ${text.take(200)}")
        }

  /** Calling: Arcadence records call interactions (via click-to-call or a
    * telephony provider's webhook); the adapter contract logs outcomes. No
    * automated voice dialing is implemented.
    */
  trait CallAdapter:
    def logCall(
        workspaceId: UUID,
        contactId: UUID,
        summary: String,
        outcome: Option[String]
    ): IO[ChannelResult]

  final class DbCallAdapter(xa: doobie.Transactor[IO]) extends CallAdapter:
    def logCall(
        workspaceId: UUID,
        contactId: UUID,
        summary: String,
        outcome: Option[String]
    ): IO[ChannelResult] =
      sql"""insert into public.channel_interactions
            (workspace_id, contact_id, channel, direction, summary, outcome)
            values ($workspaceId, $contactId, 'call', 'outbound', $summary, $outcome)""".update.run
        .transact(xa)
        .map(_ => Sent("logged"))
