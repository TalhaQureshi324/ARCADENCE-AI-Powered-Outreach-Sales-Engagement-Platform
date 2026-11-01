package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.email.gmail.GmailCredentials
import com.arcadence.core.email.microsoft.MicrosoftCredentials
import com.arcadence.core.integrations.Integrations
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

import java.util.UUID

object IntegrationDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class ConnectRequest(kind: String, config: Json)
  object ConnectRequest:
    given Encoder[ConnectRequest] = deriveEncoder
    given Decoder[ConnectRequest] = deriveDecoder
    given Schema[ConnectRequest] = Schema.derived

  final case class IntegrationDto(
      id: UUID,
      kind: String,
      status: String,
      config: Json,
      lastError: Option[String],
      lastSyncedAt: Option[String]
  )
  object IntegrationDto:
    given Encoder[IntegrationDto] = deriveEncoder
    given Decoder[IntegrationDto] = deriveDecoder
    given Schema[IntegrationDto] = Schema.derived

  final case class CalendarEventRequest(
      emailAccountId: UUID,
      summary: String,
      startIso: String,
      endIso: String,
      attendeeEmail: Option[String] = None,
      description: Option[String] = None
  )
  object CalendarEventRequest:
    given Encoder[CalendarEventRequest] = deriveEncoder
    given Decoder[CalendarEventRequest] = deriveDecoder
    given Schema[CalendarEventRequest] = Schema.derived

/** External integration registrations (Phase 12.2). Config secrets are stored
  * per workspace and never echoed in full (masked on read). Calendar event
  * creation resolves the OAuth access token through the Phase 2 provider
  * registry — integrations reuse connected mailbox identities rather than
  * storing duplicate credentials.
  */
final class IntegrationRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO],
    client: org.http4s.client.Client[IO],
    accessTokenFor: (UUID, UUID) => IO[Option[String]],
    googleBaseUrl: String = "https://www.googleapis.com",
    outlookBaseUrl: String = "https://graph.microsoft.com",
    hubspotBaseUrl: String = "https://api.hubapi.com"
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])
  private val slack = new Integrations.Slack(client)
  private val gcal =
    new Integrations.GoogleCalendar(client, baseUrl = googleBaseUrl)
  private val outlook =
    new Integrations.OutlookCalendar(client, baseUrl = outlookBaseUrl)
  private val hubspot =
    new Integrations.HubSpot(client, baseUrl = hubspotBaseUrl)

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

  private val connect = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "integrations")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[IntegrationDtos.ConnectRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[IntegrationDtos.IntegrationDto])
    .errorOut(errorOut)

  private val list = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "integrations")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[IntegrationDtos.IntegrationDto]])
    .errorOut(errorOut)

  private val disconnect = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / wspath / "integrations" / path[UUID](
        "integrationId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val testIntegration = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "integrations" / path[UUID](
        "integrationId"
      ) / "test"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val createCalendarEvent = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "integrations" / "calendar-events"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[IntegrationDtos.CalendarEventRequest])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private def parseJsonb(s: String): Json = parse(s).getOrElse(Json.Null)

  private def mask(
      integration: IntegrationDtos.IntegrationDto
  ): IntegrationDtos.IntegrationDto =
    val cfg = integration.config
    val masked = Json.obj(
      "webhookUrl" -> cfg.hcursor
        .get[String]("webhookUrl")
        .toOption
        .map(u => Json.fromString(u.take(24) + "…"))
        .getOrElse(Json.Null),
      "emailAccountId" -> cfg.hcursor
        .get[String]("emailAccountId")
        .toOption
        .map(Json.fromString)
        .getOrElse(Json.Null),
      "token" -> cfg.hcursor
        .get[String]("token")
        .toOption
        .map(_ => Json.fromString("[stored]"))
        .getOrElse(Json.Null)
    )
    integration.copy(config = masked)

  private def toDto(
      t: (UUID, String, String, String, Option[String], Option[String])
  ): IntegrationDtos.IntegrationDto =
    IntegrationDtos.IntegrationDto(
      t._1,
      t._2,
      t._3,
      parseJsonb(t._4),
      t._5,
      t._6
    )

  private def connectLogic(
      ws: UUID,
      user: UUID,
      req: IntegrationDtos.ConnectRequest
  ): IO[Either[(SttpStatus, Envelope), IntegrationDtos.IntegrationDto]] =
    if !Integrations.kinds.contains(req.kind) then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope(
              "validation_failed",
              s"Unknown integration kind '${req.kind}'."
            )
          )
        )
      )
    else
      (for id <-
          sql"""insert into public.integrations (workspace_id, kind, status, config, created_by)
                values ($ws, ${req.kind}, 'connected', ${req.config.noSpaces}::jsonb, $user)
                on conflict (workspace_id, kind) do update
                  set status = 'connected', config = excluded.config, last_error = null
                returning id, kind, status, config::text, last_error, last_synced_at::text"""
            .query[
              (UUID, String, String, String, Option[String], Option[String])
            ]
            .unique
      yield Right(mask(toDto(id))): Either[
        (SttpStatus, Envelope),
        IntegrationDtos.IntegrationDto
      ]).transact(xa)

  private def listLogic(
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), List[IntegrationDtos.IntegrationDto]]] =
    sql"""select id, kind, status, config::text, last_error, last_synced_at::text
          from public.integrations where workspace_id = $ws order by created_at"""
      .query[(UUID, String, String, String, Option[String], Option[String])]
      .to[List]
      .transact(xa)
      .map(rows => Right(rows.map(t => mask(toDto(t)))))

  private def disconnectLogic(
      ws: UUID,
      integrationId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    sql"""delete from public.integrations where id = $integrationId and workspace_id = $ws returning id"""
      .query[UUID]
      .option
      .transact(xa)
      .map {
        case Some(id) =>
          Right(Json.obj("disconnected" -> Json.fromString(id.toString)))
        case None =>
          Left(
            (
              SttpStatus.NotFound,
              Envelope("not_found", "Integration not found.")
            )
          )
      }

  private def testLogic(
      ws: UUID,
      integrationId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    sql"""select kind, config::text from public.integrations
          where id = $integrationId and workspace_id = $ws and status = 'connected'"""
      .query[(String, String)]
      .option
      .transact(xa)
      .flatMap {
        case None =>
          IO.pure(
            Left(
              (
                SttpStatus.NotFound,
                Envelope("not_found", "Connected integration not found.")
              )
            )
          )
        case Some(("slack", cfg)) =>
          val hook = parseJsonb(cfg).hcursor
            .get[String]("webhookUrl")
            .toOption
            .getOrElse("")
          slack.postMessage(hook, "Arcadence connection test").map {
            case Right(_) => Right(Json.obj("ok" -> Json.fromBoolean(true)))
            case Left(e)  =>
              Right(
                Json.obj(
                  "ok" -> Json.fromBoolean(false),
                  "error" -> Json.fromString(e)
                )
              )
          }
        case Some((kind, cfg)) =>
          IO.pure(
            Right(
              Json.obj(
                "ok" -> Json.fromBoolean(true),
                "note" -> Json.fromString(
                  s"$kind is configured; live calls run per event (calendar events, CRM pushes)."
                )
              )
            )
          )
      }

  private def calendarEventLogic(
      ws: UUID,
      req: IntegrationDtos.CalendarEventRequest
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    for
      account <-
        sql"""select provider::text from public.email_accounts
              where id = ${req.emailAccountId} and workspace_id = $ws"""
          .query[String]
          .option
          .transact(xa)
      tokenOpt <- account match
        case None    => IO.pure(None)
        case Some(p) =>
          accessTokenFor(ws, req.emailAccountId).map(_.filter(_.nonEmpty))
      result <- (account, tokenOpt) match
        case (Some("gmail"), Some(token)) =>
          gcal.createEvent(
            token,
            req.summary,
            req.startIso,
            req.endIso,
            req.attendeeEmail,
            req.description
          )
        case (Some("microsoft"), Some(token)) =>
          outlook.createEvent(
            token,
            req.summary,
            req.startIso,
            req.endIso,
            req.attendeeEmail,
            req.description
          )
        case (Some(_), None) =>
          IO.pure(
            Left(
              "The connected account has no valid access token — reconnect it."
            )
          )
        case _ =>
          IO.pure(Left("Connect a Google or Microsoft account first."))
    yield result.fold(
      e => Left((SttpStatus.BadGateway, Envelope("integration_error", e))),
      ref =>
        Right(
          Json.obj(
            "provider" -> Json.fromString(ref.provider),
            "externalId" -> Json.fromString(ref.externalId),
            "note" -> Json.fromString(
              "New event created. Existing external events are never modified without an explicit external id."
            )
          )
        )
    )

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      connect.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => connectLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), IntegrationDtos.IntegrationDto]
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
                IntegrationDtos.IntegrationDto
              ]]]
            )
        }
      ),
      disconnect.serverLogic((w, integrationId, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => disconnectLogic(w, integrationId)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      testIntegration.serverLogic((w, integrationId, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => testLogic(w, integrationId)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      createCalendarEvent.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => calendarEventLogic(w, req)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      )
    )
  )
