package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

object CrmDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class StageDto(id: UUID, name: String, position: Int)
  object StageDto:
    given Encoder[StageDto] = deriveEncoder
    given Decoder[StageDto] = deriveDecoder
    given Schema[StageDto] = Schema.derived

  final case class PipelineDto(id: UUID, name: String, stages: List[StageDto])
  object PipelineDto:
    given Encoder[PipelineDto] = deriveEncoder
    given Decoder[PipelineDto] = deriveDecoder
    given Schema[PipelineDto] = Schema.derived

  final case class CreatePipelineRequest(name: String, stages: List[String])
  object CreatePipelineRequest:
    given Encoder[CreatePipelineRequest] = deriveEncoder
    given Decoder[CreatePipelineRequest] = deriveDecoder
    given Schema[CreatePipelineRequest] = Schema.derived

  final case class DealDto(
      id: UUID,
      pipelineId: UUID,
      stageId: UUID,
      stageName: String,
      title: String,
      valueCents: Long,
      currency: String,
      status: String,
      expectedCloseDate: Option[LocalDate],
      createdAt: OffsetDateTime
  )
  object DealDto:
    given Encoder[DealDto] = deriveEncoder
    given Decoder[DealDto] = deriveDecoder
    given Schema[DealDto] = Schema.derived

  final case class CreateDealRequest(
      pipelineId: UUID,
      stageId: UUID,
      title: String,
      valueCents: Long = 0,
      currency: String = "USD",
      contactId: Option[UUID] = None,
      sourceEnrollmentId: Option[UUID] = None,
      expectedCloseDate: Option[LocalDate] = None
  )
  object CreateDealRequest:
    given Encoder[CreateDealRequest] = deriveEncoder
    given Decoder[CreateDealRequest] = deriveDecoder
    given Schema[CreateDealRequest] = Schema.derived

  final case class MoveDealRequest(stageId: UUID)
  object MoveDealRequest:
    given Encoder[MoveDealRequest] = deriveEncoder
    given Decoder[MoveDealRequest] = deriveDecoder
    given Schema[MoveDealRequest] = Schema.derived

  final case class StatusRequest(status: String)
  object StatusRequest:
    given Encoder[StatusRequest] = deriveEncoder
    given Decoder[StatusRequest] = deriveDecoder
    given Schema[StatusRequest] = Schema.derived

  final case class ActivityDto(
      id: UUID,
      dealId: Option[UUID],
      `type`: String,
      body: String,
      createdAt: OffsetDateTime
  )
  object ActivityDto:
    given Encoder[ActivityDto] = deriveEncoder
    given Decoder[ActivityDto] = deriveDecoder
    given Schema[ActivityDto] = Schema.derived

final class CrmRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO]
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private val dealRead: Read[CrmDtos.DealDto] =
    Read[
      (
          UUID,
          UUID,
          UUID,
          String,
          String,
          Long,
          String,
          String,
          Option[LocalDate],
          OffsetDateTime
      )
    ].map { t =>
      CrmDtos.DealDto(
        t._1,
        t._2,
        t._3,
        t._4,
        t._5,
        t._6,
        t._7,
        t._8,
        t._9,
        t._10
      )
    }

  private val dealSelect = fr"""
    select d.id, d.pipeline_id, d.stage_id, s.name, d.title, d.value_cents,
           d.currency, d.status::text, d.expected_close_date, d.created_at
    from public.deal d
    join public.pipeline_stage s on s.id = d.stage_id
  """

  private def audit(
      ws: UUID,
      dealId: UUID,
      actor: UUID,
      typ: String,
      body: String
  ): ConnectionIO[Unit] =
    sql"""insert into public.crm_activity (workspace_id, deal_id, type, body, actor_id)
          values ($ws, $dealId, $typ, $body, $actor)""".update.run.void

  // -- endpoints ------------------------------------------------------------------

  private val wspath = path[UUID]("workspaceId")

  private val listPipelines = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "crm" / "pipelines")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[CrmDtos.PipelineDto]])
    .errorOut(errorOut)

  private val createPipeline = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "crm" / "pipelines")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CrmDtos.CreatePipelineRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[CrmDtos.PipelineDto])
    .errorOut(errorOut)

  private val listDeals = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "crm" / "deals")
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[UUID]]("pipelineId"))
    .out(jsonBody[List[CrmDtos.DealDto]])
    .errorOut(errorOut)

  private val createDeal = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "crm" / "deals")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CrmDtos.CreateDealRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[CrmDtos.DealDto])
    .errorOut(errorOut)

  private val moveDeal = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "crm" / "deals" / path[UUID](
        "dealId"
      ) / "move"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CrmDtos.MoveDealRequest])
    .out(jsonBody[CrmDtos.DealDto])
    .errorOut(errorOut)

  private val setStatus = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "crm" / "deals" / path[UUID](
        "dealId"
      ) / "status"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CrmDtos.StatusRequest])
    .out(jsonBody[CrmDtos.DealDto])
    .errorOut(errorOut)

  private val timeline = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / wspath / "crm" / "deals" / path[UUID](
        "dealId"
      ) / "timeline"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[CrmDtos.ActivityDto]])
    .errorOut(errorOut)

  private val dealFromReply = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "crm" / "deals" / "from-reply" / path[
        UUID
      ]("enrollmentId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[CrmDtos.DealDto])
    .errorOut(errorOut)

  // -- logic ------------------------------------------------------------------------

  private def listPipelinesLogic(ws: UUID): IO[List[CrmDtos.PipelineDto]] =
    (for
      pipes <-
        sql"select id, name from public.pipeline where workspace_id = $ws order by created_at"
          .query[(UUID, String)]
          .to[List]
      stages <-
        sql"""select s.pipeline_id, s.id, s.name, s.position from public.pipeline_stage s
              join public.pipeline p on p.id = s.pipeline_id
              where p.workspace_id = $ws order by s.position"""
          .query[(UUID, UUID, String, Int)]
          .to[List]
    yield stages
      .groupBy(_._1)
      .map { case (pid, ss) =>
        val p = pipes.find(_._1 == pid)
        CrmDtos.PipelineDto(
          pid,
          p.map(_._2).getOrElse(""),
          ss.sortBy(_._4).map(s => CrmDtos.StageDto(s._2, s._3, s._4))
        )
      }
      .toList).transact(xa)

  private def createPipelineLogic(
      ws: UUID,
      req: CrmDtos.CreatePipelineRequest
  ): IO[CrmDtos.PipelineDto] =
    (for
      pid <-
        sql"""insert into public.pipeline (workspace_id, name) values ($ws, ${req.name.trim})
              returning id""".query[UUID].unique
      stageIds <- req.stages.filter(_.trim.nonEmpty).zipWithIndex.traverse {
        case (name, i) =>
          sql"""insert into public.pipeline_stage (pipeline_id, name, position) values ($pid, ${name.trim}, ${i + 1}) returning id, name, position"""
            .query[(UUID, String, Int)]
            .unique
      }
    yield CrmDtos.PipelineDto(
      pid,
      req.name.trim,
      stageIds.map(s => CrmDtos.StageDto(s._1, s._2, s._3))
    )).transact(xa)

  private def listDealsLogic(
      ws: UUID,
      pipelineId: Option[UUID]
  ): IO[List[CrmDtos.DealDto]] =
    val base = dealSelect ++ fr"where d.workspace_id = $ws"
    val q = pipelineId.fold(base)(pid => base ++ fr"and d.pipeline_id = $pid")
    (q ++ fr"order by d.created_at desc")
      .query[CrmDtos.DealDto](using dealRead)
      .to[List]
      .transact(xa)

  private def createDealLogic(
      ws: UUID,
      actor: UUID,
      req: CrmDtos.CreateDealRequest
  ): IO[Either[Envelope, CrmDtos.DealDto]] =
    (for
      valid <-
        sql"select count(*) > 0 from public.pipeline_stage where id = ${req.stageId} and pipeline_id = ${req.pipelineId}"
          .query[Boolean]
          .unique
      _ <-
        if !valid then
          FC.raiseError(
            new InvalidSignal("Stage does not belong to the pipeline.")
          )
        else FC.unit
      dedupeOk <- req.sourceEnrollmentId match
        case Some(enr) =>
          sql"""select count(*) = 0 from public.deal
                where source_enrollment_id = $enr and status = 'open'"""
            .query[Boolean]
            .unique
        case None => FC.pure(true)
      _ <- if !dedupeOk then FC.raiseError(new DuplicateSignal) else FC.unit
      d <-
        sql"""
          insert into public.deal (workspace_id, pipeline_id, stage_id, title, value_cents, currency,
            owner_id, source, source_enrollment_id, expected_close_date)
          values ($ws, ${req.pipelineId}, ${req.stageId}, ${req.title.trim}, ${req.valueCents},
                  ${req.currency.toUpperCase}, $actor, 'manual', ${req.sourceEnrollmentId}, ${req.expectedCloseDate})
          returning id, pipeline_id, stage_id
        """.query[(UUID, UUID, UUID)].unique
      _ <- req.contactId.traverse(cid =>
        sql"insert into public.deal_contact (deal_id, contact_id) values (${d._1}, $cid) on conflict do nothing".update.run.void
      )
      _ <- audit(ws, d._1, actor, "status_change", "deal created")
      full <- (dealSelect ++ fr"where d.id = ${d._1}")
        .query[CrmDtos.DealDto](using dealRead)
        .unique
    yield Right(full): Either[Envelope, CrmDtos.DealDto])
      .transact(xa)
      .handleError {
        case _: DuplicateSignal =>
          Left(
            Envelope(
              "conflict",
              "An open deal already exists for this enrollment."
            )
          )
        case _: InvalidSignal =>
          Left(
            Envelope(
              "validation_failed",
              "Stage does not belong to the pipeline."
            )
          )
        case other => throw other
      }

  private def moveDealLogic(
      ws: UUID,
      dealId: UUID,
      actor: UUID,
      req: CrmDtos.MoveDealRequest
  ): IO[Either[Envelope, CrmDtos.DealDto]] =
    (for
      ok <-
        sql"""select count(*) > 0 from public.pipeline_stage s
              join public.deal d on d.pipeline_id = s.pipeline_id
              where s.id = ${req.stageId} and d.id = $dealId"""
          .query[Boolean]
          .unique
      _ <-
        if !ok then
          FC.raiseError(
            new InvalidSignal("Target stage is in a different pipeline.")
          )
        else FC.unit
      _ <-
        sql"""update public.deal set stage_id = ${req.stageId}
              where workspace_id = $ws and id = $dealId and status = 'open'""".update.run
      _ <- audit(
        ws,
        dealId,
        actor,
        "stage_change",
        s"moved to stage ${req.stageId}"
      )
      full <- (dealSelect ++ fr"where d.id = $dealId")
        .query[CrmDtos.DealDto](using dealRead)
        .unique
    yield Right(full): Either[Envelope, CrmDtos.DealDto])
      .transact(xa)
      .handleError {
        case _: InvalidSignal =>
          Left(
            Envelope(
              "validation_failed",
              "Target stage is in a different pipeline."
            )
          )
        case other => throw other
      }

  private def setStatusLogic(
      ws: UUID,
      dealId: UUID,
      actor: UUID,
      status: String
  ): IO[Either[Envelope, CrmDtos.DealDto]] =
    val allowed = Set("open", "won", "lost")
    if !allowed.contains(status) then
      IO.pure(
        Left(Envelope("validation_failed", "status must be open, won or lost."))
      )
    else
      (for
        _ <-
          sql"""update public.deal set status = ${status}::deal_status,
                  finished_at = (case when ${status}::deal_status in ('won','lost') then now() else finished_at end)
                where workspace_id = $ws and id = $dealId""".update.run
        _ <- audit(ws, dealId, actor, "status_change", s"status set to $status")
        full <- (dealSelect ++ fr"where d.id = $dealId")
          .query[CrmDtos.DealDto](using dealRead)
          .unique
      yield Right(full): Either[Envelope, CrmDtos.DealDto]).transact(xa)

  private def timelineLogic(
      ws: UUID,
      dealId: UUID
  ): IO[List[CrmDtos.ActivityDto]] =
    sql"""select id, deal_id, type, body, created_at from public.crm_activity
          where workspace_id = $ws and deal_id = $dealId order by created_at desc limit 100"""
      .query[(UUID, Option[UUID], String, String, OffsetDateTime)]
      .to[List]
      .transact(xa)
      .map(_.map(t => CrmDtos.ActivityDto(t._1, t._2, t._3, t._4, t._5)))

  /** Auto-deal on positive replies (Phase 9.2): deduped by the partial unique
    * index on open deals per enrollment; creates an activity trail entry.
    */
  private def fromReplyLogic(
      ws: UUID,
      enrollmentId: UUID,
      actor: UUID
  ): IO[Either[Envelope, CrmDtos.DealDto]] =
    (for
      row <-
        sql"""select e.campaign_id, e.contact_id, c.email from public.campaign_enrollment e
              join public.contact c on c.id = e.contact_id
              where e.id = $enrollmentId"""
          .query[(UUID, Option[UUID], String)]
          .option
      _ <- if row.isEmpty then FC.raiseError(new NotFoundSignal) else FC.unit
      (_, _contactId, email) = row.get
      pipeline <-
        sql"""select p.id, (select s.id from public.pipeline_stage s where s.pipeline_id = p.id order by position limit 1)
              from public.pipeline p where p.workspace_id = $ws order by p.created_at limit 1"""
          .query[(UUID, UUID)]
          .option
      result <- pipeline match
        case None =>
          FC.raiseError(
            new NotFoundSignal("No pipeline configured for this workspace.")
          )
        case Some((pid, sid)) =>
          for
            existing <-
              sql"""select count(*) = 0 from public.deal
                    where source_enrollment_id = $enrollmentId and status = 'open'"""
                .query[Boolean]
                .unique
            _ <-
              if !existing then FC.raiseError(new DuplicateSignal) else FC.unit
            insertedId <-
              sql"""insert into public.deal (workspace_id, pipeline_id, stage_id, title, source,
                    source_enrollment_id, owner_id)
                    values ($ws, $pid, $sid, ${s"Reply: $email"}, 'reply', $enrollmentId, $actor)
                    returning id""".query[UUID].unique
            _ <- audit(
              ws,
              insertedId,
              actor,
              "reply",
              s"auto-created from positive reply by $email"
            )
            full <- (dealSelect ++ fr"where d.id = $insertedId")
              .query[CrmDtos.DealDto](using dealRead)
              .unique
          yield Right(full): Either[Envelope, CrmDtos.DealDto]
    yield result).transact(xa).handleError {
      case _: NotFoundSignal =>
        Left(Envelope("not_found", "Enrollment or pipeline not found."))
      case _: DuplicateSignal =>
        Left(
          Envelope(
            "conflict",
            "An open deal already exists for this enrollment."
          )
        )
      case other => throw other
    }

  private class NotFoundSignal(msg: String) extends RuntimeException(msg):
    def this() = this("not found")
  private class DuplicateSignal extends RuntimeException("duplicate")
  private class InvalidSignal(msg: String) extends RuntimeException(msg)

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
            authorizer
              .requirePermission(user, ws, Permission.ManageCampaigns)
              .map {
                case Left(error) => Left(failed(error))
                case Right(m)    => Right(m.userId)
              }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private def envStatus(env: Envelope): SttpStatus =
    env.code match
      case "not_found"         => SttpStatus.NotFound
      case "conflict"          => SttpStatus.Conflict
      case "validation_failed" => SttpStatus.UnprocessableEntity
      case _                   => SttpStatus.InternalServerError

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      listPipelines.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => listPipelinesLogic(w).map(Right(_))
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                CrmDtos.PipelineDto
              ]]]
            )
        }
      ),
      createPipeline.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => createPipelineLogic(w, req).map(Right(_))
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), CrmDtos.PipelineDto]
              ]
            )
        }
      ),
      listDeals.serverLogic((w, auth, pid) =>
        requireView(auth, w).flatMap {
          case Right(_)    => listDealsLogic(w, pid).map(Right(_))
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                CrmDtos.DealDto
              ]]]
            )
        }
      ),
      createDeal.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(actor) =>
            createDealLogic(w, actor, req).map {
              case Right(d)  => Right(d)
              case Left(env) => Left((envStatus(env), env))
            }
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), CrmDtos.DealDto]]
            )
        }
      ),
      moveDeal.serverLogic((w, d, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(actor) =>
            moveDealLogic(w, d, actor, req).map {
              case Right(x)  => Right(x)
              case Left(env) => Left((envStatus(env), env))
            }
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), CrmDtos.DealDto]]
            )
        }
      ),
      setStatus.serverLogic((w, d, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(actor) =>
            setStatusLogic(w, d, actor, req.status).map {
              case Right(x)  => Right(x)
              case Left(env) => Left((envStatus(env), env))
            }
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), CrmDtos.DealDto]]
            )
        }
      ),
      timeline.serverLogic((w, d, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => timelineLogic(w, d).map(Right(_))
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                CrmDtos.ActivityDto
              ]]]
            )
        }
      ),
      dealFromReply.serverLogic((w, e, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(actor) =>
            fromReplyLogic(w, e, actor).map {
              case Right(x)  => Right(x)
              case Left(env) => Left((envStatus(env), env))
            }
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), CrmDtos.DealDto]]
            )
        }
      )
    )
  )
