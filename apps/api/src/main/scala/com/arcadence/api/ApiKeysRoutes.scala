package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import scala.util.Random

object ApiKeysDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class CreateKeyRequest(
      name: String,
      scopes: Option[List[String]] = None,
      expiresInDays: Option[Int] = None
  )
  object CreateKeyRequest:
    given Encoder[CreateKeyRequest] = deriveEncoder
    given Decoder[CreateKeyRequest] = deriveDecoder
    given Schema[CreateKeyRequest] = Schema.derived

  final case class ApiKeyDto(
      id: UUID,
      name: String,
      keyPrefix: String,
      scopes: List[String],
      lastUsedAt: Option[String],
      expiresAt: Option[String],
      revokedAt: Option[String],
      createdAt: String
  )
  object ApiKeyDto:
    given Encoder[ApiKeyDto] = deriveEncoder
    given Decoder[ApiKeyDto] = deriveDecoder
    given Schema[ApiKeyDto] = Schema.derived

  final case class WebhookSubDto(
      id: UUID,
      url: String,
      events: List[String],
      active: Boolean,
      createdAt: String
  )
  object WebhookSubDto:
    given Encoder[WebhookSubDto] = deriveEncoder
    given Decoder[WebhookSubDto] = deriveDecoder
    given Schema[WebhookSubDto] = Schema.derived

  final case class CreateWebhookRequest(url: String, events: List[String])
  object CreateWebhookRequest:
    given Encoder[CreateWebhookRequest] = deriveEncoder
    given Decoder[CreateWebhookRequest] = deriveDecoder
    given Schema[CreateWebhookRequest] = Schema.derived

/** Public API keys and webhook subscriptions (Phase 12.1). Secrets are shown
  * exactly once at creation and stored only as sha256 hashes with a display
  * prefix; scopes are validated against a whitelist; expiry and revocation gate
  * authentication. Webhook endpoints get per-subscription signing secrets
  * (shown once) and recorded deliveries.
  */
final class ApiKeysRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO]
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  val knownScopes: Set[String] = Set(
    "leads:read",
    "leads:write",
    "campaigns:read",
    "campaigns:write",
    "inbox:read",
    "analytics:read",
    "crm:read",
    "crm:write",
    "ai:write"
  )

  val documentedEvents: Set[String] = Set(
    "lead.created",
    "lead.added_to_list",
    "campaign.completed",
    "reply.received",
    "reply.classified",
    "deal.created",
    "deal.stage_changed",
    "approval.requested"
  )

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

  private def sha256(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  private def newSecret: (String, String, String) =
    val alphabet =
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val body =
      (1 to 40).map(i => alphabet(Random.nextInt(alphabet.length))).mkString
    val prefix = "ak_" + body.take(8)
    val secret = s"$prefix.$body"
    (prefix, secret, sha256(secret))

  // -- endpoints ------------------------------------------------------------------

  private val wspath = path[UUID]("workspaceId")

  private val createKey = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "api-keys")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[ApiKeysDtos.CreateKeyRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val listKeys = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "api-keys")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[ApiKeysDtos.ApiKeyDto]])
    .errorOut(errorOut)

  private val revokeKey = endpoint.delete
    .in("api" / "v1" / "workspaces" / wspath / "api-keys" / path[UUID]("keyId"))
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val listWebhooks = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "webhooks")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[ApiKeysDtos.WebhookSubDto]])
    .errorOut(errorOut)

  private val createWebhook = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "webhooks")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[ApiKeysDtos.CreateWebhookRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private def deleteWebhook = endpoint.delete
    .in("api" / "v1" / "workspaces" / wspath / "webhooks" / path[UUID]("subId"))
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  // -- logic -------------------------------------------------------------------------

  private def createKeyLogic(
      ws: UUID,
      user: UUID,
      req: ApiKeysDtos.CreateKeyRequest
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    val scopes = req.scopes.getOrElse(List("leads:read", "campaigns:read"))
    val bad = scopes.filterNot(knownScopes.contains)
    if req.name.trim.isEmpty then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope("validation_failed", "Name is required.")
          )
        )
      )
    else if bad.nonEmpty then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope(
              "validation_failed",
              s"Unknown scopes: ${bad.mkString(", ")}. Allowed: ${knownScopes.toList.sorted.mkString(", ")}."
            )
          )
        )
      )
    else
      val (prefix, secret, hash) = newSecret
      val scopesText = scopes.mkString(",")
      val days = req.expiresInDays.filter(d => d > 0 && d <= 365)
      val insert: ConnectionIO[UUID] = days.fold(
        sql"""insert into public.api_keys (workspace_id, name, key_prefix, key_hash, scopes, created_by)
              values ($ws, ${req.name.trim}, $prefix, $hash, string_to_array($scopesText, ','), $user)
              returning id""".query[UUID].unique
      )(d =>
        sql"""insert into public.api_keys (workspace_id, name, key_prefix, key_hash, scopes, created_by, expires_at)
              values ($ws, ${req.name.trim}, $prefix, $hash, string_to_array($scopesText, ','), $user,
                      now() + ($d * interval '1 day'))
              returning id""".query[UUID].unique
      )
      insert
        .map { id =>
          Right(
            Json.obj(
              "id" -> Json.fromString(id.toString),
              "keyPrefix" -> Json.fromString(prefix),
              "secret" -> Json.fromString(secret),
              "scopes" -> Json.fromValues(scopes.map(Json.fromString)),
              "note" -> Json.fromString(
                "Store this secret now — it is shown exactly once and only its hash is retained."
              )
            )
          ): Either[(SttpStatus, Envelope), Json]
        }
        .transact(xa)

  private def listKeysLogic(
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), List[ApiKeysDtos.ApiKeyDto]]] =
    sql"""select id, name, key_prefix, array_to_string(scopes, ','), last_used_at::text, expires_at::text, revoked_at::text, created_at::text
          from public.api_keys where workspace_id = $ws order by created_at desc"""
      .query[
        (
            UUID,
            String,
            String,
            String,
            Option[String],
            Option[String],
            Option[String],
            String
        )
      ]
      .to[List]
      .transact(xa)
      .map(rows =>
        Right(
          rows.map(t =>
            ApiKeysDtos.ApiKeyDto(
              t._1,
              t._2,
              t._3,
              t._4.split(",").filter(_.nonEmpty).toList,
              t._5,
              t._6,
              t._7,
              t._8
            )
          )
        )
      )

  private def revokeKeyLogic(
      ws: UUID,
      keyId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    sql"""update public.api_keys set revoked_at = now()
          where id = $keyId and workspace_id = $ws and revoked_at is null returning id"""
      .query[UUID]
      .option
      .transact(xa)
      .map {
        case Some(id) =>
          Right(Json.obj("revoked" -> Json.fromString(id.toString)))
        case None =>
          Left(
            (
              SttpStatus.NotFound,
              Envelope("not_found", "Key not found or already revoked.")
            )
          )
      }

  private def listWebhooksLogic(
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), List[ApiKeysDtos.WebhookSubDto]]] =
    sql"""select id, url, array_to_string(events, ','), active, created_at::text from public.webhook_subscriptions
          where workspace_id = $ws order by created_at desc"""
      .query[(UUID, String, String, Boolean, String)]
      .to[List]
      .transact(xa)
      .map(rows =>
        Right(
          rows.map(t =>
            ApiKeysDtos.WebhookSubDto(
              t._1,
              t._2,
              t._3.split(",").filter(_.nonEmpty).toList,
              t._4,
              t._5
            )
          )
        )
      )

  private def createWebhookLogic(
      ws: UUID,
      user: UUID,
      req: ApiKeysDtos.CreateWebhookRequest
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    val bad = req.events.filterNot(documentedEvents.contains)
    if bad.nonEmpty then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope(
              "validation_failed",
              s"Unknown events: ${bad.mkString(", ")}. Documented: ${documentedEvents.toList.sorted.mkString(", ")}."
            )
          )
        )
      )
    else
      val secret = "whsec_" + (1 to 40)
        .map(_ => "abcdef0123456789" (Random.nextInt(16)))
        .mkString
      sql"""insert into public.webhook_subscriptions (workspace_id, url, events, secret, created_by)
            values ($ws, ${req.url.trim}, string_to_array(${req.events.distinct
          .mkString(",")}, ','), $secret, $user)
            returning id, url, array_to_string(events, ','), active, created_at::text"""
        .query[(UUID, String, String, Boolean, String)]
        .unique
        .transact(xa)
        .map { t =>
          Right(
            Json.obj(
              "id" -> Json.fromString(t._1.toString),
              "url" -> Json.fromString(t._2),
              "events" -> Json.fromValues(t._3.split(",").map(Json.fromString)),
              "signingSecret" -> Json.fromString(secret),
              "note" -> Json.fromString(
                "Store the signing secret now — it is shown exactly once. Deliveries are HMAC-SHA256 signed (X-Arcadence-Signature) with retries and dead-lettering."
              )
            )
          )
        }

  private def deleteWebhookLogic(
      ws: UUID,
      subId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    sql"""delete from public.webhook_subscriptions where id = $subId and workspace_id = $ws returning id"""
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
              Envelope("not_found", "Subscription not found.")
            )
          )
      }

  // -- assembly --------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      createKey.serverLogic((w, auth, req) =>
        requireManage(auth, w).flatMap {
          case Right(user) => createKeyLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      listKeys.serverLogic((w, auth) =>
        requireManage(auth, w).flatMap {
          case Right(_)    => listKeysLogic(w)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                ApiKeysDtos.ApiKeyDto
              ]]]
            )
        }
      ),
      revokeKey.serverLogic((w, keyId, auth) =>
        requireManage(auth, w).flatMap {
          case Right(_)    => revokeKeyLogic(w, keyId)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      listWebhooks.serverLogic((w, auth) =>
        requireManage(auth, w).flatMap {
          case Right(_)    => listWebhooksLogic(w)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                ApiKeysDtos.WebhookSubDto
              ]]]
            )
        }
      ),
      createWebhook.serverLogic((w, auth, req) =>
        requireManage(auth, w).flatMap {
          case Right(user) => createWebhookLogic(w, user, req)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      deleteWebhook.serverLogic((w, subId, auth) =>
        requireManage(auth, w).flatMap {
          case Right(_)    => deleteWebhookLogic(w, subId)
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      )
    )
  )

object ApiKeys:
  /** Authenticates an API-key bearer token: sha256 lookup, revocation and
    * expiry checks, last_used_at heartbeat. Returns the workspace and the key's
    * scopes on success.
    */
  def authenticate(
      token: String,
      xa: Transactor[IO]
  ): IO[Option[(UUID, List[String])]] =
    val hash = MessageDigest
      .getInstance("SHA-256")
      .digest(token.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString
    sql"""select workspace_id, array_to_string(scopes, ',') from public.api_keys
          where key_hash = $hash and revoked_at is null
            and (expires_at is null or expires_at > now())"""
      .query[(UUID, String)]
      .option
      .transact(xa)
      .map(_.map { case (wsId, scopesCsv) =>
        (wsId, scopesCsv.split(",").filter(_.nonEmpty).toList)
      })
      .flatTap { _ =>
        sql"""update public.api_keys set last_used_at = now() where key_hash = $hash""".update.run.void
          .transact(xa)
      }
