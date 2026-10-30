package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.ai.AiGateway
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.free.FC
import io.circe.Json
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.security.MessageDigest
import java.util.UUID

object AiDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class CreateAgentRequest(
      name: String,
      kind: String,
      provider: Option[String] = None,
      model: Option[String] = None,
      requiresApproval: Option[Boolean] = None,
      maxCredits: Option[Int] = None,
      config: Option[Json] = None
  )
  object CreateAgentRequest:
    given Encoder[CreateAgentRequest] = deriveEncoder
    given Decoder[CreateAgentRequest] = deriveDecoder
    given Schema[CreateAgentRequest] = Schema.derived

  final case class UpdateAgentRequest(
      enabled: Option[Boolean] = None,
      model: Option[String] = None,
      requiresApproval: Option[Boolean] = None,
      maxCredits: Option[Int] = None,
      config: Option[Json] = None
  )
  object UpdateAgentRequest:
    given Encoder[UpdateAgentRequest] = deriveEncoder
    given Decoder[UpdateAgentRequest] = deriveDecoder
    given Schema[UpdateAgentRequest] = Schema.derived

  final case class AgentDto(
      id: UUID,
      name: String,
      kind: String,
      provider: String,
      model: String,
      enabled: Boolean,
      requiresApproval: Boolean,
      maxCredits: Int,
      config: Json
  )
  object AgentDto:
    given Encoder[AgentDto] = deriveEncoder
    given Decoder[AgentDto] = deriveDecoder
    given Schema[AgentDto] = Schema.derived

  final case class CreateTemplateRequest(
      name: String,
      body: String,
      kind: Option[String] = None
  )
  object CreateTemplateRequest:
    given Encoder[CreateTemplateRequest] = deriveEncoder
    given Decoder[CreateTemplateRequest] = deriveDecoder
    given Schema[CreateTemplateRequest] = Schema.derived

  final case class TemplateDto(
      id: UUID,
      name: String,
      kind: String,
      body: String,
      version: Int
  )
  object TemplateDto:
    given Encoder[TemplateDto] = deriveEncoder
    given Decoder[TemplateDto] = deriveDecoder
    given Schema[TemplateDto] = Schema.derived

  final case class CompleteRequest(
      system: Option[String] = None,
      user: String,
      maxOutputTokens: Option[Int] = None,
      jsonMode: Option[Boolean] = None,
      model: Option[String] = None,
      kind: Option[String] = None
  )
  object CompleteRequest:
    given Encoder[CompleteRequest] = deriveEncoder
    given Decoder[CompleteRequest] = deriveDecoder
    given Schema[CompleteRequest] = Schema.derived

  final case class RunStepDto(
      id: UUID,
      stepNo: Int,
      tool: Option[String],
      status: String,
      requestPreview: Option[String],
      responsePreview: Option[String],
      durationMs: Option[Int]
  )
  object RunStepDto:
    given Encoder[RunStepDto] = deriveEncoder
    given Decoder[RunStepDto] = deriveDecoder
    given Schema[RunStepDto] = Schema.derived

  final case class RunDto(
      id: UUID,
      kind: String,
      status: String,
      provider: String,
      model: String,
      inputPreview: Option[String],
      outputPreview: Option[String],
      error: Option[String],
      inputTokens: Long,
      outputTokens: Long,
      costMicros: Long,
      createdAt: String
  )
  object RunDto:
    given Encoder[RunDto] = deriveEncoder
    given Decoder[RunDto] = deriveDecoder
    given Schema[RunDto] = Schema.derived

  final case class RunDetailDto(run: RunDto, steps: List[RunStepDto])
  object RunDetailDto:
    given Encoder[RunDetailDto] = deriveEncoder
    given Decoder[RunDetailDto] = deriveDecoder
    given Schema[RunDetailDto] = Schema.derived

  final case class ApprovalDto(
      id: UUID,
      action: String,
      payload: Json,
      status: String,
      runId: Option[UUID],
      createdAt: String,
      decidedAt: Option[String]
  )
  object ApprovalDto:
    given Encoder[ApprovalDto] = deriveEncoder
    given Decoder[ApprovalDto] = deriveDecoder
    given Schema[ApprovalDto] = Schema.derived

  final case class CreateApprovalRequest(
      action: String,
      runId: Option[UUID] = None,
      payload: Option[Json] = None
  )
  object CreateApprovalRequest:
    given Encoder[CreateApprovalRequest] = deriveEncoder
    given Decoder[CreateApprovalRequest] = deriveDecoder
    given Schema[CreateApprovalRequest] = Schema.derived

  final case class DecideApprovalRequest(decision: String)
  object DecideApprovalRequest:
    given Encoder[DecideApprovalRequest] = deriveEncoder
    given Decoder[DecideApprovalRequest] = deriveDecoder
    given Schema[DecideApprovalRequest] = Schema.derived

/** AI gateway API (Phase 11.1). Every model call is workspace-scoped,
  * budget-checked, size-capped and logged with redacted previews. The gateway
  * only ever produces text — no AI output executes anything by itself; later
  * subphases must route proposals through approvals and deterministic services.
  */
final class AiRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO],
    gateway: Option[AiGateway.Gateway],
    defaultModel: String
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private val maxOutputTokenCap = 4000

  // jsonb columns are read as text and parsed locally (no doobie-circe module).
  private def parseJsonb(s: String): Json =
    io.circe.parser.parse(s).getOrElse(Json.Null)

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def envStatus(env: Envelope): SttpStatus =
    env.code match
      case "not_found"           => SttpStatus.NotFound
      case "conflict"            => SttpStatus.Conflict
      case "validation_failed"   => SttpStatus.UnprocessableEntity
      case "ai_not_configured"   => SttpStatus.ServiceUnavailable
      case "insufficient_budget" => SttpStatus.PaymentRequired
      case "quota_exceeded"      => SttpStatus.PaymentRequired
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

  private val aiConfig = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "config")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val listAgents = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "agents")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[AiDtos.AgentDto]])
    .errorOut(errorOut)

  private val createAgent = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "agents")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiDtos.CreateAgentRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[AiDtos.AgentDto])
    .errorOut(errorOut)

  private val updateAgent = endpoint.patch
    .in(
      "api" / "v1" / "workspaces" / wspath / "ai" / "agents" / path[UUID](
        "agentId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiDtos.UpdateAgentRequest])
    .out(jsonBody[AiDtos.AgentDto])
    .errorOut(errorOut)

  private val listTemplates = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "templates")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[AiDtos.TemplateDto]])
    .errorOut(errorOut)

  private val createTemplate = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "templates")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiDtos.CreateTemplateRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[AiDtos.TemplateDto])
    .errorOut(errorOut)

  private val usage = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "usage")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val complete = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "complete")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiDtos.CompleteRequest])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val listRuns = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "runs")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[AiDtos.RunDto]])
    .errorOut(errorOut)

  private val getRun = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / wspath / "ai" / "runs" / path[UUID]("runId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[AiDtos.RunDetailDto])
    .errorOut(errorOut)

  private val cancelRun = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "ai" / "runs" / path[UUID](
        "runId"
      ) / "cancel"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val listApprovals = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "approvals")
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("status"))
    .out(jsonBody[List[AiDtos.ApprovalDto]])
    .errorOut(errorOut)

  private val createApproval = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "approvals")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiDtos.CreateApprovalRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[AiDtos.ApprovalDto])
    .errorOut(errorOut)

  private val decideApproval = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "ai" / "approvals" / path[UUID](
        "approvalId"
      ) / "decide"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiDtos.DecideApprovalRequest])
    .out(jsonBody[AiDtos.ApprovalDto])
    .errorOut(errorOut)

  // -- budget accounting ------------------------------------------------------------

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

  // -- logic -------------------------------------------------------------------------

  private def configLogic(ws: UUID): IO[Either[(SttpStatus, Envelope), Json]] =
    val q: ConnectionIO[Either[(SttpStatus, Envelope), Json]] =
      for
        month <- usageThisMonth(ws)
        budget <- budgetOf(ws)
      yield Right(
        Json.obj(
          "gatewayConfigured" -> Json.fromBoolean(gateway.isDefined),
          "provider" -> Json.fromString(gateway.fold("none")(_.name)),
          "defaultModel" -> Json.fromString(defaultModel),
          "monthlyUsage" -> Json.obj(
            "tokens" -> Json.fromLong(month._1),
            "costMicros" -> Json.fromLong(month._2)
          ),
          "budget" -> Json.obj(
            "monthlyTokens" -> Json.fromLong(budget._1),
            "monthlyCostMicros" -> Json.fromLong(budget._2)
          ),
          "note" -> Json.fromString(
            "AI provider integration is configured server-side via env; customer data is only sent as explicit prompt content and never used for provider training."
          )
        )
      )
    q.transact(xa)

  private def listAgentsLogic(
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), List[AiDtos.AgentDto]]] =
    sql"""select id, name, kind, provider, model, enabled, requires_approval, max_credits, config
          from public.ai_agents where workspace_id = $ws order by created_at"""
      .query[
        (UUID, String, String, String, String, Boolean, Boolean, Int, String)
      ]
      .to[List]
      .transact(xa)
      .map(rows =>
        Right(rows.map { t =>
          AiDtos.AgentDto(
            t._1,
            t._2,
            t._3,
            t._4,
            t._5,
            t._6,
            t._7,
            t._8,
            parseJsonb(t._9)
          )
        })
      )

  private def createAgentLogic(
      ws: UUID,
      user: UUID,
      req: AiDtos.CreateAgentRequest
  ): IO[Either[(SttpStatus, Envelope), AiDtos.AgentDto]] =
    val kinds = Set("writer", "researcher", "reply_agent", "sales_agent")
    if !kinds.contains(req.kind) then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope(
              "validation_failed",
              s"Unknown agent kind '${req.kind}'. Allowed: ${kinds.mkString(", ")}."
            )
          )
        )
      )
    else
      (for
        id <-
          sql"""insert into public.ai_agents
                (workspace_id, name, kind, provider, model, requires_approval, max_credits, config, created_by)
                values ($ws, ${req.name.trim}, ${req.kind}, ${req.provider
              .getOrElse("openai-compatible")},
                        ${req.model.getOrElse(
              defaultModel
            )}, ${req.requiresApproval.getOrElse(true)},
                        ${req.maxCredits.getOrElse(50)}, ${req.config
              .getOrElse(Json.obj())
              .noSpaces}::jsonb, $user)
                returning id"""
            .query[UUID]
            .unique
        row <-
          sql"""select id, name, kind, provider, model, enabled, requires_approval, max_credits, config
                from public.ai_agents where id = $id"""
            .query[
              (
                  UUID,
                  String,
                  String,
                  String,
                  String,
                  Boolean,
                  Boolean,
                  Int,
                  String
              )
            ]
            .unique
      yield Right(
        AiDtos.AgentDto(
          row._1,
          row._2,
          row._3,
          row._4,
          row._5,
          row._6,
          row._7,
          row._8,
          parseJsonb(row._9)
        )
      ): Either[(SttpStatus, Envelope), AiDtos.AgentDto]).transact(xa)

  private def updateAgentLogic(
      ws: UUID,
      agentId: UUID,
      req: AiDtos.UpdateAgentRequest
  ): IO[Either[(SttpStatus, Envelope), AiDtos.AgentDto]] =
    val sets = List(
      req.enabled.map(e => fr"enabled = $e"),
      req.model.map(m => fr"model = $m"),
      req.requiresApproval.map(r => fr"requires_approval = $r"),
      req.maxCredits.map(c => fr"max_credits = $c"),
      req.config.map(c => fr"config = ${c.noSpaces}::jsonb")
    ).flatten
    if sets.isEmpty then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope("validation_failed", "No changes supplied.")
          )
        )
      )
    else
      (for
        _ <-
          (fr"update public.ai_agents set" ++ sets.intercalate(
            fr","
          ) ++ fr"where id = $agentId and workspace_id = $ws").update.run
        row <-
          sql"""select id, name, kind, provider, model, enabled, requires_approval, max_credits, config
                from public.ai_agents where id = $agentId and workspace_id = $ws"""
            .query[
              (
                  UUID,
                  String,
                  String,
                  String,
                  String,
                  Boolean,
                  Boolean,
                  Int,
                  String
              )
            ]
            .option
      yield row
        .map { t =>
          Right(
            AiDtos.AgentDto(
              t._1,
              t._2,
              t._3,
              t._4,
              t._5,
              t._6,
              t._7,
              t._8,
              parseJsonb(t._9)
            )
          ): Either[(SttpStatus, Envelope), AiDtos.AgentDto]
        }
        .getOrElse(
          Left((SttpStatus.NotFound, Envelope("not_found", "Agent not found.")))
        )).transact(xa)

  private def listTemplatesLogic(
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), List[AiDtos.TemplateDto]]] =
    sql"""select id, name, kind, body, version from public.ai_templates
          where workspace_id = $ws order by name, version desc"""
      .query[(UUID, String, String, String, Int)]
      .to[List]
      .transact(xa)
      .map(rows =>
        Right(rows.map(t => AiDtos.TemplateDto(t._1, t._2, t._3, t._4, t._5)))
      )

  private def createTemplateLogic(
      ws: UUID,
      user: UUID,
      req: AiDtos.CreateTemplateRequest
  ): IO[Either[(SttpStatus, Envelope), AiDtos.TemplateDto]] =
    (for
      next <-
        sql"""select coalesce(max(version), 0) + 1 from public.ai_templates
              where workspace_id = $ws and lower(name) = lower(${req.name.trim})"""
          .query[Int]
          .unique
      row <-
        sql"""insert into public.ai_templates (workspace_id, name, kind, body, version, created_by)
              values ($ws, ${req.name.trim}, ${req.kind.getOrElse(
            "user"
          )}, ${req.body}, $next, $user)
              returning id, name, kind, body, version"""
          .query[(UUID, String, String, String, Int)]
          .unique
    yield Right(
      AiDtos.TemplateDto(row._1, row._2, row._3, row._4, row._5)
    ): Either[
      (SttpStatus, Envelope),
      AiDtos.TemplateDto
    ]).transact(xa)

  private def usageLogic(ws: UUID): IO[Either[(SttpStatus, Envelope), Json]] =
    val q: ConnectionIO[Either[(SttpStatus, Envelope), Json]] =
      for
        month <- usageThisMonth(ws)
        budget <- budgetOf(ws)
      yield Right(
        Json.obj(
          "month" -> Json.obj(
            "inputTokens" -> Json.fromLong(0),
            "totalTokens" -> Json.fromLong(month._1),
            "costMicros" -> Json.fromLong(month._2)
          ),
          "budgetTokens" -> Json.fromLong(budget._1),
          "budgetCostMicros" -> Json.fromLong(budget._2)
        )
      )
    q.transact(xa)

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

  private def completeLogic(
      ws: UUID,
      user: UUID,
      req: AiDtos.CompleteRequest
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    gateway match
      case None =>
        IO.pure(
          Left(
            (
              SttpStatus.ServiceUnavailable,
              Envelope(
                "ai_not_configured",
                "No AI provider is configured for this deployment (AI_API_KEY missing). Configure an approved provider to enable AI features."
              )
            )
          )
        )
      case Some(gw) =>
        val model = req.model.getOrElse(defaultModel)
        val maxTokens = math.max(
          1,
          math.min(maxOutputTokenCap, req.maxOutputTokens.getOrElse(512))
        )
        val jsonMode = req.jsonMode.getOrElse(false)
        val kind =
          if Set("writer", "research", "reply", "sales_agent", "evaluation")
              .contains(req.kind.getOrElse(""))
          then req.kind.getOrElse("completion")
          else "completion"
        val messages =
          req.system
            .map(s => AiGateway.ChatMessage("system", s))
            .toList :+ AiGateway.ChatMessage("user", req.user)
        val digest = MessageDigest
          .getInstance("SHA-256")
          .digest(
            (req.system.getOrElse("") + "\u0000" + req.user).getBytes("UTF-8")
          )
          .map("%02x".format(_))
          .mkString

        val budgetAndRun: ConnectionIO[Either[(SttpStatus, Envelope), UUID]] =
          for
            month <- usageThisMonth(ws)
            budget <- budgetOf(ws)
            over =
              if month._1 >= budget._1 || month._2 >= budget._2 then true
              else false
            result <-
              if over then
                FC.pure(
                  Left(
                    (
                      SttpStatus.PaymentRequired,
                      Envelope(
                        "insufficient_budget",
                        "Workspace AI budget exhausted for this month. Raise the budget or wait for the reset."
                      )
                    )
                  ): Either[(SttpStatus, Envelope), UUID]
                )
              else
                sql"""insert into public.ai_runs
                      (workspace_id, kind, status, provider, model, input_digest, input_preview, requested_by)
                      values ($ws, $kind, 'running', ${gw.name}, $model, $digest,
                              ${AiGateway.Redact.preview(
                    req.user
                  )}, $user) returning id"""
                  .query[UUID]
                  .unique
                  .map(sid => Right(sid): Either[(SttpStatus, Envelope), UUID])
          yield result

        budgetAndRun.transact(xa).flatMap {
          case Left(env)    => IO.pure(Left(env))
          case Right(runId) =>
            val started = System.currentTimeMillis()
            gw.complete(model, messages, maxTokens, jsonMode).flatMap {
              case Left(err) =>
                (for
                  _ <-
                    sql"""update public.ai_runs set status = 'failed', error = ${err.detail}, finished_at = now()
                          where id = $runId""".update.run.void
                  _ <-
                    sql"""insert into public.ai_run_steps
                          (run_id, workspace_id, step_no, tool, status, request_preview, response_preview)
                          values ($runId, $ws, 1, 'model', 'failed', ${AiGateway.Redact
                        .preview(req.user)}, ${AiGateway.Redact.preview(
                        err.detail
                      )})""".update.run.void
                yield ())
                  .transact(xa) *> IO.pure(Left(gatewayErrorEnvelope(err)))
              case Right(c) =>
                val cost = AiGateway.estimateCostMicros(c.usage)
                val duration = (System.currentTimeMillis() - started).toInt
                (for
                  _ <-
                    sql"""update public.ai_runs set status = 'completed',
                          output_preview = ${AiGateway.Redact.preview(c.text)},
                          input_tokens = ${c.usage.inputTokens}, output_tokens = ${c.usage.outputTokens},
                          cost_micros = $cost, finished_at = now()
                          where id = $runId""".update.run.void
                  _ <-
                    sql"""insert into public.ai_usage
                          (workspace_id, run_id, provider, model, input_tokens, output_tokens, cost_micros)
                          values ($ws, $runId, ${gw.name}, ${c.model}, ${c.usage.inputTokens}, ${c.usage.outputTokens}, $cost)""".update.run.void
                  _ <-
                    sql"""insert into public.ai_run_steps
                          (run_id, workspace_id, step_no, tool, status, request_preview, response_preview, duration_ms)
                          values ($runId, $ws, 1, 'model', 'ok', ${AiGateway.Redact
                        .preview(req.user)},
                                  ${AiGateway.Redact.preview(
                        c.text
                      )}, $duration)""".update.run.void
                yield ()).transact(xa) *>
                  IO.pure(
                    Right(
                      Json.obj(
                        "runId" -> Json.fromString(runId.toString),
                        "text" -> Json.fromString(c.text),
                        "model" -> Json.fromString(c.model),
                        "finishReason" -> Json.fromString(c.finishReason),
                        "inputTokens" -> Json.fromLong(c.usage.inputTokens),
                        "outputTokens" -> Json.fromLong(c.usage.outputTokens),
                        "costMicros" -> Json.fromLong(cost)
                      )
                    )
                  )
            }
        }

  private def runDtoRead(
      ws: UUID,
      limit: Int,
      runId: Option[UUID]
  ): ConnectionIO[List[
    (
        UUID,
        String,
        String,
        String,
        String,
        Option[String],
        Option[String],
        Option[String],
        Long,
        Long,
        Long,
        String
    )
  ]] =
    val base =
      fr"""select id, kind, status, provider, model, input_preview, output_preview, error,
                    input_tokens, output_tokens, cost_micros, created_at::text
                    from public.ai_runs where workspace_id = $ws""" ++
        runId.fold(fr"""""")(rid => fr" and id = $rid")
    (base ++ fr"order by created_at desc limit $limit")
      .query[
        (
            UUID,
            String,
            String,
            String,
            String,
            Option[String],
            Option[String],
            Option[String],
            Long,
            Long,
            Long,
            String
        )
      ]
      .to[List]

  private def toRunDto(
      t: (
          UUID,
          String,
          String,
          String,
          String,
          Option[String],
          Option[String],
          Option[String],
          Long,
          Long,
          Long,
          String
      )
  ): AiDtos.RunDto =
    AiDtos.RunDto(
      t._1,
      t._2,
      t._3,
      t._4,
      t._5,
      t._6,
      t._7,
      t._8,
      t._9,
      t._10,
      t._11,
      t._12
    )

  private def listRunsLogic(
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), List[AiDtos.RunDto]]] =
    runDtoRead(ws, 50, None).transact(xa).map(rows => Right(rows.map(toRunDto)))

  private def getRunLogic(
      ws: UUID,
      runId: UUID
  ): IO[Either[(SttpStatus, Envelope), AiDtos.RunDetailDto]] =
    val q =
      for
        runs <- runDtoRead(ws, 1, Some(runId))
        steps <-
          sql"""select id, step_no, tool, status, request_preview, response_preview, duration_ms
                from public.ai_run_steps where run_id = $runId and workspace_id = $ws order by step_no"""
            .query[
              (
                  UUID,
                  Int,
                  Option[String],
                  String,
                  Option[String],
                  Option[String],
                  Option[Int]
              )
            ]
            .to[List]
      yield runs.headOption.map(r => (r, steps))
    q.transact(xa).map {
      case None =>
        Left((SttpStatus.NotFound, Envelope("not_found", "Run not found.")))
      case Some((r, steps)) =>
        Right(
          AiDtos.RunDetailDto(
            toRunDto(r),
            steps.map(s =>
              AiDtos.RunStepDto(s._1, s._2, s._3, s._4, s._5, s._6, s._7)
            )
          )
        )
    }

  private def cancelRunLogic(
      ws: UUID,
      runId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    sql"""update public.ai_runs set status = 'cancelled', finished_at = now()
          where id = $runId and workspace_id = $ws and status in ('pending','running')
          returning id"""
      .query[UUID]
      .option
      .transact(xa)
      .map {
        case Some(id) =>
          Right(
            Json.obj(
              "runId" -> Json.fromString(id.toString),
              "status" -> Json.fromString("cancelled")
            )
          )
        case None =>
          Left(
            (
              SttpStatus.Conflict,
              Envelope(
                "conflict",
                "Run not found or is not in a cancellable state."
              )
            )
          )
      }

  private def approvalDto(
      t: (UUID, String, Json, String, Option[UUID], String, Option[String])
  ): AiDtos.ApprovalDto =
    AiDtos.ApprovalDto(t._1, t._2, t._3, t._4, t._5, t._6, t._7)

  private def listApprovalsLogic(
      ws: UUID,
      status: Option[String]
  ): IO[Either[(SttpStatus, Envelope), List[AiDtos.ApprovalDto]]] =
    val base =
      fr"""select id, action, payload, status, run_id, created_at::text, decided_at::text
                    from public.ai_approvals where workspace_id = $ws""" ++
        status.fold(fr"""""")(s => fr" and status = $s")
    (base ++ fr"order by created_at desc limit 50")
      .query[
        (UUID, String, String, String, Option[UUID], String, Option[String])
      ]
      .to[List]
      .transact(xa)
      .map(rows =>
        Right(rows.map(t => approvalDto(t.copy(_3 = parseJsonb(t._3)))))
      )

  private def createApprovalLogic(
      ws: UUID,
      user: UUID,
      req: AiDtos.CreateApprovalRequest
  ): IO[Either[(SttpStatus, Envelope), AiDtos.ApprovalDto]] =
    sql"""insert into public.ai_approvals (workspace_id, run_id, action, payload, status, requested_by)
          values ($ws, ${req.runId}, ${req.action}, ${req.payload
        .getOrElse(Json.obj())
        .noSpaces}::jsonb, 'pending', $user)
          returning id, action, payload::text, status, run_id, created_at::text, decided_at::text"""
      .query[
        (UUID, String, String, String, Option[UUID], String, Option[String])
      ]
      .unique
      .transact(xa)
      .map(t => Right(approvalDto(t.copy(_3 = parseJsonb(t._3)))))

  private def decideApprovalLogic(
      ws: UUID,
      user: UUID,
      approvalId: UUID,
      decision: String
  ): IO[Either[(SttpStatus, Envelope), AiDtos.ApprovalDto]] =
    if decision != "approved" && decision != "rejected" then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope(
              "validation_failed",
              "Decision must be 'approved' or 'rejected'."
            )
          )
        )
      )
    else
      sql"""update public.ai_approvals set status = $decision, decided_by = $user, decided_at = now()
            where id = $approvalId and workspace_id = $ws and status = 'pending'
            returning id, action, payload::text, status, run_id, created_at::text, decided_at::text"""
        .query[
          (UUID, String, String, String, Option[UUID], String, Option[String])
        ]
        .option
        .transact(xa)
        .map {
          case Some(t) => Right(approvalDto(t.copy(_3 = parseJsonb(t._3))))
          case None    =>
            Left(
              (
                SttpStatus.NotFound,
                Envelope(
                  "not_found",
                  "Pending approval not found in this workspace."
                )
              )
            )
        }

  // -- assembly --------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      aiConfig.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => configLogic(w)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      listAgents.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => listAgentsLogic(w)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                AiDtos.AgentDto
              ]]]
            )
        }
      ),
      createAgent.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => createAgentLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), AiDtos.AgentDto]]
            )
        }
      ),
      updateAgent.serverLogic((w, agentId, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => updateAgentLogic(w, agentId, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), AiDtos.AgentDto]]
            )
        }
      ),
      listTemplates.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => listTemplatesLogic(w)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                AiDtos.TemplateDto
              ]]]
            )
        }
      ),
      createTemplate.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => createTemplateLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), AiDtos.TemplateDto]]
            )
        }
      ),
      usage.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => usageLogic(w)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      complete.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => completeLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      listRuns.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => listRunsLogic(w)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                AiDtos.RunDto
              ]]]
            )
        }
      ),
      getRun.serverLogic((w, runId, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => getRunLogic(w, runId)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), AiDtos.RunDetailDto]
              ]
            )
        }
      ),
      cancelRun.serverLogic((w, runId, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => cancelRunLogic(ws = w, runId = runId)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      listApprovals.serverLogic((w, auth, status) =>
        requireView(auth, w).flatMap {
          case Right(_)    => listApprovalsLogic(w, status)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                AiDtos.ApprovalDto
              ]]]
            )
        }
      ),
      createApproval.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => createApprovalLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), AiDtos.ApprovalDto]]
            )
        }
      ),
      decideApproval.serverLogic((w, approvalId, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) =>
            decideApprovalLogic(w, user, approvalId, req.decision)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), AiDtos.ApprovalDto]]
            )
        }
      )
    )
  )
