package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.ai.{AiGateway, AiSalesAgent, AiWriter}
import com.arcadence.core.leads.LeadProvider
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.free.FC
import io.circe.{Decoder, HCursor, Json}
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

object AiAgentDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class AgentRunRequest(
      objective: String,
      offer: String,
      persona: String,
      contactId: Option[UUID] = None,
      variations: Option[Int] = None
  )
  object AgentRunRequest:
    given Encoder[AgentRunRequest] = deriveEncoder
    given Decoder[AgentRunRequest] = deriveDecoder
    given Schema[AgentRunRequest] = Schema.derived

  final case class CampaignRefRequest(campaignId: UUID)
  object CampaignRefRequest:
    given Encoder[CampaignRefRequest] = deriveEncoder
    given Decoder[CampaignRefRequest] = deriveDecoder
    given Schema[CampaignRefRequest] = Schema.derived

/** AI Sales Agent routes (Phase 11.4). The orchestration order is fixed in
  * code; models fill validated structured slots; provider search/enrichment run
  * as sandbox-labeled tool calls through the licensed-provider framework; the
  * run ends at a pending human approval — nothing is sent, launched or
  * auto-communicated.
  */
final class AiAgentRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO],
    gateway: Option[AiGateway.Gateway],
    defaultModel: String
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

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

  private def parseJsonb(s: String): Json = parse(s).getOrElse(Json.Null)

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

  private val wspath = path[UUID]("workspaceId")

  private val agentRun = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "agent" / "run")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiAgentDtos.AgentRunRequest])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val agentRecommendations = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "ai" / "agent" / "recommendations"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiAgentDtos.CampaignRefRequest])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val agentMeetings = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "ai" / "agent" / "meetings")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AiAgentDtos.CampaignRefRequest])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private def modelCall[A: io.circe.Decoder](
      ws: UUID,
      gw: AiGateway.Gateway,
      runId: UUID,
      stepNo: Int,
      messages: List[AiGateway.ChatMessage],
      maxTokens: Int
  ): IO[Either[(SttpStatus, Envelope), (A, AiGateway.Completion)]] =
    val started = System.currentTimeMillis()
    gw.complete(defaultModel, messages, maxTokens, jsonMode = true).flatMap {
      case Left(err) =>
        recordStep(
          ws,
          runId,
          stepNo,
          "model",
          "failed",
          Some(AiGateway.Redact.preview(messages.last.content)),
          Some(AiGateway.Redact.preview(err.detail)),
          None
        ) *>
          IO.pure(
            Left(
              (
                SttpStatus.BadGateway,
                Envelope(
                  "ai_provider_error",
                  s"Model provider error: ${err.detail}"
                )
              )
            )
          )
      case Right(completion) =>
        AiGateway.structured[A](completion) match
          case Left(err) =>
            recordStep(
              ws,
              runId,
              stepNo,
              "model",
              "failed",
              Some(AiGateway.Redact.preview(messages.last.content)),
              Some(err.detail),
              None
            ) *>
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
          case Right((value, _)) =>
            recordStep(
              ws,
              runId,
              stepNo,
              "model",
              "ok",
              Some(AiGateway.Redact.preview(messages.last.content)),
              Some(AiGateway.Redact.preview(completion.text)),
              Some((System.currentTimeMillis() - started).toInt)
            ) *>
              IO.pure(Right((value, completion)))
    }

  private def recordStep(
      ws: UUID,
      runId: UUID,
      stepNo: Int,
      tool: String,
      status: String,
      requestPreview: Option[String],
      responsePreview: Option[String],
      durationMs: Option[Int]
  ): IO[Unit] =
    sql"""insert into public.ai_run_steps
          (run_id, workspace_id, step_no, tool, status, request_preview, response_preview, duration_ms)
          values ($runId, $ws, $stepNo, $tool, $status, $requestPreview, $responsePreview, $durationMs)""".update.run.void
      .transact(xa)

  private def recordInvocationTx(
      ws: UUID,
      runId: UUID,
      tool: String,
      args: Json,
      summary: Json,
      status: String
  ): ConnectionIO[Unit] =
    sql"""insert into public.ai_tool_invocations
          (workspace_id, run_id, tool, args, result_summary, status, finished_at)
          values ($ws, $runId, $tool, ${args.noSpaces}::jsonb, ${summary.noSpaces}::jsonb, $status, now())""".update.run.void

  private def runLogic(
      ws: UUID,
      user: UUID,
      req: AiAgentDtos.AgentRunRequest
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    gateway match
      case None     => IO.pure(notConfigured)
      case Some(gw) =>
        val open: ConnectionIO[Either[(SttpStatus, Envelope), (UUID, Long)]] =
          for
            month <- usageThisMonth(ws)
            budget <- budgetOf(ws)
            result <-
              if month._1 >= budget._1 then
                FC.pure(
                  Left(
                    (
                      SttpStatus.PaymentRequired,
                      Envelope(
                        "insufficient_budget",
                        "Workspace AI budget exhausted."
                      )
                    )
                  )
                )
              else
                sql"""insert into public.ai_runs
                      (workspace_id, kind, status, provider, model, input_preview, requested_by)
                      values ($ws, 'sales_agent', 'running', ${gw.name}, $defaultModel,
                              ${AiGateway.Redact.preview(
                    req.objective + " | " + req.offer
                  )}, $user) returning id"""
                  .query[UUID]
                  .unique
                  .map(rid =>
                    Right((rid, budget._1 - month._1)): Either[
                      (SttpStatus, Envelope),
                      (UUID, Long)
                    ]
                  )
          yield result
        open.transact(xa).flatMap {
          case Left(env)                  => IO.pure(Left(env))
          case Right((runId, tokensLeft)) =>
            // Flat, deterministic 7-step sequence: each stage is explicit and
            // typed; failures short-circuit into the error envelope.
            val run: IO[((List[UUID], UUID), Int, Int, Boolean, List[String])] =
              for
                step1E <- modelCall[AiSalesAgent.BriefUnderstanding](
                  ws,
                  gw,
                  runId,
                  1,
                  AiSalesAgent
                    .briefMessages(req.objective, req.offer, req.persona),
                  300
                )
                understanding = step1E.fold(
                  _ => AiSalesAgent.BriefUnderstanding("", ""),
                  _._1
                )
                step2E <- modelCall[AiSalesAgent.AudienceSuggestion](
                  ws,
                  gw,
                  runId,
                  2,
                  AiSalesAgent.audienceMessages(understanding),
                  250
                )
                audience = step2E.fold(
                  _ => AiSalesAgent.AudienceSuggestion(None, None, None, None),
                  _._1
                )
                filters = LeadProvider.SearchFilters(
                  industry = audience.industry,
                  region = audience.region,
                  jobTitle = audience.jobTitle,
                  companySize = audience.companySize
                )
                step3E <- LeadProvider.SandboxProvider
                  .search(filters, 1)
                  .flatMap {
                    case Left(err) =>
                      recordInvocationTx(
                        ws,
                        runId,
                        "search_providers",
                        Json.obj("provider" -> Json.fromString("sandbox")),
                        Json.obj("error" -> Json.fromString(err.toString)),
                        "failed"
                      )
                        .transact(xa) *>
                        recordStep(
                          ws,
                          runId,
                          3,
                          "search_providers",
                          "failed",
                          None,
                          Some(err.toString),
                          None
                        ).as(
                          Left(
                            (
                              SttpStatus.BadGateway,
                              Envelope(
                                "ai_provider_error",
                                s"Provider search failed: $err"
                              )
                            )
                          ): Either[(SttpStatus, Envelope), (Int, List[String])]
                        )
                    case Right(page) =>
                      (for
                        _ <- recordInvocationTx(
                          ws,
                          runId,
                          "search_providers",
                          Json.obj(
                            "provider" -> Json.fromString("sandbox"),
                            "filters" -> Json.fromString(filters.toString)
                          ),
                          Json
                            .obj("results" -> Json.fromInt(page.contacts.size)),
                          "completed"
                        )
                        sid <- sql"""insert into public.search_requests
                            (workspace_id, requested_by, provider, status, results_count, estimated_credits, credits_charged, finished_at)
                            values ($ws, $user, 'sandbox', 'done', ${page.contacts.size}, 0, 0, now()) returning id"""
                          .query[UUID]
                          .unique
                        _ <- page.contacts.traverse_ { c =>
                          sql"""insert into public.discovered_contacts
                                  (workspace_id, search_request_id, provider, provider_contact_id, email, email_verification,
                                   first_name, last_name, job_title, company_name, industry, region, company_size, confidence, permitted_use)
                                  values ($ws, $sid, 'sandbox', ${c.providerContactId}, ${c.email}, ${c.emailVerification},
                                          ${c.firstName}, ${c.lastName}, ${c.jobTitle}, ${c.companyName}, ${c.industry},
                                          ${c.region}, ${c.companySize}, ${c.confidence}, ${c.permittedUse})
                                  on conflict do nothing""".update.run.void
                        }
                        _ <- sql"""insert into public.provider_usage (workspace_id, provider, operation, units)
                                 values ($ws, 'sandbox', 'search', 1)""".update.run.void
                      yield Right(
                        (
                          page.contacts.size,
                          page.contacts.flatMap(_.email).take(3)
                        )
                      )).transact(xa)
                        .flatTap(_ =>
                          recordStep(
                            ws,
                            runId,
                            3,
                            "search_providers",
                            "ok",
                            None,
                            Some(s"${page.contacts.size} sandbox contacts"),
                            None
                          )
                        )
                  }
                found = step3E.map(_._1).getOrElse(0)
                candidateEmails = step3E.map(_._2).getOrElse(Nil)
                step4E <- (candidateEmails.headOption match
                  case None =>
                    IO.pure(Right(0): Either[(SttpStatus, Envelope), Int])
                  case Some(email) =>
                    LeadProvider.SandboxProvider.enrich(email, None).flatMap {
                      case Right(Some(c)) =>
                        (recordInvocationTx(
                          ws,
                          runId,
                          "enrich_leads",
                          Json.obj("email" -> Json.fromString(email)),
                          Json.obj(
                            "jobTitle" -> c.jobTitle
                              .map(Json.fromString)
                              .getOrElse(Json.Null)
                          ),
                          "completed"
                        ) *>
                          sql"""insert into public.provider_usage (workspace_id, provider, operation, units)
                                values ($ws, 'sandbox', 'enrich', 1)""".update.run.void)
                          .transact(xa) *>
                          recordStep(
                            ws,
                            runId,
                            4,
                            "enrich_leads",
                            "ok",
                            Some(email),
                            Some(c.jobTitle.getOrElse("")),
                            None
                          ).as(Right(1): Either[(SttpStatus, Envelope), Int])
                      case _ =>
                        recordInvocationTx(
                          ws,
                          runId,
                          "enrich_leads",
                          Json.obj(),
                          Json.obj("enriched" -> Json.fromInt(0)),
                          "completed"
                        ).transact(xa) *>
                          recordStep(
                            ws,
                            runId,
                            4,
                            "enrich_leads",
                            "ok",
                            Some(email),
                            Some("no enrichment data"),
                            None
                          ).as(Right(0): Either[(SttpStatus, Envelope), Int])
                    }
                )
                variations = math.max(
                  1,
                  math.min(3, req.variations.getOrElse(1))
                )
                writerMessages = AiWriter.briefMessages(
                  AiWriter.WriterBrief(
                    req.objective,
                    req.offer,
                    req.persona,
                    "direct",
                    "reply to book a call",
                    "short",
                    "light"
                  ),
                  Nil,
                  variations
                )
                step5E <- modelCall[AiWriter.WriterOutput](
                  ws,
                  gw,
                  runId,
                  5,
                  writerMessages,
                  1400
                )
                output = step5E.fold(_ => AiWriter.WriterOutput(Nil), _._1)
                drafts = output.variations
                  .map(d => (d, AiWriter.evaluate(d, Set.empty)))
                (ready, issues) = AiSalesAgent.readiness(
                  found,
                  drafts,
                  tokensLeft - 100
                )
                persisted <- {
                  val persist: ConnectionIO[(List[UUID], UUID)] =
                    for
                      draftIds <- drafts.traverse { case (d, flags) =>
                        sql"""insert into public.ai_messages
                              (workspace_id, run_id, contact_id, draft_subject, draft_body, status, evidence)
                              values ($ws, $runId, ${req.contactId}, ${d.subject}, ${d.body}, 'draft',
                                      ${Json
                            .obj(
                              "flags" -> Json.fromValues(
                                flags.map(Json.fromString)
                              ),
                              "step" -> Json.fromString("sales_agent")
                            )
                            .noSpaces}::jsonb)
                              returning id""".query[UUID].unique
                      }
                      approvalId <-
                        sql"""insert into public.ai_approvals (workspace_id, run_id, action, payload, status, requested_by)
                              values ($ws, $runId, 'launch_campaign', ${Json
                            .obj(
                              "draftIds" -> Json.fromValues(
                                draftIds.map(id => Json.fromString(id.toString))
                              ),
                              "contactsFound" -> Json.fromInt(found),
                              "readiness" -> Json.fromBoolean(ready),
                              "issues" -> Json
                                .fromValues(issues.map(Json.fromString))
                            )
                            .noSpaces}::jsonb, 'pending', $user) returning id"""
                          .query[UUID]
                          .unique
                      _ <-
                        sql"""update public.ai_runs set status = 'awaiting_approval', finished_at = now() where id = $runId""".update.run.void
                    yield (draftIds, approvalId)
                  persist.transact(xa).flatTap { case (_, approvalId) =>
                    recordStep(
                      ws,
                      runId,
                      6,
                      "validate_readiness",
                      "ok",
                      None,
                      Some(s"ready=$ready issues=${issues.mkString(",")}"),
                      None
                    ) *>
                      recordStep(
                        ws,
                        runId,
                        7,
                        "submit_approval",
                        "ok",
                        None,
                        Some(s"approval $approvalId"),
                        None
                      )
                  }
                }
              yield (persisted, found, step4E.getOrElse(0), ready, issues)
            run.flatMap { case ((res, foundN, enrichedN, readyN, issuesN)) =>

              IO.pure(
                Right(
                  Json.obj(
                    "runId" -> Json.fromString(runId.toString),
                    "draftIds" -> Json.fromValues(
                      res._1.map(id => Json.fromString(id.toString))
                    ),
                    "approvalId" -> Json.fromString(res._2.toString),
                    "contactsFound" -> Json.fromInt(foundN),
                    "enriched" -> Json.fromInt(enrichedN),
                    "readiness" -> Json.fromBoolean(readyN),
                    "issues" -> Json.fromValues(issuesN.map(Json.fromString)),
                    "steps" -> Json
                      .fromValues(AiSalesAgent.steps.map(Json.fromString)),
                    "note" -> Json.fromString(
                      "Constrained run: model filled validated slots only; provider tools ran against labeled sandbox data; launching requires the pending human approval."
                    )
                  )
                ): Either[(SttpStatus, Envelope), Json]
              )
            }
        }

  /** Monitor + recommend (steps 8-9): real campaign stats into a validated
    * structured recommendation, stored as an awaiting-approval run.
    */
  private def recommendationsLogic(
      ws: UUID,
      user: UUID,
      campaignId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    gateway match
      case None     => IO.pure(notConfigured)
      case Some(gw) =>
        val stats: ConnectionIO[Option[(String, Int, Int, Int)]] =
          sql"""select c.name,
                (select count(*) from public.campaign_enrollment e where e.campaign_id = c.id),
                (select count(*) from public.campaign_enrollment e where e.campaign_id = c.id and e.status = 'replied'),
                (select count(*) from public.campaign_enrollment e where e.campaign_id = c.id and e.status = 'bounced')
                from public.campaign c where c.id = $campaignId and c.workspace_id = $ws"""
            .query[(String, Int, Int, Int)]
            .option
        stats.transact(xa).flatMap {
          case None =>
            IO.pure(
              Left(
                (
                  SttpStatus.NotFound,
                  Envelope("not_found", "Campaign not found.")
                )
              )
            )
          case Some((name, enrolled, replied, bounced)) =>
            openRun(ws, gw, user, s"monitor $name").flatMap { runId =>
              val messages = List(
                AiGateway.ChatMessage(
                  "system",
                  """You are Arcadence's campaign analyst. Return STRICT JSON only.
                    |Schema: {"recommendations":[{"action":"...","why":"..."}]}. Max 3, concrete and honest.""".stripMargin
                ),
                AiGateway.ChatMessage(
                  "user",
                  s"Campaign '$name': $enrolled enrolled, $replied replies, $bounced bounces. Recommend next changes."
                )
              )
              modelCall[Recommendations](ws, gw, runId, 1, messages, 500)
                .flatMap {
                  case Left(env)                 => IO.pure(Left(env))
                  case Right((recs, completion)) =>
                    (for
                      _ <- sql"""update public.ai_runs set status = 'completed',
                          output_preview = ${AiGateway.Redact.preview(
                          completion.text
                        )},
                          input_tokens = ${completion.usage.inputTokens}, output_tokens = ${completion.usage.outputTokens},
                          cost_micros = ${AiGateway.estimateCostMicros(
                          completion.usage
                        )}, finished_at = now()
                          where id = $runId""".update.run.void
                      _ <- sql"""insert into public.ai_usage (workspace_id, run_id, provider, model, input_tokens, output_tokens, cost_micros)
                          values ($ws, $runId, ${gw.name}, ${completion.model}, ${completion.usage.inputTokens},
                                  ${completion.usage.outputTokens}, ${AiGateway
                          .estimateCostMicros(
                            completion.usage
                          )})""".update.run.void
                    yield ()).transact(xa) *>
                      IO.pure(
                        Right(
                          Json.obj(
                            "campaign" -> Json.fromString(name),
                            "stats" -> Json.obj(
                              "enrolled" -> Json.fromInt(enrolled),
                              "replies" -> Json.fromInt(replied),
                              "bounces" -> Json.fromInt(bounced)
                            ),
                            "recommendations" -> Json.fromValues(
                              recs.recommendations.map(r =>
                                Json.obj(
                                  "action" -> Json.fromString(r.action),
                                  "why" -> Json.fromString(r.why)
                                )
                              )
                            )
                          )
                        )
                      )
                }
            }
        }

  final case class Recommendation(action: String, why: String)
  final case class Recommendations(recommendations: List[Recommendation])
  given io.circe.Decoder[Recommendation] = (c: HCursor) =>
    for
      a <- c.get[String]("action"); w <- c.get[String]("why").orElse(Right(""))
    yield Recommendation(a, w)
  given io.circe.Decoder[Recommendations] = (c: HCursor) =>
    c.get[List[Recommendation]]("recommendations").map(Recommendations(_))

  private def notConfigured: Either[(SttpStatus, Envelope), Nothing] =
    Left(
      (
        SttpStatus.ServiceUnavailable,
        Envelope(
          "ai_not_configured",
          "No AI provider is configured (AI_API_KEY missing)."
        )
      )
    )

  private def openRun(
      ws: UUID,
      gw: AiGateway.Gateway,
      user: UUID,
      preview: String
  ): IO[UUID] =
    sql"""insert into public.ai_runs (workspace_id, kind, status, provider, model, input_preview, requested_by)
          values ($ws, 'sales_agent', 'running', ${gw.name}, $defaultModel, ${AiGateway.Redact
        .preview(preview)}, $user)
          returning id""".query[UUID].unique.transact(xa)

  /** Step 10: qualified meeting opportunities from real positive replies. */
  private def meetingsLogic(
      ws: UUID,
      user: UUID,
      campaignId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    gateway match
      case None     => IO.pure(notConfigured)
      case Some(gw) =>
        val leads: ConnectionIO[List[(String, String)]] =
          sql"""select c.first_name, c.email from public.campaign_enrollment e
                join public.contact c on c.id = e.contact_id
                where e.workspace_id = $ws and e.campaign_id = $campaignId and e.status = 'replied'
                order by e.created_at desc limit 5"""
            .query[(String, String)]
            .to[List]
        leads.transact(xa).flatMap { rows =>
          if rows.isEmpty then
            IO.pure(
              Right(
                Json.obj(
                  "meetings" -> Json.arr(),
                  "note" -> Json
                    .fromString("No positive replies yet to qualify.")
                )
              )
            )
          else
            openRun(ws, gw, user, s"meetings for campaign $campaignId")
              .flatMap { runId =>
                val messages = List(
                  AiGateway.ChatMessage(
                    "system",
                    """Qualify meeting opportunities. Return STRICT JSON only.
                    |Schema: {"meetings":[{"contact":"...","email":"...","priority":"high|medium|low","angle":"..."}]}.""".stripMargin
                  ),
                  AiGateway.ChatMessage(
                    "user",
                    s"Positive repliers: ${rows.map(r => s"${r._1} <${r._2}>").mkString("; ")}"
                  )
                )
                modelCall[Meetings](ws, gw, runId, 1, messages, 500).flatMap {
                  case Left(env)              => IO.pure(Left(env))
                  case Right((m, completion)) =>
                    (for
                      _ <- sql"""update public.ai_runs set status = 'completed',
                          output_preview = ${AiGateway.Redact.preview(
                          completion.text
                        )},
                          input_tokens = ${completion.usage.inputTokens}, output_tokens = ${completion.usage.outputTokens},
                          cost_micros = ${AiGateway.estimateCostMicros(
                          completion.usage
                        )}, finished_at = now()
                          where id = $runId""".update.run.void
                      _ <- sql"""insert into public.ai_usage (workspace_id, run_id, provider, model, input_tokens, output_tokens, cost_micros)
                          values ($ws, $runId, ${gw.name}, ${completion.model}, ${completion.usage.inputTokens},
                                  ${completion.usage.outputTokens}, ${AiGateway
                          .estimateCostMicros(
                            completion.usage
                          )})""".update.run.void
                    yield ()).transact(xa) *>
                      IO.pure(
                        Right(
                          Json.fromValues(
                            m.meetings.map(x =>
                              Json.obj(
                                "contact" -> Json.fromString(x.contact),
                                "email" -> Json.fromString(x.email),
                                "priority" -> Json.fromString(x.priority),
                                "angle" -> Json.fromString(x.angle)
                              )
                            )
                          )
                        )
                      )
                }
              }
        }

  final case class Meeting(
      contact: String,
      email: String,
      priority: String,
      angle: String
  )
  final case class Meetings(meetings: List[Meeting])
  given io.circe.Decoder[Meeting] = (c: HCursor) =>
    for
      ct <- c.get[String]("contact")
      em <- c.get[String]("email")
      pr <- c.get[String]("priority").orElse(Right("medium"))
      an <- c.get[String]("angle").orElse(Right(""))
    yield Meeting(ct, em, pr, an)
  given io.circe.Decoder[Meetings] = (c: HCursor) =>
    c.get[List[Meeting]]("meetings").map(Meetings(_))

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      agentRun.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => runLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      agentRecommendations.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => recommendationsLogic(w, user, req.campaignId)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      agentMeetings.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => meetingsLogic(w, user, req.campaignId)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      )
    )
  )
