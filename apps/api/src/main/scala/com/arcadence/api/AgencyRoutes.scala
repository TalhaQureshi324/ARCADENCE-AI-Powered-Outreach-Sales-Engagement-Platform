package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.util.UUID

/** Agency management (Phase 9.3/9.4): a parent organization manages client
  * workspaces via `organizations.parent_organization_id`. Agency owners/ admins
  * get consolidated, read-only reporting across their client workspaces; client
  * data isolation is preserved because agency access is limited to aggregates —
  * never contacts, inbox contents or credentials.
  */
final class AgencyRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO]
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  /** Verifies the caller owns/administers the PARENT organization and the child
    * workspace actually belongs to it. Returns Left(error) otherwise.
    */
  private def requireAgencyAccess(
      auth: Option[String],
      childWorkspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    auth match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(e) =>
            IO.pure(
              Left((SttpStatus.Unauthorized, Envelope(e.code, e.message)))
            )
          case Right(user) =>
            sql"""
              select p.id from public.organization p
              join public.organization c on c.parent_organization_id = p.id
              join public.workspace w on w.organization_id = c.id
              where w.id = $childWorkspaceId
            """.query[UUID].option.transact(xa).flatMap {
              case None =>
                IO.pure(Left(failed(ApiError.NotFound("client workspace"))))
              case Some(parentOrg) =>
                sql"""
                  select count(*) > 0 from public.workspace_member m
                  join public.workspace w on w.id = m.workspace_id
                  where m.workspace_id = $parentOrg and m.user_id = ${user.id}
                    and m.role in ('owner', 'admin')
                """.query[Boolean].unique.transact(xa).flatMap {
                  case true  => IO.pure(Right(()))
                  case false =>
                    IO.pure(Left(failed(ApiError.NotFound("client workspace"))))
                }
            }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private val listClients = endpoint.get
    .in("api" / "v1" / "agency" / path[UUID]("agencyWorkspaceId") / "clients")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val consolidatedReport = endpoint.get
    .in("api" / "v1" / "agency" / path[UUID]("agencyWorkspaceId") / "reporting")
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("from"))
    .in(query[Option[String]]("to"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  // -- logic ------------------------------------------------------------------------

  /** Verifies agencyWorkspaceId is a workspace of an agency (parent) org the
    * user administers, and returns the client workspace ids.
    */
  private def clientWorkspaces(
      workspaceId: UUID,
      user: AuthenticatedUser
  ): IO[Either[(SttpStatus, Envelope), List[UUID]]] =
    (for
      myOrg <-
        sql"select organization_id from public.workspace where id = $workspaceId"
          .query[UUID]
          .option
      isAdmin <- myOrg match
        case Some(orgId) =>
          sql"""
            select count(*) > 0 from public.workspace_member m
            join public.workspace w on w.id = m.workspace_id
            where w.organization_id = $orgId and m.user_id = ${user.id}
              and m.role in ('owner', 'admin')
          """.query[Boolean].unique
        case None => FC.pure(false)
      clients <-
        if !isAdmin then FC.raiseError(new NotAgencySignal)
        else
          myOrg match
            case Some(orgId) =>
              sql"""
                select w.id from public.workspace w
                join public.organization o on o.id = w.organization_id
                where o.parent_organization_id = $orgId
              """.query[UUID].to[List]
            case None => FC.pure(Nil)
    yield Right(clients): Either[(SttpStatus, Envelope), List[UUID]])
      .transact(xa)
      .handleError {
        case _: NotAgencySignal =>
          Left(failed(ApiError.NotFound("agency workspace")))
        case other => throw other
      }

  private def listClientsLogic(
      auth: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    auth match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(e) =>
            IO.pure(
              Left((SttpStatus.Unauthorized, Envelope(e.code, e.message)))
            )
          case Right(user) =>
            clientWorkspaces(workspaceId, user).flatMap {
              case Left(env)      => IO.pure(Left(env))
              case Right(clients) =>
                clients
                  .traverse { cid =>
                    (for
                      info <-
                        sql"""select name, slug from public.workspace where id = $cid"""
                          .query[(String, String)]
                          .unique
                      boxes <-
                        sql"select count(*) from public.email_accounts where workspace_id = $cid"
                          .query[Long]
                          .unique
                      camps <-
                        sql"select count(*) from public.campaign where workspace_id = $cid and status = 'active'"
                          .query[Long]
                          .unique
                      leads <-
                        sql"select count(*) from public.contact where workspace_id = $cid"
                          .query[Long]
                          .unique
                      replies <-
                        sql"""select count(*) from public.email_messages m
                            join public.email_threads t on t.id = m.thread_id
                            where t.workspace_id = $cid and m.direction = 'inbound'"""
                          .query[Long]
                          .unique
                    yield Json.obj(
                      "workspaceId" -> Json.fromString(cid.toString),
                      "name" -> Json.fromString(info._1),
                      "slug" -> Json.fromString(info._2),
                      "mailboxes" -> Json.fromLong(boxes),
                      "activeCampaigns" -> Json.fromLong(camps),
                      "leads" -> Json.fromLong(leads),
                      "replies" -> Json.fromLong(replies)
                    )).transact(xa)
                  }
                  .map(rows => Right(Json.fromValues(rows)))
            }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private def reportingLogic(
      auth: Option[String],
      workspaceId: UUID,
      from: Option[String],
      to: Option[String]
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    auth match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(e) =>
            IO.pure(
              Left((SttpStatus.Unauthorized, Envelope(e.code, e.message)))
            )
          case Right(user) =>
            clientWorkspaces(workspaceId, user).flatMap {
              case Left(env)      => IO.pure(Left(env))
              case Right(clients) =>
                val ids =
                  if clients.isEmpty then List(UUID.randomUUID()) else clients
                val inList =
                  ids.map(id => fr"$id").reduce((a, b) => a ++ fr", " ++ b)
                sql"""
                  select coalesce(sum(send_scheduled),0), coalesce(sum(accepted),0),
                         coalesce(sum(bounce_hard),0), coalesce(sum(replies),0),
                         coalesce(sum(meeting_booked),0)
                  from public.campaign_daily_stats
                  where workspace_id in ($inList)
                """
                  .query[(Int, Int, Int, Int, Int)]
                  .unique
                  .transact(xa)
                  .map { case (sched, acc, bh, rep, mtg) =>
                    Right(
                      Json.obj(
                        "clientWorkspaces" -> Json.fromInt(ids.size),
                        "emailsSubmitted" -> Json.fromInt(sched),
                        "accepted" -> Json.fromInt(acc),
                        "bounceHard" -> Json.fromInt(bh),
                        "replies" -> Json.fromInt(rep),
                        "meetings" -> Json.fromInt(mtg)
                      )
                    )
                  }
            }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private class NotAgencySignal
      extends RuntimeException("not an agency workspace")

  // -- assembly --------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      listClients.serverLogic((aw, auth) =>
        auth match
          case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
            authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
              case Left(e) =>
                IO.pure(
                  Left((SttpStatus.Unauthorized, Envelope(e.code, e.message)))
                )
              case Right(user) =>
                listClientsLogic(auth, aw)
            }
          case _ => IO.pure(Left(failed(ApiError.Unauthorized)))
      ),
      consolidatedReport.serverLogic((aw, auth, f, t) =>
        auth match
          case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
            authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
              case Left(e) =>
                IO.pure(
                  Left((SttpStatus.Unauthorized, Envelope(e.code, e.message)))
                )
              case Right(user) =>
                reportingLogic(auth, aw, f, t)
            }
          case _ => IO.pure(Left(failed(ApiError.Unauthorized)))
      )
    )
  )
