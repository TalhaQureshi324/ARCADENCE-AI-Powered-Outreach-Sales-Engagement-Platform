package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.ai.{AiGateway, AiWriter}
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

object AiWriterDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class CreateResearchRequest(
      claim: String,
      contactId: Option[UUID] = None,
      companyDomain: Option[String] = None,
      sourceUrl: Option[String] = None,
      verified: Option[Boolean] = None
  )
  object CreateResearchRequest:
    given Encoder[CreateResearchRequest] = deriveEncoder
    given Decoder[CreateResearchRequest] = deriveDecoder
    given Schema[CreateResearchRequest] = Schema.derived

  final case class ResearchDto(
      id: UUID,
      contactId: Option[UUID],
      companyDomain: Option[String],
      claim: String,
      sourceUrl: Option[String],
      sourceKind: String,
      retrievedAt: String,
      verified: Boolean
  )
  object ResearchDto:
    given Encoder[ResearchDto] = deriveEncoder
    given Decoder[ResearchDto] = deriveDecoder
    given Schema[ResearchDto] = Schema.derived

  final case class WriteRequest(
      objective: String,
      offer: String,
      persona: String,
      tone: String,
      cta: String,
      length: String,
      personalizationLevel: String,
      researchIds: Option[List[UUID]] = None,
      variations: Option[Int] = None,
      contactId: Option[UUID] = None
  )
  object WriteRequest:
    given Encoder[WriteRequest] = deriveEncoder
    given Decoder[WriteRequest] = deriveDecoder
    given Schema[WriteRequest] = Schema.derived

  final case class MessageDto(
      id: UUID,
      runId: Option[UUID],
      contactId: Option[UUID],
      threadId: Option[UUID],
      draftSubject: Option[String],
      draftBody: Option[String],
      finalSubject: Option[String],
      finalBody: Option[String],
      status: String,
      evidence: Json
  )
  object MessageDto:
    given Encoder[MessageDto] = deriveEncoder
    given Decoder[MessageDto] = deriveDecoder
    given Schema[MessageDto] = Schema.derived

  final case class EditMessageRequest(
      finalSubject: Option[String] = None,
      finalBody: Option[String] = None
  )
  object EditMessageRequest:
    given Encoder[EditMessageRequest] = deriveEncoder
    given Decoder[EditMessageRequest] = deriveDecoder
    given Schema[EditMessageRequest] = Schema.derived

  final case class RenderRequest(variables: Map[String, String])
  object RenderRequest:
    given Encoder[RenderRequest] = deriveEncoder
    given Decoder[RenderRequest] = deriveDecoder
    given Schema[RenderRequest] = Schema.derived

/** AI writer + researcher routes (Phase 11.2). Drafts are proposals only: they
  * carry deterministic evaluation flags (unsupported claims, unknown variables,
  * deceptive phrases), are stored as ai_messages drafts, and never touch
  * sending or suppression paths — approved/edited drafts are consumed later by
  * the deterministic composer.
  */
final class AiWriterRoutes(
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
      case "conflict"            => SttpStatus.Conflict
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

  private def requireView(
      auth: Option[String],
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    auth match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(e) =>
            IO.pure(
              Left((SttpStatus.Unauthorized, Envelope(e.code, e.message)))
            )
          case Right(user) =>
            authorizer
              .requirePermission(user, ws, Permission.ViewWorkspace)
              .map {
                case Left(error) => Left(failed(error))
                case Right(_)    => Right(())
              }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  // -- endpoints ------------------------------------------------------------------

  private val wspath = path[UUID]("workspaceId")

  private val createResearch = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "research")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiWriterDtos.CreateResearchRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[AiWriterDtos.ResearchDto])
    .errorOut(errorOut)

  private val listResearch = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "research")
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[UUID]]("contactId"))
    .out(jsonBody[List[AiWriterDtos.ResearchDto]])
    .errorOut(errorOut)

  private val deleteResearch = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / wspath / "ai" / "research" / path[UUID](
        "itemId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val write = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "write")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiWriterDtos.WriteRequest])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val listMessages = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "messages")
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("status"))
    .out(jsonBody[List[AiWriterDtos.MessageDto]])
    .errorOut(errorOut)

  private val editMessage = endpoint.patch
    .in(
      "api" / "v1" / "workspaces" / wspath / "ai" / "messages" / path[UUID](
        "messageId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiWriterDtos.EditMessageRequest])
    .out(jsonBody[AiWriterDtos.MessageDto])
    .errorOut(errorOut)

  private val decideMessage = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "ai" / "messages" / path[UUID](
        "messageId"
      ) / path[String]("decision")
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[AiWriterDtos.MessageDto])
    .errorOut(errorOut)

  private val renderMessage = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "ai" / "messages" / path[UUID](
        "messageId"
      ) / "render"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiWriterDtos.RenderRequest])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  // -- shared bits ------------------------------------------------------------------

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

  private def budgetGate(
      ws: UUID
  ): ConnectionIO[Either[(SttpStatus, Envelope), Unit]] =
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

  private def messageDto(
      t: (
          UUID,
          Option[UUID],
          Option[UUID],
          Option[UUID],
          Option[String],
          Option[String],
          Option[String],
          Option[String],
          String,
          String
      )
  ): Either[String, AiWriterDtos.MessageDto] =
    Right(
      AiWriterDtos.MessageDto(
        t._1,
        t._2,
        t._3,
        t._4,
        t._5,
        t._6,
        t._7,
        t._8,
        t._9,
        parseJsonb(t._10)
      )
    )

  private val messageSelect =
    fr"""select id, run_id, contact_id, thread_id, draft_subject, draft_body,
      final_subject, final_body, status, evidence::text from public.ai_messages where workspace_id ="""

  // -- logic -------------------------------------------------------------------------

  private def createResearchLogic(
      ws: UUID,
      user: UUID,
      req: AiWriterDtos.CreateResearchRequest
  ): IO[Either[(SttpStatus, Envelope), AiWriterDtos.ResearchDto]] =
    sql"""insert into public.ai_research_items
          (workspace_id, contact_id, company_domain, claim, source_url, source_kind, verified, created_by)
          values ($ws, ${req.contactId}, ${req.companyDomain}, ${req.claim.trim}, ${req.sourceUrl},
                  'manual', ${req.verified.getOrElse(false)}, $user)
          returning id, contact_id, company_domain, claim, source_url, source_kind, retrieved_at::text, verified"""
      .query[
        (
            UUID,
            Option[UUID],
            Option[String],
            String,
            Option[String],
            String,
            String,
            Boolean
        )
      ]
      .unique
      .transact(xa)
      .map { t =>
        Right(
          AiWriterDtos
            .ResearchDto(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8)
        )
      }

  private def listResearchLogic(
      ws: UUID,
      contactId: Option[UUID]
  ): IO[Either[(SttpStatus, Envelope), List[AiWriterDtos.ResearchDto]]] =
    val base = fr"""select id, contact_id, company_domain, claim, source_url, source_kind,
                    retrieved_at::text, verified from public.ai_research_items where workspace_id =""" ++
      fr"$ws" ++ contactId.fold(fr"")(cid => fr" and contact_id = $cid")
    (base ++ fr"order by created_at desc limit 200")
      .query[
        (
            UUID,
            Option[UUID],
            Option[String],
            String,
            Option[String],
            String,
            String,
            Boolean
        )
      ]
      .to[List]
      .transact(xa)
      .map(rows =>
        Right(
          rows.map(t =>
            AiWriterDtos
              .ResearchDto(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8)
          )
        )
      )

  private def deleteResearchLogic(
      ws: UUID,
      itemId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    sql"""delete from public.ai_research_items where id = $itemId and workspace_id = $ws returning id"""
      .query[UUID]
      .option
      .transact(xa)
      .map {
        case Some(id) =>
          Right(Json.obj("deleted" -> Json.fromString(id.toString)))
        case None =>
          Left(
            (
              SttpStatus.NotFound,
              Envelope("not_found", "Research item not found.")
            )
          )
      }

  private def writeLogic(
      ws: UUID,
      user: UUID,
      req: AiWriterDtos.WriteRequest
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    gateway match
      case None     => IO.pure(notConfigured)
      case Some(gw) =>
        val variations = math.max(1, math.min(3, req.variations.getOrElse(1)))
        val brief = AiWriter.WriterBrief(
          objective = req.objective,
          offer = req.offer,
          persona = req.persona,
          tone = req.tone,
          cta = req.cta,
          length = req.length,
          personalizationLevel = req.personalizationLevel
        )
        val budgetAndFacts: ConnectionIO[
          Either[(SttpStatus, Envelope), List[AiWriter.ResearchFact]]
        ] =
          for
            gate <- budgetGate(ws)
            facts <-
              if gate.isLeft then FC.pure(Nil)
              else
                req.researchIds.getOrElse(Nil).toNel match
                  case None      => FC.pure(Nil)
                  case Some(ids) =>
                    sql"""select id, claim, source_url, retrieved_at::text from public.ai_research_items
                          where workspace_id = $ws and id::text = any(${ids.toList
                        .map(_.toString)
                        .toArray})"""
                      .query[(UUID, String, Option[String], Option[String])]
                      .to[List]
                      .map { rows =>
                        val byId = rows.map(r => r._1.toString -> r).toMap
                        req.researchIds
                          .getOrElse(Nil)
                          .flatMap(id => byId.get(id.toString))
                          .zipWithIndex
                          .map { case (r, i) =>
                            AiWriter.ResearchFact(
                              s"f${i + 1}",
                              r._2,
                              r._3,
                              r._4
                            )
                          }
                      }
          yield gate.map(_ => facts)

        budgetAndFacts.transact(xa).flatMap {
          case Left(env)    => IO.pure(Left(env))
          case Right(facts) =>
            val providedKeys = facts.map(_.key).toSet
            val messages = AiWriter.briefMessages(brief, facts, variations)
            val digest = MessageDigest
              .getInstance("SHA-256")
              .digest(
                messages.map(_.content).mkString("\u0000").getBytes("UTF-8")
              )
              .map("%02x".format(_))
              .mkString
            val openRun: ConnectionIO[UUID] =
              sql"""insert into public.ai_runs
                    (workspace_id, kind, status, provider, model, input_digest, input_preview, requested_by)
                    values ($ws, 'writer', 'running', ${gw.name}, $defaultModel, $digest,
                            ${AiGateway.Redact.preview(
                  messages.last.content
                )}, $user) returning id"""
                .query[UUID]
                .unique
            openRun.transact(xa).flatMap { runId =>
              val started = System.currentTimeMillis()
              gw.complete(defaultModel, messages, 1600, jsonMode = true)
                .flatMap {
                  case Left(err) =>
                    (for _ <-
                        sql"""update public.ai_runs set status = 'failed', error = ${err.detail}, finished_at = now()
                               where id = $runId""".update.run.void
                    yield ())
                      .transact(xa) *> IO.pure(Left(gatewayErrorEnvelope(err)))
                  case Right(completion) =>
                    AiGateway.structured[AiWriter.WriterOutput](
                      completion
                    ) match
                      case Left(err) =>
                        (for _ <-
                            sql"""update public.ai_runs set status = 'failed', error = ${err.detail}, finished_at = now()
                                   where id = $runId""".update.run.void
                        yield ()).transact(xa) *>
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
                      case Right((output, _)) =>
                        val cost =
                          AiGateway.estimateCostMicros(completion.usage)
                        val rows = output.variations.map { d =>
                          val flags = AiWriter.evaluate(d, providedKeys)
                          val evidence = Json.obj(
                            "promptVersion" -> Json
                              .fromString(AiWriter.promptVersion),
                            "flags" -> Json
                              .fromValues(flags.map(Json.fromString)),
                            "claims" -> Json.fromValues(
                              d.claims.map(c =>
                                Json.obj(
                                  "factKey" -> Json.fromString(c.factKey),
                                  "quote" -> Json.fromString(c.quote)
                                )
                              )
                            ),
                            "facts" -> Json.fromValues(
                              facts.map(f =>
                                Json.obj(
                                  "key" -> Json.fromString(f.key),
                                  "claim" -> Json.fromString(f.claim),
                                  "sourceUrl" -> f.sourceUrl
                                    .map(Json.fromString)
                                    .getOrElse(Json.Null),
                                  "retrievedAt" -> f.retrievedAt
                                    .map(Json.fromString)
                                    .getOrElse(Json.Null)
                                )
                              )
                            )
                          )
                          (d, flags, evidence)
                        }
                        val persist: ConnectionIO[List[UUID]] =
                          for
                            _ <-
                              sql"""update public.ai_runs set status = 'completed',
                                  output_preview = ${AiGateway.Redact.preview(
                                  completion.text
                                )},
                                  input_tokens = ${completion.usage.inputTokens},
                                  output_tokens = ${completion.usage.outputTokens},
                                  cost_micros = $cost, finished_at = now()
                                  where id = $runId""".update.run.void
                            _ <-
                              sql"""insert into public.ai_usage
                                  (workspace_id, run_id, provider, model, input_tokens, output_tokens, cost_micros)
                                  values ($ws, $runId, ${gw.name}, ${completion.model},
                                          ${completion.usage.inputTokens}, ${completion.usage.outputTokens}, $cost)""".update.run.void
                            _ <-
                              sql"""insert into public.ai_run_steps
                                  (run_id, workspace_id, step_no, tool, status, request_preview, response_preview, duration_ms)
                                  values ($runId, $ws, 1, 'model', 'ok',
                                          ${AiGateway.Redact.preview(
                                  messages.last.content
                                )},
                                          ${AiGateway.Redact.preview(
                                  completion.text
                                )},
                                          ${(System
                                  .currentTimeMillis() - started).toInt})""".update.run.void
                            ids <-
                              rows.traverse { case (d, _, evidence) =>
                                sql"""insert into public.ai_messages
                                    (workspace_id, run_id, contact_id, draft_subject, draft_body, status, evidence)
                                    values ($ws, $runId, ${req.contactId}, ${d.subject}, ${d.body}, 'draft',
                                            ${evidence.noSpaces}::jsonb)
                                    returning id"""
                                  .query[UUID]
                                  .unique
                              }
                          yield ids
                        persist.transact(xa).map { ids =>
                          Right(
                            Json.obj(
                              "runId" -> Json.fromString(runId.toString),
                              "drafts" -> Json.fromValues(
                                ids.zip(rows).map { case (id, (d, flags, _)) =>
                                  Json.obj(
                                    "id" -> Json.fromString(id.toString),
                                    "subject" -> Json.fromString(d.subject),
                                    "body" -> Json.fromString(d.body),
                                    "flags" -> Json
                                      .fromValues(flags.map(Json.fromString))
                                  )
                                }
                              ),
                              "note" -> Json.fromString(
                                "Drafts are proposals with evaluation flags. Approve and edit before any deterministic rendering; sending rules and suppression are enforced elsewhere and never bypassed."
                              )
                            )
                          )
                        }
                }
            }
        }

  private def loadMessage(
      ws: UUID,
      messageId: UUID
  ): ConnectionIO[Option[AiWriterDtos.MessageDto]] =
    (messageSelect ++ fr"$ws and id = $messageId")
      .query[
        (
            UUID,
            Option[UUID],
            Option[UUID],
            Option[UUID],
            Option[String],
            Option[String],
            Option[String],
            Option[String],
            String,
            String
        )
      ]
      .option
      .map(_.flatMap(messageDto(_).toOption))

  private def listMessagesLogic(
      ws: UUID,
      status: Option[String]
  ): IO[Either[(SttpStatus, Envelope), List[AiWriterDtos.MessageDto]]] =
    val base =
      messageSelect ++ fr"$ws" ++ status.fold(fr"")(s => fr" and status = $s")
    (base ++ fr"order by created_at desc limit 100")
      .query[
        (
            UUID,
            Option[UUID],
            Option[UUID],
            Option[UUID],
            Option[String],
            Option[String],
            Option[String],
            Option[String],
            String,
            String
        )
      ]
      .to[List]
      .transact(xa)
      .map(rows => Right(rows.flatMap(messageDto(_).toOption)))

  private def editMessageLogic(
      ws: UUID,
      messageId: UUID,
      req: AiWriterDtos.EditMessageRequest
  ): IO[Either[(SttpStatus, Envelope), AiWriterDtos.MessageDto]] =
    val sets = List(
      req.finalSubject.map(s => fr"final_subject = $s"),
      req.finalBody.map(b => fr"final_body = $b"),
      Some(fr"status = 'edited'")
    ).flatten
    (for
      _ <-
        (fr"update public.ai_messages set" ++ sets.intercalate(fr",") ++
          fr"where id = $messageId and workspace_id = $ws and status in ('draft','edited')").update.run
      row <- loadMessage(ws, messageId)
    yield row match
      case Some(dto) => Right(dto)
      case None      =>
        Left(
          (
            SttpStatus.NotFound,
            Envelope("not_found", "Draft not found or not editable.")
          )
        )
    )
      .transact(xa)

  private def decideMessageLogic(
      ws: UUID,
      messageId: UUID,
      decision: String
  ): IO[Either[(SttpStatus, Envelope), AiWriterDtos.MessageDto]] =
    val newStatus = decision match
      case "approve" => "approved"
      case "reject"  => "rejected"
      case _         => ""
    if newStatus.isEmpty then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope("validation_failed", "Decision must be approve or reject.")
          )
        )
      )
    else
      (for
        _ <-
          sql"""update public.ai_messages set status = $newStatus
                where id = $messageId and workspace_id = $ws and status in ('draft','edited')""".update.run
        row <- loadMessage(ws, messageId)
      yield row match
        case Some(dto) => Right(dto)
        case None      =>
          Left(
            (
              SttpStatus.NotFound,
              Envelope(
                "not_found",
                "Draft not found or not in a decidable state."
              )
            )
          )
      )
        .transact(xa)

  private def renderMessageLogic(
      ws: UUID,
      messageId: UUID,
      req: AiWriterDtos.RenderRequest
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    loadMessage(ws, messageId).transact(xa).flatMap {
      case None =>
        IO.pure(
          Left((SttpStatus.NotFound, Envelope("not_found", "Draft not found.")))
        )
      case Some(dto) =>
        val subject = dto.finalSubject.orElse(dto.draftSubject).getOrElse("")
        val body = dto.finalBody.orElse(dto.draftBody).getOrElse("")
        (
          AiWriter.render(subject, req.variables),
          AiWriter.render(body, req.variables)
        ) match
          case (Right(rs), Right(rb)) =>
            IO.pure(
              Right(
                Json.obj(
                  "subject" -> Json.fromString(rs),
                  "body" -> Json.fromString(rb),
                  "note" -> Json.fromString(
                    "Deterministic render preview. Final sends still pass campaign/suppression rules."
                  )
                )
              )
            )
          case (Left(e), _) =>
            IO.pure(
              Left(
                (
                  SttpStatus.UnprocessableEntity,
                  Envelope("validation_failed", e)
                )
              )
            )
          case (_, Left(e)) =>
            IO.pure(
              Left(
                (
                  SttpStatus.UnprocessableEntity,
                  Envelope("validation_failed", e)
                )
              )
            )
    }

  // -- assembly --------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      createResearch.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => createResearchLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), AiWriterDtos.ResearchDto]
              ]
            )
        }
      ),
      listResearch.serverLogic((w, auth, contactId) =>
        requireView(auth, w).flatMap {
          case Right(_)    => listResearchLogic(w, contactId)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                AiWriterDtos.ResearchDto
              ]]]
            )
        }
      ),
      deleteResearch.serverLogic((w, itemId, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => deleteResearchLogic(w, itemId)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      write.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => writeLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      listMessages.serverLogic((w, auth, status) =>
        requireView(auth, w).flatMap {
          case Right(_)    => listMessagesLogic(w, status)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                AiWriterDtos.MessageDto
              ]]]
            )
        }
      ),
      editMessage.serverLogic((w, messageId, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => editMessageLogic(w, messageId, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), AiWriterDtos.MessageDto]
              ]
            )
        }
      ),
      renderMessage.serverLogic((w, messageId, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => renderMessageLogic(w, messageId, req)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      decideMessage.serverLogic((w, messageId, decision, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => decideMessageLogic(w, messageId, decision)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), AiWriterDtos.MessageDto]
              ]
            )
        }
      )
    )
  )
