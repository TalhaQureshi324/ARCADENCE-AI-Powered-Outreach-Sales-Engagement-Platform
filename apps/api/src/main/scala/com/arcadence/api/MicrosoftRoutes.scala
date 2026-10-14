package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.crypto.StateInvalid
import com.arcadence.core.email.{AuthorizationResult, Credentials}
import com.arcadence.core.email.microsoft.GraphProvider
import io.circe.Json
import io.circe.parser.parse
import org.http4s.{HttpRoutes, Method, Request, Response, Status, Uri}
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.time.OffsetDateTime
import java.util.UUID

object MicrosoftDtos:
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

/** Microsoft 365 connect flow (Phase 2.3): state-signed authorization start and
  * the browser-facing Graph OAuth callback — the same tamper-proof shape as the
  * Gmail flow (ADR-0021/0022), landing back on the app with a `microsoft=…`
  * outcome parameter.
  */
final class MicrosoftRoutes(
    authenticator: Authenticator,
    repos: EmailAccountRepos,
    authorizer: Authorizer,
    registry: ProviderRegistry
):
  import MicrosoftDtos.*

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

  private val start = endpoint.post
    .in("api" / "v1" / "email-connections" / "oauth" / "microsoft" / "start")
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
              registry.microsoft match
                case None =>
                  IO.pure(
                    Left(
                      failed(
                        ApiError.ProviderNotConfigured(
                          "Microsoft OAuth is not configured on this server — set MSFT_OAUTH_CLIENT_ID and MSFT_OAUTH_CLIENT_SECRET (see docs/MANUAL_SETUP.md)."
                        )
                      )
                    )
                  )
                case Some(graph) =>
                  IO {
                    val verifier = ProviderRegistry.randomVerifier
                    val payload = Json.obj(
                      "ws" -> Json.fromString(request.workspaceId.toString),
                      "uid" -> Json.fromString(user.id.toString),
                      "exp" -> Json.fromLong(
                        OffsetDateTime.now
                          .plusSeconds(MicrosoftRoutes.StateTtlSeconds)
                          .toEpochSecond
                      ),
                      "nonce" -> Json.fromString(
                        ProviderRegistry.randomUrlSafeToken(16)
                      ),
                      "v" -> Json.fromString(verifier)
                    )
                    val state = registry.stateSigner.sign(payload.noSpaces)
                    StartResponse(
                      graph.authorizationUrl(
                        state,
                        ProviderRegistry.pkceChallenge(verifier)
                      )
                    )
                  }.map(Right(_))
          }
    }

  val http: HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      List[ServerEndpoint[Fs2Streams[IO], IO]](
        start.serverLogic((auth, req) => startLogic(auth, req))
      )
    ) <+> callbackRoute

  private val CallbackPath =
    "/api/v1/email-connections/oauth/microsoft/callback"
  private def callbackRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req
        if req.method == Method.GET && req.uri.path.toString == CallbackPath =>
      handleCallback(req)
        .handleError(t =>
          redirect(
            s"microsoft=error&detail=${urlQ(Option(t.getMessage).getOrElse("unexpected error").take(160))}"
          )
        )
  }

  private def handleCallback(req: Request[IO]): IO[Response[IO]] =
    val params = req.uri.query.params
    val code = params.get("code")
    val state = params.get("state")
    (params.get("error"), state) match
      case (Some(msError), _) =>
        IO.pure(redirect(s"microsoft=denied&detail=${urlQ(msError)}"))
      case (None, Some(state)) =>
        code match
          case None => IO.pure(redirect("microsoft=error&detail=missing+code"))
          case Some(authCode) => completeAndRedirect(authCode, state)
      case _ => IO.pure(redirect("microsoft=invalid_state"))

  private def completeAndRedirect(
      authCode: String,
      state: String
  ): IO[Response[IO]] =
    registry.stateSigner.verify(state) match
      case Left(StateInvalid.Expired) => IO.pure(redirect("microsoft=expired"))
      case Left(_)        => IO.pure(redirect("microsoft=invalid_state"))
      case Right(payload) =>
        parse(payload) match
          case Left(_)     => IO.pure(redirect("microsoft=invalid_state"))
          case Right(json) =>
            val decoded = for
              ws <- json.hcursor.get[String]("ws").toOption
              uid <- json.hcursor.get[String]("uid").toOption
              v <- json.hcursor.get[String]("v").toOption
              exp <- json.hcursor.get[Long]("exp").toOption
            yield (UUID.fromString(ws), UUID.fromString(uid), v, exp)
            decoded match
              case None => IO.pure(redirect("microsoft=invalid_state"))
              case Some((workspaceId, userId, verifier, exp)) =>
                if exp <= System.currentTimeMillis() / 1000L then
                  IO.pure(redirect("microsoft=expired"))
                else
                  authorizer
                    .requirePermission(
                      AuthenticatedUser(userId, ""),
                      workspaceId,
                      Permission.ManageEmailAccounts
                    )
                    .flatMap {
                      case Left(_)  => IO.pure(redirect("microsoft=forbidden"))
                      case Right(_) =>
                        registry.microsoft match
                          case None =>
                            IO.pure(redirect("microsoft=not_configured"))
                          case Some(graph) =>
                            connectAccount(
                              graph,
                              workspaceId,
                              userId,
                              authCode,
                              verifier
                            )
                    }

  private def connectAccount(
      graph: GraphProvider,
      workspaceId: UUID,
      userId: UUID,
      authCode: String,
      verifier: String
  ): IO[Response[IO]] =
    for
      credsRef <- cats.effect.Ref.of[IO, Option[Credentials]](None)
      completed <- graph.completeAuthorization(
        AuthorizationResult(
          code = authCode,
          redirectUri = registry.redirectUri.replace("/google/", "/microsoft/"),
          state = "",
          codeVerifier = Some(verifier)
        ),
        creds => credsRef.set(Some(creds))
      )
      response <- completed match
        case Left(providerError) =>
          IO.pure(
            redirect(
              s"microsoft=error&detail=${urlQ(providerError.detail.take(160))}"
            )
          )
        case Right(identity) =>
          (for
            upserted <- repos.upsertConnectedAccount(
              workspaceId,
              EmailProviderId.Microsoft,
              identity.emailAddress,
              identity.displayName
            )
            (accountId, created) = upserted
            _ <- repos.ensureSyncState(accountId)
            _ <- credsRef.get.flatMap {
              case None        => IO.unit
              case Some(creds) =>
                registry.credentialStorer(repos, accountId)(creds)
            }
            _ <- repos.recordHealthEvent(
              workspaceId,
              accountId,
              "connected",
              Some(
                if created then
                  "Microsoft 365 account connected via Graph OAuth."
                else "Microsoft 365 account reconnected via Graph OAuth."
              )
            )
            _ <- repos.audit(
              workspaceId,
              userId,
              if created then "email_account.connected"
              else "email_account.reconnected",
              "email_account",
              accountId.toString,
              Json.obj("provider" -> Json.fromString("microsoft"))
            )
          yield redirect(
            if created then "microsoft=connected" else "microsoft=reconnected"
          ))
            .handleError(t =>
              redirect(
                s"microsoft=error&detail=${urlQ(Option(t.getMessage).getOrElse("storage failure").take(160))}"
              )
            )
    yield response

  private def redirect(query: String): Response[IO] =
    Response[IO](Status.SeeOther).putHeaders(
      org.http4s.headers.Location(
        Uri.unsafeFromString(s"${registry.appOrigin}/app/email-accounts?$query")
      )
    )

  private def urlQ(value: String): String =
    java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)

object MicrosoftRoutes:
  val StateTtlSeconds: Long = 600L
