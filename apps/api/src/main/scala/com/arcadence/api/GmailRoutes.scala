package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.crypto.StateInvalid
import com.arcadence.core.email.{
  AuthorizationResult,
  Credentials,
  OutboundMessage,
  ProviderError
}
import com.arcadence.core.email.gmail.GmailProvider
import io.circe.Json
import io.circe.parser.parse
import org.http4s.{HttpRoutes, Method, Request, Response, Status, Uri}
import org.typelevel.ci.CIString
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.duration.*

object GmailDtos:
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import io.circe.{Decoder, Encoder}
  import sttp.tapir.Schema

  final case class StartRequest(workspaceId: UUID)
  object StartRequest:
    given Encoder[StartRequest] = deriveEncoder
    given Decoder[StartRequest] = deriveDecoder
    given Schema[StartRequest] = Schema.derived

  final case class StartResponse(authorizeUrl: String)
  object StartResponse:
    given Encoder[StartResponse] = deriveEncoder
    given Decoder[StartResponse] = deriveDecoder
    given Schema[StartResponse] = Schema.derived

/** Gmail connect flow (Phase 2.2): state-signed authorization start, a
  * browser-facing OAuth callback that lands back on the web app, and the
  * authorized test send.
  *
  * The callback is a plain http4s route (top-level browser navigation — no
  * Authorization header is available; identity and authorization come from the
  * HMAC-signed state, which is bound to workspace + user + PKCE verifier and
  * expires after 10 minutes).
  */
final class GmailRoutes(
    authenticator: Authenticator,
    repos: EmailAccountRepos,
    authorizer: Authorizer,
    registry: ProviderRegistry
):
  import GmailDtos.*

  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def verifyBearer(
      authorization: Option[String]
  ): IO[Either[(SttpStatus, Envelope), AuthenticatedUser]] =
    authorization match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator
          .verify(token.stripPrefix("Bearer ").trim)
          .map(
            _.leftMap(e =>
              (SttpStatus.Unauthorized, Envelope(e.code, e.message))
            )
          )
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  // -- start -------------------------------------------------------------------

  private val start = endpoint.post
    .in("api" / "v1" / "email-connections" / "oauth" / "google" / "start")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[StartRequest])
    .out(jsonBody[StartResponse])
    .errorOut(errorOut)

  private def startLogic(
      authorization: Option[String],
      request: StartRequest
  ): IO[Either[(SttpStatus, Envelope), StartResponse]] =
    verifyBearer(authorization).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(user)    =>
        authorizer
          .requirePermission(
            user,
            request.workspaceId,
            Permission.ManageEmailAccounts
          )
          .flatMap {
            case Left(error) => IO.pure(Left(failed(error)))
            case Right(_)    =>
              registry.gmail match
                case None =>
                  IO.pure(
                    Left(
                      failed(
                        ApiError.ProviderNotConfigured(
                          "Google OAuth is not configured on this server — set GOOGLE_OAUTH_CLIENT_ID and GOOGLE_OAUTH_CLIENT_SECRET (see docs/MANUAL_SETUP.md)."
                        )
                      )
                    )
                  )
                case Some(gmail) =>
                  IO {
                    val verifier = ProviderRegistry.randomVerifier
                    val payload = Json.obj(
                      "ws" -> Json.fromString(request.workspaceId.toString),
                      "uid" -> Json.fromString(user.id.toString),
                      "exp" -> Json.fromLong(
                        OffsetDateTime.now
                          .plusSeconds(GmailRoutes.StateTtlSeconds)
                          .toEpochSecond
                      ),
                      "nonce" -> Json.fromString(
                        ProviderRegistry.randomUrlSafeToken(16)
                      ),
                      "v" -> Json.fromString(verifier)
                    )
                    val state = registry.stateSigner.sign(payload.noSpaces)
                    StartResponse(
                      gmail.authorizationUrl(
                        state,
                        ProviderRegistry.pkceChallenge(verifier)
                      )
                    )
                  }.map(Right(_))
          }
    }

  // -- assembly ----------------------------------------------------------------

  val http: HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      List[ServerEndpoint[Fs2Streams[IO], IO]](
        start.serverLogic((auth, req) => startLogic(auth, req))
      )
    ) <+> callbackRoute

  // The OAuth callback: browser navigation, no bearer header. Any failure
  // lands back on the email-accounts page with a safe query-string error.
  private val CallbackPath = "/api/v1/email-connections/oauth/google/callback"
  private def callbackRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req
        if req.method == Method.GET && req.uri.path.toString == CallbackPath =>
      handleCallback(req)
        .handleError(t =>
          redirect(
            s"gmail=error&detail=${urlQ(Option(t.getMessage).getOrElse("unexpected error").take(160))}"
          )
        )
  }

  private def handleCallback(req: Request[IO]): IO[Response[IO]] =
    val params = req.uri.query.params
    val code = params.get("code")
    val state = params.get("state")
    (params.get("error"), state) match
      case (Some(googleError), _) =>
        IO.pure(redirect(s"gmail=denied&detail=${urlQ(googleError)}"))
      case (None, Some(state)) =>
        code match
          case None => IO.pure(redirect("gmail=error&detail=missing+code"))
          case Some(authCode) => completeAndRedirect(authCode, state)
      case _ => IO.pure(redirect("gmail=invalid_state"))

  private def completeAndRedirect(
      authCode: String,
      state: String
  ): IO[Response[IO]] =
    registry.stateSigner.verify(state) match
      case Left(StateInvalid.Expired) => IO.pure(redirect("gmail=expired"))
      case Left(_)        => IO.pure(redirect("gmail=invalid_state"))
      case Right(payload) =>
        parse(payload) match
          case Left(_)     => IO.pure(redirect("gmail=invalid_state"))
          case Right(json) =>
            val decoded = for
              ws <- json.hcursor.get[String]("ws").toOption
              uid <- json.hcursor.get[String]("uid").toOption
              v <- json.hcursor.get[String]("v").toOption
              exp <- json.hcursor.get[Long]("exp").toOption
            yield (UUID.fromString(ws), UUID.fromString(uid), v, exp)
            decoded match
              case None => IO.pure(redirect("gmail=invalid_state"))
              case Some((workspaceId, userId, verifier, exp)) =>
                if exp <= System.currentTimeMillis() / 1000L then
                  IO.pure(redirect("gmail=expired"))
                else
                  authorizer
                    .requirePermission(
                      AuthenticatedUser(userId, ""),
                      workspaceId,
                      Permission.ManageEmailAccounts
                    )
                    .flatMap {
                      case Left(_)  => IO.pure(redirect("gmail=forbidden"))
                      case Right(_) =>
                        registry.gmail match
                          case None => IO.pure(redirect("gmail=not_configured"))
                          case Some(gmail) =>
                            connectAccount(
                              gmail,
                              workspaceId,
                              userId,
                              authCode,
                              verifier
                            )
                    }

  private def connectAccount(
      gmail: GmailProvider,
      workspaceId: UUID,
      userId: UUID,
      authCode: String,
      verifier: String
  ): IO[Response[IO]] =
    for
      credsRef <- cats.effect.Ref.of[IO, Option[Credentials]](None)
      completed <- gmail.completeAuthorization(
        AuthorizationResult(
          code = authCode,
          redirectUri = registry.redirectUri,
          state = "",
          codeVerifier = Some(verifier)
        ),
        creds => credsRef.set(Some(creds))
      )
      response <- completed match
        case Left(providerError) =>
          IO.pure(
            redirect(
              s"gmail=error&detail=${urlQ(providerError.detail.take(160))}"
            )
          )
        case Right(identity) =>
          (for
            upserted <- repos.upsertConnectedAccount(
              workspaceId,
              EmailProviderId.Gmail,
              identity.emailAddress,
              identity.displayName
            )
            (accountId, created) = upserted
            _ <- repos.ensureSyncState(accountId)
            _ <- credsRef.get.flatMap {
              case None        => IO.unit
              case Some(creds) =>
                val expiresAt = creds match
                  case g: com.arcadence.core.email.gmail.GmailCredentials =>
                    g.accessTokenExpiresAt
                  case _ => OffsetDateTime.now.plusSeconds(3600)
                registry.credentialStorer(repos, accountId)(creds)
            }
            _ <- repos.recordHealthEvent(
              workspaceId,
              accountId,
              "connected",
              Some(if created then "Gmail account connected via Google OAuth."
              else "Gmail account reconnected via Google OAuth.")
            )
            _ <- repos.audit(
              workspaceId,
              userId,
              if created then "email_account.connected"
              else "email_account.reconnected",
              "email_account",
              accountId.toString,
              Json.obj("provider" -> Json.fromString("gmail"))
            )
          yield redirect(if created then "gmail=connected"
          else "gmail=reconnected"))
            .handleError(t =>
              redirect(
                s"gmail=error&detail=${urlQ(Option(t.getMessage).getOrElse("storage failure").take(160))}"
              )
            )
    yield response

  // -- authorized test send ------------------------------------------------------

  // -- helpers -------------------------------------------------------------------

  private def redirect(query: String): Response[IO] =
    Response[IO](Status.SeeOther).putHeaders(
      org.http4s.headers.Location(
        Uri.unsafeFromString(s"${registry.appOrigin}/app/email-accounts?$query")
      )
    )

  private def urlQ(value: String): String =
    java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)

object GmailRoutes:
  val StateTtlSeconds: Long = 600L

object EmailProviderId:
  val Gmail: String = "gmail"
  val Microsoft: String = "microsoft"
