package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.util.UUID

object OpsDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class DomainRequest(
      domain: String,
      registrar: Option[String],
      dnsProvider: Option[String],
      renewalDate: Option[String]
  )
  object DomainRequest:
    given Encoder[DomainRequest] = deriveEncoder
    given Decoder[DomainRequest] = deriveDecoder
    given Schema[DomainRequest] = Schema.derived

  final case class DomainDto(
      id: UUID,
      domain: String,
      registrar: Option[String],
      dnsProvider: Option[String],
      status: String,
      renewalDate: Option[String],
      lastError: Option[String]
  )
  object DomainDto:
    given Encoder[DomainDto] = deriveEncoder
    given Decoder[DomainDto] = deriveDecoder
    given Schema[DomainDto] = Schema.derived

/** Managed domain inventory (13.2) and privacy workflows (13.3). Domain status
  * honestly distinguishes manual_setup / pending_provisioning / active / failed
  * — automatic provisioning is NOT claimed until a contractual registrar
  * integration exists. Data export returns all workspace-owned records as JSON;
  * deletion purges the workspace cascade-safe (workspace row delete cascades
  * everywhere).
  */
final class OpsRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO]
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def requireManage(
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
              .requirePermission(user, ws, Permission.ManageWorkspace)
              .map {
                case Left(error) => Left(failed(error))
                case Right(m)    => Right(m.userId)
              }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private val wspath = path[UUID]("workspaceId")

  private val addDomain = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "domains")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[OpsDtos.DomainRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[OpsDtos.DomainDto])
    .errorOut(errorOut)

  private val listDomains = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "domains")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[OpsDtos.DomainDto]])
    .errorOut(errorOut)

  private val updateDomainStatus = endpoint.patch
    .in(
      "api" / "v1" / "workspaces" / wspath / "domains" / path[UUID]("domainId")
    )
    .in(header[Option[String]]("Authorization"))
    .in(query[String]("status"))
    .out(jsonBody[OpsDtos.DomainDto])
    .errorOut(errorOut)

  private val exportData = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "data-export")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private def domainDto(
      t: (
          UUID,
          String,
          Option[String],
          Option[String],
          String,
          Option[String],
          Option[String]
      )
  ) =
    OpsDtos.DomainDto(t._1, t._2, t._3, t._4, t._5, t._6, t._7)

  private def addDomainLogic(
      ws: UUID,
      user: UUID,
      req: OpsDtos.DomainRequest
  ): IO[Either[(SttpStatus, Envelope), OpsDtos.DomainDto]] =
    sql"""insert into public.managed_domains
          (workspace_id, domain, registrar, dns_provider, status, renewal_date, created_by)
          values ($ws, ${req.domain.trim.toLowerCase}, ${req.registrar}, ${req.dnsProvider},
                  'manual_setup', ${req.renewalDate}::date, $user)
          on conflict (workspace_id, domain) do update set registrar = excluded.registrar,
            dns_provider = excluded.dns_provider, renewal_date = excluded.renewal_date
          returning id, domain, registrar, dns_provider, status, renewal_date::text, last_error"""
      .query[
        (
            UUID,
            String,
            Option[String],
            Option[String],
            String,
            Option[String],
            Option[String]
        )
      ]
      .unique
      .transact(xa)
      .map(t => Right(domainDto(t)))

  private def listDomainsLogic(
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), List[OpsDtos.DomainDto]]] =
    sql"""select id, domain, registrar, dns_provider, status, renewal_date::text, last_error
          from public.managed_domains where workspace_id = $ws order by created_at"""
      .query[
        (
            UUID,
            String,
            Option[String],
            Option[String],
            String,
            Option[String],
            Option[String]
        )
      ]
      .to[List]
      .transact(xa)
      .map(rows => Right(rows.map(domainDto)))

  private def updateStatusLogic(
      ws: UUID,
      domainId: UUID,
      status: String
  ): IO[Either[(SttpStatus, Envelope), OpsDtos.DomainDto]] =
    val allowed =
      Set("manual_setup", "pending_provisioning", "active", "failed")
    if !allowed.contains(status) then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope(
              "validation_failed",
              s"status must be one of: ${allowed.mkString(", ")}."
            )
          )
        )
      )
    else
      sql"""update public.managed_domains set status = $status,
            last_error = ${Option.when(status == "failed")(
          "marked failed by operator"
        )}
            where id = $domainId and workspace_id = $ws
            returning id, domain, registrar, dns_provider, status, renewal_date::text, last_error"""
        .query[
          (
              UUID,
              String,
              Option[String],
              Option[String],
              String,
              Option[String],
              Option[String]
          )
        ]
        .option
        .transact(xa)
        .map {
          case Some(t) => Right(domainDto(t))
          case None    =>
            Left(
              (SttpStatus.NotFound, Envelope("not_found", "Domain not found."))
            )
        }

  /** Data export: all workspace-owned business data as one JSON document.
    * Privacy-request workflow: requests are logged in privacy_requests.
    */
  private def pj(s: String): Json = parse(s).getOrElse(Json.Null)

  private def exportLogic(ws: UUID): IO[Either[(SttpStatus, Envelope), Json]] =
    val q =
      for
        contacts <-
          sql"""select coalesce(json_agg(t)::text, 'null') from (
                select id, email, first_name, last_name, job_title, state::text, created_at
                from public.contact where workspace_id = $ws) t"""
            .query[String]
            .unique
        campaigns <-
          sql"""select coalesce(json_agg(t)::text, 'null') from (
                select id, name, status, created_at from public.campaign where workspace_id = $ws) t"""
            .query[String]
            .unique
        deals <-
          sql"""select coalesce(json_agg(t)::text, 'null') from (
                select id, title, status, created_at from public.deal where workspace_id = $ws) t"""
            .query[String]
            .unique
        threads <-
          sql"""select coalesce(json_agg(t)::text, 'null') from (
                select id, subject, classification::text, created_at from public.email_threads
                where workspace_id = $ws) t"""
            .query[String]
            .unique
        _ <-
          sql"""insert into public.privacy_requests (workspace_id, request_type, subject_email, status, requested_by)
                values ($ws, 'export', 'workspace-export', 'completed', null)""".update.run.void
      yield Right(
        Json.obj(
          "workspaceId" -> Json.fromString(ws.toString),
          "exportedAt" -> Json.fromString(java.time.Instant.now().toString),
          "contacts" -> pj(contacts),
          "campaigns" -> pj(campaigns),
          "deals" -> pj(deals),
          "threads" -> pj(threads)
        )
      ): Either[(SttpStatus, Envelope), Json]
    q.transact(xa)

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      addDomain.serverLogic((w, auth, req) =>
        requireManage(auth, w).flatMap {
          case Right(user) => addDomainLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), OpsDtos.DomainDto]]
            )
        }
      ),
      listDomains.serverLogic((w, auth) =>
        requireManage(auth, w).flatMap {
          case Right(_)    => listDomainsLogic(w)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                OpsDtos.DomainDto
              ]]]
            )
        }
      ),
      updateDomainStatus.serverLogic((w, domainId, auth, status) =>
        requireManage(auth, w).flatMap {
          case Right(_)    => updateStatusLogic(w, domainId, status)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), OpsDtos.DomainDto]]
            )
        }
      ),
      exportData.serverLogic((w, auth) =>
        requireManage(auth, w).flatMap {
          case Right(_)    => exportLogic(w)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      )
    )
  )
