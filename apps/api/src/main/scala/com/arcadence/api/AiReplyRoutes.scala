package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.ai.{AiGateway, AiReply, AiWriter}
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.free.FC
import io.circe.Json
import io.circe.parser.parse
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.security.MessageDigest
import java.util.UUID

object AiReplyDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class ReplyDraftRequest(
      threadId: UUID,
      instruction: Option[String] = None
  )
  object ReplyDraftRequest:
    given Encoder[ReplyDraftRequest] = deriveEncoder
    given Decoder[ReplyDraftRequest] = deriveDecoder
    given Schema[ReplyDraftRequest] = Schema.derived

/** AI Reply Agent routes (Phase 11.3). Proposes intent classification, reply
  * drafts and next actions for authorized conversations, with evidence
  * references. Deterministic rules take precedence: unsubscribe and bounce
  * threads never reach the model (Phase 6 sync already applied suppression/list
  * hygiene), every external response requires human approval before it can be
  * sent, and any sent result stays in the deterministic inbox path.
  */
final class AiReplyRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO],
    gateway: Option[AiGateway.Gateway],
    defaultModel: String
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def envStatus(env: Envelope): SttpStatus =
    env.code match
      case "not_found"           => SttpStatus.NotFound
      case "validation_failed"   => SttpStatus.UnprocessableEntity
      case "ai_not_configured"   => SttpStatus.ServiceUnavailable
      case "insufficient_budget" => SttpStatus.PaymentRequired
      case "ai_provider_error"   => SttpStatus.BadGateway
      case "ai_rate_limited"     => SttpStatus.TooManyRequests
      case _                     => SttpStatus.InternalServerError

  private def requireEdit(
      auth: Option[String],
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), UUID]] =
    auth match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(e) =>
            IO.pure(
              Left((SttpStatus.Unauthorized, Envelope(e.code, e.message)))
            )
          case Right(user) =>
            authorizer.requirePermission(user, ws, Permission.EditContent).map {
              case Left(error) => Left(failed(error))
              case Right(m)    => Right(m.userId)
            }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private val wspath = path[UUID]("workspaceId")

  private val replyDraft = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "reply" / "draft")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiReplyDtos.ReplyDraftRequest])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private def parseJsonb(s: String): Json = parse(s).getOrElse(Json.Null)

  private def gatewayErrorEnvelope(
      err: AiGateway.GatewayError
  ): (SttpStatus, Envelope) = err match
    case AiGateway.Timeout(_) =>
      (
        SttpStatus.BadGateway,
        Envelope("ai_provider_error", s"Model provider timeout: ${err.detail}")
      )
    case AiGateway.RateLimited(_) =>
      (SttpStatus.TooManyRequests, Envelope("ai_rate_limited", err.detail))
    case AiGateway.QuotaExceeded(_) =>
      (SttpStatus.PaymentRequired, Envelope("quota_exceeded", err.detail))
    case AiGateway.Unauthorized(_) =>
      (
        SttpStatus.BadGateway,
        Envelope("ai_provider_error", "Model provider rejected credentials.")
      )
    case AiGateway.BadRequest(d) =>
      (
        SttpStatus.UnprocessableEntity,
        Envelope("validation_failed", s"Model rejected the request: $d")
      )
    case AiGateway.Unavailable(d) =>
      (
        SttpStatus.BadGateway,
        Envelope("ai_provider_error", s"Model provider unavailable: $d")
      )
    case AiGateway.NotConfigured(d) =>
      (SttpStatus.ServiceUnavailable, Envelope("ai_not_configured", d))

  private def usageThisMonth(ws: UUID): ConnectionIO[(Long, Long)] =
    sql"""select coalesce(sum(input_tokens + output_tokens), 0), coalesce(sum(cost_micros), 0)
          from public.ai_usage where workspace_id = $ws
            and created_at >= date_trunc('month', now())"""
      .query[(Long, Long)]
      .unique

  private def budgetOf(ws: UUID): ConnectionIO[(Long, Long)] =
    sql"select ai_monthly_token_limit, ai_monthly_spend_micros from public.workspace where id = $ws"
      .query[(Long, Long)]
      .option
      .map(_.getOrElse((0L, 0L)))

  private def notConfigured: Either[(SttpStatus, Envelope), Nothing] =
    Left(
      (
        SttpStatus.ServiceUnavailable,
        Envelope(
          "ai_not_configured",
          "No AI provider is configured for this deployment (AI_API_KEY missing)."
        )
      )
    )

  private def replyDraftLogic(
      ws: UUID,
      user: UUID,
      req: AiReplyDtos.ReplyDraftRequest
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    gateway match
      case None     => IO.pure(notConfigured)
      case Some(gw) =>
        // Thread + contact + deterministic classification (workspace-scoped).
        val loadThread: ConnectionIO[
          Option[(UUID, Option[UUID], Option[UUID], String, String)]
        ] =
          sql"""select t.id, t.contact_id, t.campaign_id, t.subject, t.classification::text
                from public.email_threads t where t.id = ${req.threadId} and t.workspace_id = $ws"""
            .query[(UUID, Option[UUID], Option[UUID], String, String)]
            .option
        loadThread.transact(xa).flatMap {
          case None =>
            IO.pure(
              Left(
                (
                  SttpStatus.NotFound,
                  Envelope("not_found", "Thread not found.")
                )
              )
            )
          case Some(
                (
                  threadId,
                  contactId,
                  campaignId,
                  threadSubject,
                  threadClassification
                )
              ) =>
            // Deterministic short-circuit: suppression and bounce hygiene are
            // Phase 6 sync rules — the model is never consulted for them.
            AiReply.deterministicShortCircuit(threadClassification) match
              case Some(kind) =>
                IO.pure(
                  Right(
                    Json.obj(
                      "intent" -> Json.fromString(kind),
                      "modelCalled" -> Json.fromBoolean(false),
                      "note" -> Json.fromString(
                        s"Deterministic $kind handling already applied at sync time (suppression/list hygiene are independent of AI). No reply draft is proposed."
                      )
                    )
                  )
                )
              case None =>
                val budgetGate
                    : ConnectionIO[Either[(SttpStatus, Envelope), Unit]] =
                  for
                    month <- usageThisMonth(ws)
                    budget <- budgetOf(ws)
                  yield
                    if month._1 >= budget._1 || month._2 >= budget._2 then
                      Left(
                        (
                          SttpStatus.PaymentRequired,
                          Envelope(
                            "insufficient_budget",
                            "Workspace AI budget exhausted for this month."
                          )
                        )
                      )
                    else Right(())
                budgetGate.transact(xa).flatMap {
                  case Left(env) => IO.pure(Left(env))
                  case Right(_)  =>
                    val gather: ConnectionIO[
                      (
                          List[(String, String, Option[String])],
                          List[(String, Option[String], Option[String])],
                          Option[String],
                          Option[String]
                      )
                    ] =
                      for
                        msgs <-
                          sql"""select direction, body_text, classification::text from public.email_messages
                                where thread_id = $threadId and workspace_id = $ws
                                order by occurred_at desc limit 6"""
                            .query[(String, String, Option[String])]
                            .to[List]
                        latestClass = msgs.headOption
                          .flatMap(_._3)
                          .getOrElse(threadClassification)
                        knowledgeRows <-
                          contactId.fold(
                            sql"""select claim, source_url, retrieved_at::text from public.ai_research_items
                                  where workspace_id = $ws and contact_id is null
                                  order by created_at desc limit 10"""
                              .query[(String, Option[String], Option[String])]
                              .to[List]
                          )(cid =>
                            sql"""select claim, source_url, retrieved_at::text from public.ai_research_items
                                  where workspace_id = $ws and (contact_id = $cid or contact_id is null)
                                  order by created_at desc limit 10"""
                              .query[(String, Option[String], Option[String])]
                              .to[List]
                          )
                        campaignCtx <-
                          campaignId.fold(FC.pure(None: Option[String]))(cid =>
                            sql"select name from public.campaign where id = $cid and workspace_id = $ws"
                              .query[String]
                              .option
                          )
                        contactCtx <-
                          contactId.fold(FC.pure(None: Option[String]))(cid =>
                            sql"""select concat_ws(' ', first_name, last_name)
                                  from public.contact where id = $cid and workspace_id = $ws"""
                              .query[String]
                              .option
                          )
                      yield (msgs, knowledgeRows, campaignCtx, contactCtx)
                    gather.transact(xa).flatMap {
                      case (msgsDesc, knowledgeRows, campaignCtx, contactCtx) =>
                        val knowledge = knowledgeRows.zipWithIndex.map {
                          case (k, i) =>
                            AiWriter.ResearchFact(
                              s"f${i + 1}",
                              k._1,
                              k._2,
                              k._3
                            )
                        }
                        val providedKeys = knowledge.map(_.key).toSet
                        val deterministicClass =
                          msgsDesc.headOption
                            .flatMap(_._3)
                            .getOrElse(threadClassification)
                        val messages = AiReply.replyMessages(
                          threadSubject = threadSubject,
                          deterministicClassification = deterministicClass,
                          campaignContext = campaignCtx,
                          contactContext = contactCtx,
                          knowledge = knowledge,
                          messages = msgsDesc.reverse
                            .map(m => AiReply.ThreadMessage(m._1, m._2, m._3)),
                          instruction = req.instruction
                        )
                        val digest = MessageDigest
                          .getInstance("SHA-256")
                          .digest(
                            messages
                              .map(_.content)
                              .mkString("\u0000")
                              .getBytes("UTF-8")
                          )
                          .map("%02x".format(_))
                          .mkString
                        val openRun: ConnectionIO[UUID] =
                          sql"""insert into public.ai_runs
                              (workspace_id, kind, status, provider, model, input_digest, input_preview, requested_by)
                              values ($ws, 'reply', 'running', ${gw.name}, $defaultModel, $digest,
                                      ${AiGateway.Redact.preview(
                              messages.last.content
                            )}, $user) returning id"""
                            .query[UUID]
                            .unique
                        openRun.transact(xa).flatMap { runId =>
                          val started = System.currentTimeMillis()
                          gw.complete(
                            defaultModel,
                            messages,
                            1200,
                            jsonMode = true
                          ).flatMap {
                            case Left(err) =>
                              sql"""update public.ai_runs set status = 'failed', error = ${err.detail}, finished_at = now()
                                  where id = $runId""".update.run.void
                                .transact(xa) *>
                                IO.pure(Left(gatewayErrorEnvelope(err)))
                            case Right(completion) =>
                              AiGateway.structured[AiReply.ReplyOutput](
                                completion
                              ) match
                                case Left(err) =>
                                  sql"""update public.ai_runs set status = 'failed', error = ${err.detail}, finished_at = now()
                                      where id = $runId""".update.run.void
                                    .transact(xa) *>
                                    IO.pure(
                                      Left(
                                        (
                                          SttpStatus.UnprocessableEntity,
                                          Envelope(
                                            "validation_failed",
                                            s"Model output failed validation: ${err.detail}"
                                          )
                                        )
                                      )
                                    )
                                case Right((out, _)) =>
                                  val flags =
                                    AiReply.validate(out, providedKeys)
                                  val hardBad = flags.exists(f =>
                                    f.startsWith("invalid_intent:") || f
                                      .startsWith("invalid_action:")
                                  )
                                  if hardBad then
                                    sql"""update public.ai_runs set status = 'failed', error = ${"proposal rejected: " + flags
                                        .mkString(",")}, finished_at = now()
                                        where id = $runId""".update.run.void
                                      .transact(xa) *>
                                      IO.pure(
                                        Left(
                                          (
                                            SttpStatus.UnprocessableEntity,
                                            Envelope(
                                              "validation_failed",
                                              s"Agent proposal rejected by deterministic validation: ${flags.mkString(", ")}"
                                            )
                                          )
                                        )
                                      )
                                  else
                                    val cost = AiGateway.estimateCostMicros(
                                      completion.usage
                                    )
                                    val evidence = Json.obj(
                                      "promptVersion" -> Json
                                        .fromString(AiReply.promptVersion),
                                      "flags" -> Json
                                        .fromValues(flags.map(Json.fromString)),
                                      "deterministicClassification" -> Json
                                        .fromString(deterministicClass),
                                      "intent" -> Json.fromString(out.intent),
                                      "confidence" -> Json
                                        .fromString(out.confidence),
                                      "nextActions" -> Json.fromValues(
                                        out.nextActions.map(a =>
                                          Json.obj(
                                            "action" -> Json
                                              .fromString(a.action),
                                            "rationale" -> Json
                                              .fromString(a.rationale),
                                            "factKeys" -> Json.fromValues(
                                              a.factKeys.map(Json.fromString)
                                            )
                                          )
                                        )
                                      ),
                                      "evidence" -> Json.fromValues(
                                        out.evidence.map(e =>
                                          Json.obj(
                                            "factKey" -> Json
                                              .fromString(e.factKey),
                                            "how" -> Json.fromString(e.how)
                                          )
                                        )
                                      ),
                                      "facts" -> Json.fromValues(
                                        knowledge.map(f =>
                                          Json.obj(
                                            "key" -> Json.fromString(f.key),
                                            "claim" -> Json.fromString(f.claim),
                                            "sourceUrl" -> f.sourceUrl
                                              .map(Json.fromString)
                                              .getOrElse(Json.Null)
                                          )
                                        )
                                      )
                                    )
                                    val persist: ConnectionIO[UUID] =
                                      for
                                        _ <-
                                          sql"""update public.ai_runs set status = 'awaiting_approval',
                                              output_preview = ${AiGateway.Redact
                                              .preview(completion.text)},
                                              input_tokens = ${completion.usage.inputTokens},
                                              output_tokens = ${completion.usage.outputTokens},
                                              cost_micros = $cost, finished_at = now()
                                              where id = $runId""".update.run.void
                                        _ <-
                                          sql"""insert into public.ai_usage
                                              (workspace_id, run_id, provider, model, input_tokens, output_tokens, cost_micros)
                                              values ($ws, $runId, ${gw.name}, ${completion.model},
                                                      ${completion.usage.inputTokens}, ${completion.usage.outputTokens}, $cost)""".update.run.void
                                        messageId <-
                                          sql"""insert into public.ai_messages
                                              (workspace_id, run_id, contact_id, thread_id, draft_subject, draft_body, status, evidence)
                                              values ($ws, $runId, $contactId, $threadId, ${out.replySubject}, ${out.replyBody},
                                                      'draft', ${evidence.noSpaces}::jsonb)
                                              returning id"""
                                            .query[UUID]
                                            .unique
                                        _ <-
                                          sql"""insert into public.ai_approvals (workspace_id, run_id, action, payload, status, requested_by)
                                              values ($ws, $runId, 'send_reply', ${Json
                                              .obj(
                                                "aiMessageId" -> Json
                                                  .fromString(
                                                    messageId.toString
                                                  )
                                              )
                                              .noSpaces}::jsonb, 'pending', $user)""".update.run.void
                                      yield messageId
                                    persist.transact(xa).map { messageId =>
                                      Right(
                                        Json.obj(
                                          "runId" -> Json
                                            .fromString(runId.toString),
                                          "aiMessageId" -> Json
                                            .fromString(messageId.toString),
                                          "modelCalled" -> Json
                                            .fromBoolean(true),
                                          "intent" -> Json
                                            .fromString(out.intent),
                                          "confidence" -> Json
                                            .fromString(out.confidence),
                                          "flags" -> Json.fromValues(
                                            flags.map(Json.fromString)
                                          ),
                                          "reply" -> Json.obj(
                                            "subject" -> Json
                                              .fromString(out.replySubject),
                                            "body" -> Json
                                              .fromString(out.replyBody)
                                          ),
                                          "note" -> Json.fromString(
                                            "Proposal only. Sending requires human approval; unsubscribe/bounce handling remains deterministic and independent of this output."
                                          )
                                        )
                                      )
                                    }
                          }
                        }
                    }
                }
        }

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      replyDraft.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => replyDraftLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      )
    )
  )
