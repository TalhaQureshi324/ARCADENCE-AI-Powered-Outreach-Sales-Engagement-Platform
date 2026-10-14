package com.arcadence.core.email.microsoft

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.core.email.{
  AccountIdentity,
  AuthorizationResult,
  Credentials,
  EmailProvider,
  HealthSnapshot,
  InboxBatch,
  InboxMessage,
  OutboundMessage,
  ProviderCapabilities,
  ProviderError,
  ProviderMessageId,
  ReplyMessage
}
import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.http4s.{Method, Request, UrlForm}

import java.time.{Instant, OffsetDateTime, ZoneOffset, format as jtimeformat}

/** Microsoft 365 adapter over the Microsoft identity platform (v2) and
  * Microsoft Graph (Phase 2.3, ADR-0022).
  *
  * Delegated permissions: Mail.Send + Mail.Read + User.Read + offline_access.
  * Sends go out as draft-then-send so the provider message id is captured (POST
  * /me/sendMail returns only 202 with no id). Inbox listing uses the
  * `@odata.nextLink` skip-token as cursor; the delta-query /
  * change-notification design (ADR-0022) builds on the same folder surface in
  * Phase 6.
  *
  * Tenant behavior: the authority tenant is configurable (`common` by default);
  * tenant admins may block the app — consent failures surface as typed
  * PermissionDenied errors, never as silent connection attempts.
  */
final class GraphProvider(
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    endpoints: GraphEndpoints,
    client: Client[IO]
) extends EmailProvider:

  import GraphProvider.*

  val id: EmailProvider.Id = EmailProvider.Microsoft

  val capabilities: ProviderCapabilities =
    ProviderCapabilities.forProvider(EmailProvider.Microsoft).get

  def authorizationUrl(
      state: String,
      codeChallenge: String,
      loginHint: Option[String] = None
  ): String =
    val base = Map(
      "client_id" -> clientId,
      "response_type" -> "code",
      "redirect_uri" -> redirectUri,
      "response_mode" -> "query",
      "scope" -> GraphScopes.All.mkString(" "),
      "state" -> state,
      "code_challenge" -> codeChallenge,
      "code_challenge_method" -> "S256"
    ) ++ loginHint.map(hint => "login_hint" -> hint)
    val query = base.map((k, v) => s"$k=${urlEncode(v)}").mkString("&")
    s"${endpoints.authorizeUrl}?$query"

  def completeAuthorization(
      authorization: AuthorizationResult,
      store: Credentials => IO[Unit]
  ): IO[Either[ProviderError, AccountIdentity]] =
    val form = UrlForm(
      Seq(
        "client_id" -> clientId,
        "client_secret" -> clientSecret,
        "code" -> authorization.code,
        "redirect_uri" -> authorization.redirectUri,
        "grant_type" -> "authorization_code"
      ) ++ authorization.codeVerifier.map(v => "code_verifier" -> v).toList: _*
    )
    for
      tokenJson <- postForm(endpoints.tokenUrl, form)
      creds <- tokenJson match
        case Left(error) => IO.pure(Left(error))
        case Right(json) =>
          decodeTokenResponse(json) match
            case Left(detail) =>
              IO.pure(
                Left(ProviderError.Unexpected(s"token response: $detail"))
              )
            case Right((refresh, access, expiresIn)) =>
              val creds = MicrosoftCredentials(
                refreshToken = refresh,
                accessToken = access,
                accessTokenExpiresAt =
                  OffsetDateTime.now.plusSeconds(math.max(1, expiresIn))
              )
              store(creds).as(Right(creds))
      identity <- creds match
        case Left(error)       => IO.pure(Left(error))
        case Right(validCreds) => whoAmI(validCreds)
    yield identity

  def validateConnection(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, AccountIdentity]] =
    withFresh(load).flatMap {
      case Left(error)  => IO.pure(Left(error))
      case Right(creds) => whoAmI(creds)
    }

  def submit(
      load: IO[Option[Credentials]],
      message: OutboundMessage
  ): IO[Either[ProviderError, ProviderMessageId]] =
    withFresh(load).flatMap {
      case Left(error)  => IO.pure(Left(error))
      case Right(creds) =>
        val draft = Json.obj(
          "subject" -> Json.fromString(message.subject),
          "body" -> Json.obj(
            "contentType" -> Json.fromString(if message.htmlBody.isDefined then
              "HTML"
            else "Text"),
            "content" -> Json.fromString(
              message.htmlBody.getOrElse(message.textBody)
            )
          ),
          "toRecipients" -> Json.fromValues(
            message.to.map(addr =>
              Json.obj(
                "emailAddress" -> Json.obj("address" -> Json.fromString(addr))
              )
            )
          )
        )
        postJson(endpoints.createDraftUrl, draft, creds).flatMap {
          case Left(error)    => IO.pure(Left(error))
          case Right(created) =>
            str(created, "id") match
              case None =>
                IO.pure(
                  Left(
                    ProviderError.Unexpected(
                      "draft creation returned no message id"
                    )
                  )
                )
              case Some(draftId) =>
                postEmpty(s"${endpoints.sendDraftUrl(draftId)}", creds)
                  .map(_.as(ProviderMessageId(draftId)))
        }
    }

  def submitReply(
      load: IO[Option[Credentials]],
      reply: ReplyMessage
  ): IO[Either[ProviderError, ProviderMessageId]] =
    withFresh(load).flatMap {
      case Left(error)  => IO.pure(Left(error))
      case Right(creds) =>
        // createReply copies the parent thread context (In-Reply-To/References);
        // the draft body is replaced with our content, then the draft is sent.
        postJson(
          endpoints.createReplyUrl(reply.inReplyToMessageId),
          Json.obj(),
          creds
        ).flatMap {
          case Left(error)    => IO.pure(Left(error))
          case Right(created) =>
            str(created, "id") match
              case None =>
                IO.pure(
                  Left(
                    ProviderError.Unexpected(
                      "reply draft returned no message id"
                    )
                  )
                )
              case Some(draftId) =>
                val bodyPatch = Json.obj(
                  "body" -> Json.obj(
                    "contentType" -> Json.fromString(
                      if reply.htmlBody.isDefined then "HTML" else "Text"
                    ),
                    "content" -> Json.fromString(
                      reply.htmlBody.getOrElse(reply.textBody)
                    )
                  )
                )
                patchJson(
                  s"${endpoints.graphApiBaseUrl}/me/messages/$draftId",
                  bodyPatch,
                  creds
                ).flatMap {
                  case Left(error) => IO.pure(Left(error))
                  case Right(_)    =>
                    postEmpty(s"${endpoints.sendDraftUrl(draftId)}", creds)
                      .map(_.as(ProviderMessageId(draftId)))
                }
        }
    }

  def syncInbox(
      load: IO[Option[Credentials]],
      cursor: Option[String]
  ): IO[Either[ProviderError, InboxBatch]] =
    withFresh(load).flatMap {
      case Left(error)  => IO.pure(Left(error))
      case Right(creds) =>
        val base =
          s"${endpoints.inboxMessagesUrl}?%24select=id,conversationId,subject,from,receivedDateTime&%24top=25&%24orderby=receivedDateTime%20desc"
        val url = cursor match
          case Some(skipToken) if skipToken.nonEmpty =>
            // cursor holds the opaque $skiptoken from @odata.nextLink
            s"${endpoints.inboxMessagesUrl}?%24select=id,conversationId,subject,from,receivedDateTime&%24top=25&%24skiptoken=${urlEncode(skipToken)}"
          case None    => base
          case Some(_) => base
        getJson(url, creds).flatMap {
          case Left(error) => IO.pure(Left(error))
          case Right(json) =>
            val values =
              json.hcursor.downField("value").as[List[Json]].getOrElse(Nil)
            val messages = values.flatMap(readInboxMessage)
            val nextCursor = str(json, "@odata.nextLink").flatMap(nextLink =>
              UriQuery.parse(nextLink).get("$skiptoken")
            )
            IO.pure(Right(InboxBatch(messages, nextCursor)))
        }
    }

  def healthCheck(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, HealthSnapshot]] =
    withFresh(load).flatMap {
      case Left(error)  => IO.pure(Left(error))
      case Right(creds) =>
        getJson(s"${endpoints.meUrl}?%24select=id", creds).map(
          _.map(_ =>
            HealthSnapshot(
              reachable = true,
              quotaRemaining = None,
              checkedAt = OffsetDateTime.now
            )
          )
        )
    }

  def refresh(
      load: IO[Option[Credentials]],
      store: Credentials => IO[Unit]
  ): IO[Either[ProviderError, Unit]] =
    load.flatMap {
      case None =>
        IO.pure(
          Left(
            ProviderError.InvalidCredentials(
              "account has no stored credentials"
            )
          )
        )
      case Some(creds: MicrosoftCredentials) =>
        creds.refreshToken match
          case None =>
            IO.pure(
              Left(
                ProviderError.InvalidCredentials(
                  "no refresh token stored — reconnect required"
                )
              )
            )
          case Some(token) =>
            refreshAccess(token).flatMap {
              case Left(error)                => IO.pure(Left(error))
              case Right((access, expiresIn)) =>
                store(creds.withRefreshedAccess(access, expiresIn))
                  .as(Right(()))
            }
      case Some(_) =>
        IO.pure(
          Left(
            ProviderError.Unexpected(
              "stored credentials are not Microsoft credentials"
            )
          )
        )
    }

  /** Graph has no per-app token revocation endpoint; revokeSignInSessions
    * invalidates the refresh tokens this app issued for the user (best effort,
    * called by disconnect before the local purge).
    */
  def revokeAccess(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, Unit]] =
    load.flatMap {
      case None                              => IO.pure(Right(()))
      case Some(creds: MicrosoftCredentials) =>
        withFresh(IO.pure(Some(creds))).flatMap {
          case Left(error)  => IO.pure(Left(error))
          case Right(fresh) =>
            postEmpty(endpoints.revokeUrl, fresh).map(_.map(_ => ()))
        }
      case Some(_) =>
        IO.pure(
          Left(
            ProviderError.Unexpected(
              "stored credentials are not Microsoft credentials"
            )
          )
        )
    }

  // -- internals --------------------------------------------------------------

  private def whoAmI(
      creds: MicrosoftCredentials
  ): IO[Either[ProviderError, AccountIdentity]] =
    getJson(
      s"${endpoints.meUrl}?%24select=mail,userPrincipalName,displayName",
      creds
    ).map(
      _.map(json =>
        AccountIdentity(
          emailAddress = str(json, "mail")
            .orElse(str(json, "userPrincipalName"))
            .getOrElse(""),
          displayName = str(json, "displayName").filter(_.nonEmpty),
          externalAccountId = str(json, "id")
        )
      )
    )

  private def withFresh(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, MicrosoftCredentials]] =
    load.flatMap {
      case None =>
        IO.pure(
          Left(
            ProviderError.InvalidCredentials(
              "account has no stored credentials"
            )
          )
        )
      case Some(creds: MicrosoftCredentials) =>
        val expired =
          creds.accessTokenExpiresAt.isBefore(
            OffsetDateTime.now.plusSeconds(60)
          )
        if !expired then IO.pure(Right(creds))
        else
          creds.refreshToken match
            case None =>
              IO.pure(
                Left(
                  ProviderError.InvalidCredentials(
                    "access token expired and no refresh token — reconnect required"
                  )
                )
              )
            case Some(token) =>
              // In-memory only (ADR-0021 rationale); refresh tokens do not rotate.
              refreshAccess(token).map {
                case Left(error)                => Left(error)
                case Right((access, expiresIn)) =>
                  Right(creds.withRefreshedAccess(access, expiresIn))
              }
      case Some(_) =>
        IO.pure(
          Left(
            ProviderError.Unexpected(
              "stored credentials are not Microsoft credentials"
            )
          )
        )
    }

  private def refreshAccess(
      refreshToken: String
  ): IO[Either[ProviderError, (String, Long)]] =
    postForm(
      endpoints.tokenUrl,
      UrlForm(
        "client_id" -> clientId,
        "client_secret" -> clientSecret,
        "refresh_token" -> refreshToken,
        "grant_type" -> "refresh_token"
      )
    ).flatMap {
      case Left(error) => IO.pure(Left(error))
      case Right(json) =>
        str(json, "access_token") match
          case None =>
            IO.pure(
              Left(
                ProviderError.InvalidCredentials(
                  "refresh grant returned no access token"
                )
              )
            )
          case Some(a) =>
            IO.pure(
              Right((a, json.hcursor.get[Long]("expires_in").getOrElse(3600L)))
            )
    }

  private def decodeTokenResponse(
      json: Json
  ): Either[String, (Option[String], String, Long)] =
    for
      access <- str(json, "access_token").toRight("access_token missing")
      refresh = str(json, "refresh_token")
      expiresIn = json.hcursor.get[Long]("expires_in").getOrElse(3600L)
    yield (refresh, access, expiresIn)

  private def authHeader(creds: MicrosoftCredentials) =
    Authorization(
      org.http4s.Credentials
        .Token(org.http4s.AuthScheme.Bearer, creds.accessToken)
    )

  private def postForm(
      url: String,
      form: UrlForm
  ): IO[Either[ProviderError, Json]] =
    val uri = org.http4s.Uri.unsafeFromString(url)
    execute(Request[IO](Method.POST, uri).withEntity(form))

  private def postJson(
      url: String,
      body: Json,
      creds: MicrosoftCredentials
  ): IO[Either[ProviderError, Json]] =
    val uri = org.http4s.Uri.unsafeFromString(url)
    execute(
      Request[IO](Method.POST, uri)
        .withEntity(body)
        .withHeaders(authHeader(creds))
    )

  private def patchJson(
      url: String,
      body: Json,
      creds: MicrosoftCredentials
  ): IO[Either[ProviderError, Json]] =
    val uri = org.http4s.Uri.unsafeFromString(url)
    execute(
      Request[IO](Method.PATCH, uri)
        .withEntity(body)
        .withHeaders(authHeader(creds))
    )

  private def getJson(
      url: String,
      creds: MicrosoftCredentials
  ): IO[Either[ProviderError, Json]] =
    val uri = org.http4s.Uri.unsafeFromString(url)
    execute(Request[IO](Method.GET, uri).withHeaders(authHeader(creds)))

  /** POST with JSON body, accepting 202 Accepted (Graph fire-and-forget). */
  private def postEmpty(
      url: String,
      creds: MicrosoftCredentials
  ): IO[Either[ProviderError, Unit]] =
    val uri = org.http4s.Uri.unsafeFromString(url)
    val req = Request[IO](Method.POST, uri)
      .withEntity(Json.obj())
      .withHeaders(authHeader(creds))
    client
      .run(req)
      .use(res =>
        res.bodyText.compile.string.map { bodyText =>
          res.status.code match
            case 200 | 201 | 202 | 204 => Right(())
            case _                     =>
              Left(
                mapError(
                  res.status.code,
                  parse(bodyText).toOption.getOrElse(Json.Null),
                  bodyText,
                  res
                )
              )
        }
      )
      .handleError(t =>
        Left(
          ProviderError.Unreachable(
            Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
          )
        )
      )

  private def execute(request: Request[IO]): IO[Either[ProviderError, Json]] =
    client
      .run(request)
      .use(res =>
        res.bodyText.compile.string.map { bodyText =>
          val json = parse(bodyText).toOption.getOrElse(Json.Null)
          res.status.code match
            case code if code == 200 || code == 201 => Right(json)
            case other => Left(mapError(other, json, bodyText, res))
        }
      )
      .handleError(t =>
        Left(
          ProviderError.Unreachable(
            Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
          )
        )
      )

object GraphProvider:

  def str(json: Json, field: String): Option[String] =
    json.hcursor.get[String](field).toOption

  /** Microsoft identity platform + Graph error mapping (provider-specific codes
    * preserved in the detail string).
    */
  def mapError(
      statusCode: Int,
      json: Json,
      fallback: String,
      res: org.http4s.Response[IO]
  ): ProviderError =
    val code = json.hcursor.downField("error").get[String]("code").toOption
    val message =
      json.hcursor.downField("error").get[String]("message").toOption
    val oauthError = str(json, "error")
    val description = str(json, "error_description")
    val detail = message
      .orElse(description)
      .orElse(oauthError)
      .getOrElse(fallback.take(200))
    val codeStr = code.orElse(oauthError).getOrElse("")

    statusCode match
      case 400
          if codeStr.contains("invalid_grant") || codeStr.contains(
            "consent_required"
          ) ||
            codeStr.contains("interaction_required") =>
        ProviderError.InvalidCredentials(detail)
      case 401 =>
        ProviderError.InvalidCredentials(detail)
      case 403 =>
        ProviderError.PermissionDenied(detail)
      case 429 =>
        val retryAfter = res.headers
          .get(org.typelevel.ci.CIString("Retry-After"))
          .flatMap(_.head.value.toIntOption)
        ProviderError.RateLimited(retryAfter)
      case 503 | 504 =>
        ProviderError.Unreachable(detail)
      case 502 =>
        ProviderError.Throttled(detail)
      case _ =>
        ProviderError.Unexpected(s"$codeStr: $detail")

  def urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)

  private def readInboxMessage(json: Json): Option[InboxMessage] =
    for
      id <- str(json, "id")
      from = json.hcursor
        .downField("from")
        .downField("emailAddress")
        .get[String]("address")
        .toOption
        .getOrElse("")
      received = str(json, "receivedDateTime")
        .flatMap(parseIso)
        .getOrElse(Instant.EPOCH.atOffset(ZoneOffset.UTC))
    yield InboxMessage(
      providerMessageId = id,
      providerThreadId = str(json, "conversationId"),
      internetMessageId = str(json, "internetMessageId"),
      fromAddress = from,
      subject = str(json, "subject"),
      receivedAt = received
    )

  def parseIso(value: String): Option[OffsetDateTime] =
    try Some(OffsetDateTime.parse(value))
    catch
      case _: Exception =>
        try Some(Instant.parse(value).atOffset(ZoneOffset.UTC))
        catch case _: Exception => None

/** Minimal query-string reader for @odata.nextLink cursor extraction. */
object UriQuery:
  def parse(raw: String): Map[String, String] =
    raw
      .split("\\?")
      .drop(1)
      .take(1)
      .flatMap(_.split('&'))
      .flatMap { pair =>
        pair.split("=", 2) match
          case Array(k)    => Some(urlDecode(k) -> "")
          case Array(k, v) => Some(urlDecode(k) -> urlDecode(v))
          case _           => None
      }
      .toMap

  private def urlDecode(value: String): String =
    java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8)
