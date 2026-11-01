package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.channels.Channels
import com.arcadence.core.channels.Channels.SmsAdapter
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

import java.util.UUID

object ChannelDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class ConsentRequest(consent: String)
  object ConsentRequest:
    given Encoder[ConsentRequest] = deriveEncoder
    given Decoder[ConsentRequest] = deriveDecoder
    given Schema[ConsentRequest] = Schema.derived

  final case class ConsentDto(
      contactId: UUID,
      smsConsent: String,
      callConsent: String,
      smsConsentAt: Option[String],
      callConsentAt: Option[String]
  )
  object ConsentDto:
    given Encoder[ConsentDto] = deriveEncoder
    given Decoder[ConsentDto] = deriveDecoder
    given Schema[ConsentDto] = Schema.derived

  final case class SmsSendRequest(
      contactId: UUID,
      fromNumber: String,
      text: String
  )
  object SmsSendRequest:
    given Encoder[SmsSendRequest] = deriveEncoder
    given Decoder[SmsSendRequest] = deriveDecoder
    given Schema[SmsSendRequest] = Schema.derived

  final case class InteractionRequest(
      contactId: UUID,
      channel: String,
      direction: String,
      summary: String,
      outcome: Option[String] = None
  )
  object InteractionRequest:
    given Encoder[InteractionRequest] = deriveEncoder
    given Decoder[InteractionRequest] = deriveDecoder
    given Schema[InteractionRequest] = Schema.derived

  final case class InteractionDto(
      id: UUID,
      contactId: UUID,
      channel: String,
      direction: String,
      summary: String,
      outcome: Option[String],
      occurredAt: String
  )
  object InteractionDto:
    given Encoder[InteractionDto] = deriveEncoder
    given Decoder[InteractionDto] = deriveDecoder
    given Schema[InteractionDto] = Schema.derived

/** Multichannel API (Phase 12.5). Channel consent is separate from email
  * consent and checked before any send. SMS adapters are optional — email
  * functionality is fully independent. No scraping, impersonation, or automated
  * activity contrary to provider terms.
  */
final class ChannelRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO],
    smsAdapter: Option[Channels.SmsAdapter]
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

  private val setConsent = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "contacts" / path[UUID](
        "contactId"
      ) / "consent" / path[String]("channel")
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[ChannelDtos.ConsentRequest])
    .out(jsonBody[ChannelDtos.ConsentDto])
    .errorOut(errorOut)

  private val getConsent = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / wspath / "contacts" / path[UUID](
        "contactId"
      ) / "consent"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[ChannelDtos.ConsentDto])
    .errorOut(errorOut)

  private val sendSms = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "channels" / "sms")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[ChannelDtos.SmsSendRequest])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val logInteraction = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "channel-interactions")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[ChannelDtos.InteractionRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[ChannelDtos.InteractionDto])
    .errorOut(errorOut)

  private val listInteractions = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / wspath / "contacts" / path[UUID](
        "contactId"
      ) / "interactions"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[ChannelDtos.InteractionDto]])
    .errorOut(errorOut)

  private def consentDto(
      t: (UUID, String, String, Option[String], Option[String])
  ): ChannelDtos.ConsentDto =
    ChannelDtos.ConsentDto(t._1, t._2, t._3, t._4, t._5)

  private def setConsentLogic(
      ws: UUID,
      contactId: UUID,
      channel: String,
      req: ChannelDtos.ConsentRequest
  ): IO[Either[(SttpStatus, Envelope), ChannelDtos.ConsentDto]] =
    if req.consent != "opted_in" && req.consent != "opted_out" then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope(
              "validation_failed",
              "Consent must be opted_in or opted_out."
            )
          )
        )
      )
    else if channel != "sms" && channel != "call" then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope("validation_failed", "Channel must be sms or call.")
          )
        )
      )
    else
      val update = if channel == "sms" then
        fr"""update public.contact set sms_consent = ${req.consent}, sms_consent_at = now()
             where id = $contactId and workspace_id = $ws returning id, sms_consent::text, call_consent::text,
             sms_consent_at::text, call_consent_at::text"""
          .query[(UUID, String, String, Option[String], Option[String])]
      else
        fr"""update public.contact set call_consent = ${req.consent}, call_consent_at = now()
             where id = $contactId and workspace_id = $ws returning id, sms_consent::text, call_consent::text,
             sms_consent_at::text, call_consent_at::text"""
          .query[(UUID, String, String, Option[String], Option[String])]
      update.option
        .transact(xa)
        .map {
          case Some(t) => Right(consentDto(t))
          case None    =>
            Left(
              (SttpStatus.NotFound, Envelope("not_found", "Contact not found."))
            )
        }

  private def getConsentLogic(
      ws: UUID,
      contactId: UUID
  ): IO[Either[(SttpStatus, Envelope), ChannelDtos.ConsentDto]] =
    sql"""select id, sms_consent::text, call_consent::text, sms_consent_at::text, call_consent_at::text
          from public.contact where id = $contactId and workspace_id = $ws"""
      .query[(UUID, String, String, Option[String], Option[String])]
      .option
      .transact(xa)
      .map {
        case Some(t) => Right(consentDto(t))
        case None    =>
          Left(
            (SttpStatus.NotFound, Envelope("not_found", "Contact not found."))
          )
      }

  private def sendSmsLogic(
      ws: UUID,
      contactId: UUID,
      req: ChannelDtos.SmsSendRequest
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    smsAdapter match
      case None =>
        IO.pure(
          Left(
            (
              SttpStatus.ServiceUnavailable,
              Envelope(
                "sms_not_configured",
                "No SMS provider is configured for this deployment."
              )
            )
          )
        )
      case Some(adapter) =>
        sql"""select phone, sms_consent::text from public.contact
              where id = $contactId and workspace_id = $ws"""
          .query[(Option[String], String)]
          .option
          .transact(xa)
          .flatMap {
            case None =>
              IO.pure(
                Left(
                  (
                    SttpStatus.NotFound,
                    Envelope("not_found", "Contact not found.")
                  )
                )
              )
            case Some((None, _)) =>
              IO.pure(
                Left(
                  (
                    SttpStatus.UnprocessableEntity,
                    Envelope(
                      "validation_failed",
                      "Contact has no phone number."
                    )
                  )
                )
              )
            case Some((Some(_), consent)) if !Channels.consentAllows(consent) =>
              IO.pure(
                Left(
                  (
                    SttpStatus.Forbidden,
                    Envelope(
                      "sms_consent_missing",
                      "Contact has not opted in to SMS. Email consent does not cover SMS."
                    )
                  )
                )
              )
            case Some((Some(phone), _)) =>
              adapter.send(req.fromNumber, phone, req.text).map {
                case Channels.Sent(id) =>
                  Right(
                    Json.obj(
                      "sent" -> Json.fromBoolean(true),
                      "providerMessageId" -> Json.fromString(id)
                    )
                  )
                case Channels.Blocked(r) =>
                  Left((SttpStatus.Forbidden, Envelope("sms_blocked", r)))
                case Channels.Failed(r) =>
                  Left((SttpStatus.BadGateway, Envelope("sms_failed", r)))
              }
          }

  private def logInteractionLogic(
      ws: UUID,
      user: UUID,
      req: ChannelDtos.InteractionRequest
  ): IO[Either[(SttpStatus, Envelope), ChannelDtos.InteractionDto]] =
    if (req.channel != "sms" && req.channel != "call") || (req.direction != "inbound" && req.direction != "outbound")
    then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope(
              "validation_failed",
              "channel must be sms|call; direction must be inbound|outbound."
            )
          )
        )
      )
    else
      (for
        exists <-
          sql"select count(*) > 0 from public.contact where id = ${req.contactId} and workspace_id = $ws"
            .query[Boolean]
            .unique
        result <-
          if !exists then
            FC.pure(
              Left(
                (
                  SttpStatus.NotFound,
                  Envelope("not_found", "Contact not found.")
                )
              )
            )
          else
            sql"""insert into public.channel_interactions
                  (workspace_id, contact_id, channel, direction, summary, outcome, created_by)
                  values ($ws, ${req.contactId}, ${req.channel}, ${req.direction}, ${req.summary}, ${req.outcome}, $user)
                  returning id, contact_id, channel, direction, summary, outcome, occurred_at::text"""
              .query[
                (UUID, UUID, String, String, String, Option[String], String)
              ]
              .unique
              .map(t =>
                Right(
                  ChannelDtos
                    .InteractionDto(t._1, t._2, t._3, t._4, t._5, t._6, t._7)
                )
              )
      yield result).transact(xa)

  private def listInteractionsLogic(
      ws: UUID,
      contactId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[ChannelDtos.InteractionDto]]] =
    sql"""select id, contact_id, channel, direction, summary, outcome, occurred_at::text
          from public.channel_interactions where workspace_id = $ws and contact_id = $contactId
          order by occurred_at desc limit 100"""
      .query[(UUID, UUID, String, String, String, Option[String], String)]
      .to[List]
      .transact(xa)
      .map(rows =>
        Right(
          rows.map(t =>
            ChannelDtos.InteractionDto(t._1, t._2, t._3, t._4, t._5, t._6, t._7)
          )
        )
      )

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      setConsent.serverLogic((w, contactId, channel, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => setConsentLogic(w, contactId, channel, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), ChannelDtos.ConsentDto]
              ]
            )
        }
      ),
      getConsent.serverLogic((w, contactId, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => getConsentLogic(w, contactId)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), ChannelDtos.ConsentDto]
              ]
            )
        }
      ),
      sendSms.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => sendSmsLogic(w, req.contactId, req)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      logInteraction.serverLogic((w, auth, req) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => logInteractionLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), ChannelDtos.InteractionDto]
              ]
            )
        }
      ),
      listInteractions.serverLogic((w, contactId, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => listInteractionsLogic(w, contactId)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                ChannelDtos.InteractionDto
              ]]]
            )
        }
      )
    )
  )

  private def envStatus(env: Envelope): SttpStatus =
    env.code match
      case "not_found"          => SttpStatus.NotFound
      case "validation_failed"  => SttpStatus.UnprocessableEntity
      case "sms_not_configured" => SttpStatus.ServiceUnavailable
      case "sms_consent_missing" | "sms_blocked" => SttpStatus.Forbidden
      case "sms_failed"                          => SttpStatus.BadGateway
      case _ => SttpStatus.InternalServerError
