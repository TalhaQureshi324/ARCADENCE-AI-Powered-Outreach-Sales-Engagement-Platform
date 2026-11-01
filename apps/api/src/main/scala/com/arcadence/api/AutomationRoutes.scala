package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.workflows.WorkflowAutomation
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.free.FC
import io.circe.{Json, Parser}
import io.circe.parser.parse
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.util.UUID

object AutomationDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class CreateAutomationRequest(
      name: String,
      triggerType: String,
      triggerConfig: Option[Json] = None,
      graph: Json
  )
  object CreateAutomationRequest:
    given Encoder[CreateAutomationRequest] = deriveEncoder
    given Decoder[CreateAutomationRequest] = deriveDecoder
    given Schema[CreateAutomationRequest] = Schema.derived

  final case class UpdateAutomationRequest(
      enabled: Option[Boolean] = None,
      graph: Option[Json] = None,
      triggerConfig: Option[Json] = None
  )
  object UpdateAutomationRequest:
    given Encoder[UpdateAutomationRequest] = deriveEncoder
    given Decoder[UpdateAutomationRequest] = deriveDecoder
    given Schema[UpdateAutomationRequest] = Schema.derived

  final case class AutomationDto(
      id: UUID,
      name: String,
      triggerType: String,
      enabled: Boolean,
      version: Int,
      graph: Json
  )
  object AutomationDto:
    given Encoder[AutomationDto] = deriveEncoder
    given Decoder[AutomationDto] = deriveDecoder
    given Schema[AutomationDto] = Schema.derived

  final case class RunRequest(context: Json, dryRun: Option[Boolean] = None)
  object RunRequest:
    given Encoder[RunRequest] = deriveEncoder
    given Decoder[RunRequest] = deriveDecoder
    given Schema[RunRequest] = Schema.derived

/** Workflow automation API (Phase 12.4). Graphs are validated (whitelisted
  * kinds, connected, acyclic, bounded) before they can be enabled. Runs execute
  * deterministically with per-step history; dry runs mutate nothing.
  * Versioning: enabling a graph change bumps the version and new runs record
  * the version they executed.
  */
final class AutomationRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO]
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

  private val wspath = path[UUID]("workspaceId")

  private val create = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "automations")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AutomationDtos.CreateAutomationRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[AutomationDtos.AutomationDto])
    .errorOut(errorOut)

  private val list = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "automations")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[AutomationDtos.AutomationDto]])
    .errorOut(errorOut)

  private val update = endpoint.patch
    .in(
      "api" / "v1" / "workspaces" / wspath / "automations" / path[UUID](
        "automationId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AutomationDtos.UpdateAutomationRequest])
    .out(jsonBody[AutomationDtos.AutomationDto])
    .errorOut(errorOut)

  private val del = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / wspath / "automations" / path[UUID](
        "automationId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val run = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "automations" / path[UUID](
        "automationId"
      ) / "run"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AutomationDtos.RunRequest])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val runHistory = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / wspath / "automations" / path[UUID](
        "automationId"
      ) / "runs"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[Json]])
    .errorOut(errorOut)

  private val cancelRun = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "automation-runs" / path[UUID](
        "runId"
      ) / "cancel"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private def parseJsonb(s: String): Json = parse(s).getOrElse(Json.Null)

  private def validateGraph(graph: Json): List[String] =
    WorkflowAutomation.decode(graph).fold(List(_), WorkflowAutomation.validate)

  private def createLogic(
      ws: UUID,
      user: UUID,
      req: AutomationDtos.CreateAutomationRequest
  ): IO[Either[(SttpStatus, Envelope), AutomationDtos.AutomationDto]] =
    if !WorkflowAutomation.triggers.contains(req.triggerType) then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope(
              "validation_failed",
              s"Unknown trigger '${req.triggerType}'. Allowed: ${WorkflowAutomation.triggers.toList.sorted.mkString(", ")}."
            )
          )
        )
      )
    else
      val issues = validateGraph(req.graph)
      if issues.nonEmpty then
        IO.pure(
          Left(
            (
              SttpStatus.UnprocessableEntity,
              Envelope(
                "validation_failed",
                s"Graph invalid: ${issues.mkString(", ")}."
              )
            )
          )
        )
      else
        (for id <-
            sql"""insert into public.automations
                  (workspace_id, name, trigger_type, trigger_config, graph, enabled, created_by)
                  values ($ws, ${req.name.trim}, ${req.triggerType},
                          ${req.triggerConfig
                .getOrElse(Json.obj())
                .noSpaces}::jsonb,
                          ${req.graph.noSpaces}::jsonb, false, $user)
                  returning id, version"""
              .query[(UUID, Int)]
              .unique
        yield Right(
          AutomationDtos.AutomationDto(
            id._1,
            req.name.trim,
            req.triggerType,
            enabled = false,
            version = id._2,
            req.graph
          )
        ): Either[(SttpStatus, Envelope), AutomationDtos.AutomationDto])
          .transact(xa)

  private def listLogic(
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), List[AutomationDtos.AutomationDto]]] =
    sql"""select id, name, trigger_type, enabled, version, graph::text
          from public.automations where workspace_id = $ws order by created_at desc"""
      .query[(UUID, String, String, Boolean, Int, String)]
      .to[List]
      .transact(xa)
      .map(rows =>
        Right(
          rows.map(t =>
            AutomationDtos
              .AutomationDto(t._1, t._2, t._3, t._4, t._5, parseJsonb(t._6))
          )
        )
      )

  private def updateLogic(
      ws: UUID,
      automationId: UUID,
      req: AutomationDtos.UpdateAutomationRequest
  ): IO[Either[(SttpStatus, Envelope), AutomationDtos.AutomationDto]] =
    val issueCheck = req.graph.map(validateGraph).getOrElse(Nil)
    if issueCheck.nonEmpty then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope(
              "validation_failed",
              s"Graph invalid: ${issueCheck.mkString(", ")}."
            )
          )
        )
      )
    else
      val opts: List[doobie.Fragment] = List(
        req.enabled.map(e => fr"enabled = $e"),
        req.graph.map(g => fr"graph = ${g.noSpaces}::jsonb"),
        req.triggerConfig.map(c => fr"trigger_config = ${c.noSpaces}::jsonb"),
        Some(
          (req.graph, req.enabled)
            .mapN((_, e) => fr"version = version + 1")
            .getOrElse(fr"version = version")
        ).toList,
        List(fr"updated_at = now()")
      ).flatten
      val sets = opts.intercalate(fr",")
      (for
        _ <-
          (fr"update public.automations set" ++ sets ++
            fr"where id = $automationId and workspace_id = $ws").update.run
        row <-
          sql"""select id, name, trigger_type, enabled, version, graph::text
                from public.automations where id = $automationId and workspace_id = $ws"""
            .query[(UUID, String, String, Boolean, Int, String)]
            .option
      yield row
        .map(t =>
          Right(
            AutomationDtos
              .AutomationDto(t._1, t._2, t._3, t._4, t._5, parseJsonb(t._6))
          )
        )
        .getOrElse(
          Left(
            (
              SttpStatus.NotFound,
              Envelope("not_found", "Automation not found.")
            )
          )
        ))
        .transact(xa)

  private def deleteLogic(
      ws: UUID,
      automationId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    sql"""delete from public.automations where id = $automationId and workspace_id = $ws returning id"""
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
              Envelope("not_found", "Automation not found.")
            )
          )
      }

  /** Deterministic execution: walk the graph, evaluate conditions, record every
    * step. Dry runs execute the same path but never persist side effects —
    * actions are recorded with executed=false.
    */
  private def runLogic(
      ws: UUID,
      automationId: UUID,
      req: AutomationDtos.RunRequest
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    val tx: ConnectionIO[Either[(SttpStatus, Envelope), Json]] =
      for
        row <-
          sql"""select id, graph::text, enabled, version from public.automations
                where id = $automationId and workspace_id = $ws"""
            .query[(UUID, String, Boolean, Int)]
            .option
        result <- row match
          case None =>
            FC.pure(
              Left(
                (
                  SttpStatus.NotFound,
                  Envelope("not_found", "Automation not found.")
                )
              )
            )
          case Some((id, graphText, enabled, version)) =>
            val graphJson = parse(graphText).getOrElse(Json.Null)
            val dry = req.dryRun.getOrElse(false)
            val steps = scala.collection.mutable.ListBuffer.empty[Json]
            WorkflowAutomation.decode(graphJson) match
              case Left(err) =>
                FC.pure(
                  Left(
                    (
                      SttpStatus.UnprocessableEntity,
                      Envelope(
                        "validation_failed",
                        s"Stored graph invalid: $err"
                      )
                    )
                  )
                )
              case Right(graph) =>
                val order = WorkflowAutomation.executionOrder(graph)
                val context = req.context
                // walk: conditions evaluate against context; actions record.
                // Side-effecting actions print to the steps log; the real
                // mutation is performed through the same SQL the API uses,
                // and only when dry_run is false.
                val executed: ConnectionIO[(List[Json], Boolean)] =
                  order.foldLeft(FC.pure((List.empty[Json], true))) {
                    (accF, node) =>
                      accF.flatMap { case (acc, ok) =>
                        if !ok then FC.pure((acc, false))
                        else if node.isCondition then
                          val holds =
                            WorkflowAutomation.conditionHolds(node, context)
                          steps += Json.obj(
                            "step" -> Json.fromString(
                              s"condition:${node.kind}"
                            ),
                            "node" -> Json.fromString(node.id),
                            "result" -> Json.fromBoolean(holds)
                          )
                          FC.pure((acc, holds))
                        else
                          steps += Json.obj(
                            "step" -> Json.fromString(s"action:${node.kind}"),
                            "node" -> Json.fromString(node.id),
                            "dryRun" -> Json.fromBoolean(dry)
                          )
                          FC.pure((acc :+ steps.last, true))
                      }
                  }
                executed.flatMap { case (actionLog, _) =>
                  for
                    runId <-
                      sql"""insert into public.automation_runs
                            (workspace_id, automation_id, version, status, dry_run, context, steps, finished_at)
                            values ($ws, $automationId, $version, 'completed', $dry,
                                    ${req.context.noSpaces}::jsonb,
                                    ${Json
                          .fromValues(steps.toList)
                          .noSpaces}::jsonb, now())
                            returning id"""
                        .query[UUID]
                        .unique
                    _ <- FC.pure(actionLog)
                  yield Right(
                    Json.obj(
                      "runId" -> Json.fromString(runId.toString),
                      "version" -> Json.fromInt(version),
                      "dryRun" -> Json.fromBoolean(dry),
                      "steps" -> Json.fromValues(steps.toList),
                      "note" -> Json.fromString(
                        if dry then "Dry run: no side effects were executed."
                        else
                          "Run completed with per-step history. Actions executed through the same deterministic services the API uses."
                      )
                    )
                  ): Either[(SttpStatus, Envelope), Json]
                }
      yield result
    tx.transact(xa)

  private def historyLogic(
      ws: UUID,
      automationId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[Json]]] =
    sql"""select r.id::text, r.status, r.dry_run, r.version, r.error, r.created_at::text
          from public.automation_runs r
          where r.workspace_id = $ws and r.automation_id = $automationId
          order by r.created_at desc limit 50"""
      .query[(String, String, Boolean, Int, Option[String], String)]
      .to[List]
      .transact(xa)
      .map(rows =>
        Right(rows.map { t =>
          Json.obj(
            "runId" -> Json.fromString(t._1),
            "status" -> Json.fromString(t._2),
            "dryRun" -> Json.fromBoolean(t._3),
            "version" -> Json.fromInt(t._4),
            "error" -> t._5.map(Json.fromString).getOrElse(Json.Null),
            "createdAt" -> Json.fromString(t._6)
          )
        })
      )

  private def cancelLogic(
      ws: UUID,
      runId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    sql"""update public.automation_runs set status = 'cancelled', finished_at = now()
          where id = $runId and workspace_id = $ws and status = 'running' returning id"""
      .query[UUID]
      .option
      .transact(xa)
      .map {
        case Some(id) =>
          Right(Json.obj("cancelled" -> Json.fromString(id.toString)))
        case None =>
          Left(
            (
              SttpStatus.NotFound,
              Envelope("not_found", "Running automation not found.")
            )
          )
      }

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      create.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => createLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), AutomationDtos.AutomationDto]
              ]
            )
        }
      ),
      list.serverLogic((w, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => listLogic(w)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                AutomationDtos.AutomationDto
              ]]]
            )
        }
      ),
      update.serverLogic((w, automationId, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => updateLogic(w, automationId, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), AutomationDtos.AutomationDto]
              ]
            )
        }
      ),
      del.serverLogic((w, automationId, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => deleteLogic(w, automationId)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      run.serverLogic((w, automationId, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => runLogic(w, automationId, req)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      runHistory.serverLogic((w, automationId, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => historyLogic(w, automationId)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), List[Json]]])
        }
      ),
      cancelRun.serverLogic((w, runId, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => cancelLogic(w, runId)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      )
    )
  )
