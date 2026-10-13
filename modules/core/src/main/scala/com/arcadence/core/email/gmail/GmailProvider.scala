package com.arcadence.core.email.gmail

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
import fs2.text
import io.circe.Json
import io.circe.parser.parse
import org.http4s.{Method, Request, UrlForm}
import org.http4s.client.Client
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.typelevel.ci.CIString

import java.time.{
  DateTimeException,
  Instant,
  OffsetDateTime,
  ZoneOffset,
  format as jtimeformat
}
import scala.concurrent.duration.*

/** Gmail adapter over Google's OAuth 2.0 and Gmail REST APIs (Phase 2.2).
  *
  * Scopes: gmail.send + gmail.readonly + userinfo.email (minimum necessary;
  * both Gmail scopes are RESTRICTED — see ADR-0021). Offline access yields a
  * refresh token; PKCE (S256) is used on the authorization-code flow.
  *
  * All Google endpoints are injectable via [[GmailEndpoints]] so tests run the
  * full flows against a stub server. Provider failures are values ([[
  * ProviderError]]); responses never contain tokens or credentials.
  */
final class GmailProvider(
    clientId: String,
    clientSecret: String,
    redirectUri: String,
    endpoints: GmailEndpoints,
    client: Client[IO]
) extends EmailProvider:

  import GmailProvider.*

  val id: EmailProvider.Id = EmailProvider.Gmail

  val capabilities: ProviderCapabilities =
    ProviderCapabilities.forProvider(EmailProvider.Gmail).get

  /** Consent screen URL for the authorization-code + PKCE flow. */
  def authorizationUrl(state: String, codeChallenge: String): String =
    val q = Map(
      "client_id" -> clientId,
      "redirect_uri" -> redirectUri,
      "response_type" -> "code",
      "scope" -> GmailScopes.All.mkString(" "),
      "access_type" -> "offline",
      "prompt" -> "consent",
      "include_granted_scopes" -> "true",
      "state" -> state,
      "code_challenge" -> codeChallenge,
      "code_challenge_method" -> "S256"
    )
    val query = q.map((k, v) => s"$k=${urlEncode(v)}").mkString("&")
    s"${endpoints.authorizeUrl}?$query"

  /** Exchanges the authorization code for tokens, stores credentials, and
    * resolves the mailbox identity from Google's userinfo endpoint.
    */
  def completeAuthorization(
      authorization: AuthorizationResult,
      store: Credentials => IO[Unit]
  ): IO[Either[ProviderError, AccountIdentity]] =
    val form = UrlForm(
      Seq(
        "code" -> authorization.code,
        "client_id" -> clientId,
        "client_secret" -> clientSecret,
        "redirect_uri" -> authorization.redirectUri,
        "grant_type" -> "authorization_code"
      ) ++
        authorization.codeVerifier.map(v => "code_verifier" -> v).toList: _*
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
              val creds = GmailCredentials(
                refreshToken = refresh,
                accessToken = access,
                accessTokenExpiresAt =
                  OffsetDateTime.now.plusSeconds(math.max(1, expiresIn))
              )
              store(creds).as(Right(creds))
      identity <- creds match
        case Left(error)       => IO.pure(Left(error))
        case Right(validCreds) =>
          userInfo(validCreds.accessToken).map(_.map { case (email, name) =>
            AccountIdentity(
              emailAddress = email,
              displayName = Some(name).filter(_.nonEmpty),
              externalAccountId = None
            )
          })
    yield identity

  def validateConnection(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, AccountIdentity]] =
    withFresh(load).flatMap {
      case Left(error)  => IO.pure(Left(error))
      case Right(creds) =>
        getJson(s"${endpoints.gmailApiBaseUrl}/profile", creds).map(_.map {
          json =>
            val email = str(json, "emailAddress").getOrElse("")
            val historyId = str(json, "historyId")
            AccountIdentity(email, None, historyId)
        })
    }

  def submit(
      load: IO[Option[Credentials]],
      message: OutboundMessage
  ): IO[Either[ProviderError, ProviderMessageId]] =
    sendRaw(
      load,
      GmailMime.Part(
        from = message.from,
        to = message.to,
        subject = message.subject,
        textBody = message.textBody,
        htmlBody = message.htmlBody,
        extraHeaders = message.headers
      ),
      threadId = None
    )

  def submitReply(
      load: IO[Option[Credentials]],
      reply: ReplyMessage
  ): IO[Either[ProviderError, ProviderMessageId]] =
    sendRaw(
      load,
      GmailMime.Part(
        from = reply.from,
        to = reply.to,
        subject = reply.subject,
        textBody = reply.textBody,
        htmlBody = reply.htmlBody,
        extraHeaders = Map(
          "In-Reply-To" -> reply.inReplyToMessageId,
          "References" -> reply.inReplyToMessageId
        )
      ),
      threadId = Some(reply.providerThreadId)
    )

  /** Inbox listing with the page token as cursor. Per-message metadata
    * (headers) follows; the full incremental sync engine arrives in Phase 6.
    */
  def syncInbox(
      load: IO[Option[Credentials]],
      cursor: Option[String]
  ): IO[Either[ProviderError, InboxBatch]] =
    withFresh(load).flatMap {
      case Left(error)  => IO.pure(Left(error))
      case Right(creds) =>
        val params = List(
          "labelIds" -> "INBOX",
          "maxResults" -> "25"
        ) ++ cursor.map(token => "pageToken" -> token)
        val query = params.map((k, v) => s"$k=${urlEncode(v)}").mkString("&")
        getJson(s"${endpoints.gmailApiBaseUrl}/messages?$query", creds)
          .flatMap {
            case Left(error) => IO.pure(Left(error))
            case Right(json) =>
              val refs =
                json.hcursor.downField("messages").as[List[Json]].getOrElse(Nil)
              val ids = refs.flatMap(_.hcursor.get[String]("id").toOption)
              val nextToken = str(json, "nextPageToken")
              ids.toList
                .traverse(fetchInboxMessage(creds, _))
                .map(messages => Right(InboxBatch(messages, nextToken)))
          }
    }

  def healthCheck(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, HealthSnapshot]] =
    withFresh(load).flatMap {
      case Left(error)  => IO.pure(Left(error))
      case Right(creds) =>
        getJson(s"${endpoints.gmailApiBaseUrl}/profile", creds).map(
          _.map(json =>
            HealthSnapshot(
              reachable = true,
              quotaRemaining = None, // Gmail exposes no quota counter
              checkedAt = OffsetDateTime.now
            )
          )
        )
    }

  /** Forces a refresh-token grant (used by connection tests and the worker). */
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
      case Some(creds: GmailCredentials) =>
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
              "stored credentials are not Gmail credentials"
            )
          )
        )
    }

  def revokeAccess(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, Unit]] =
    load.flatMap {
      case None => IO.pure(Right(())) // nothing to revoke
      case Some(creds: GmailCredentials) =>
        creds.refreshToken match
          case None        => IO.pure(Right(()))
          case Some(token) =>
            val uri = org.http4s.Uri
              .unsafeFromString(
                s"${endpoints.revokeUrl}?token=${urlEncode(token)}"
              )
            execute(Request[IO](Method.GET, uri)).map(_.map(_ => ()))
      case Some(_) =>
        IO.pure(
          Left(
            ProviderError.Unexpected(
              "stored credentials are not Gmail credentials"
            )
          )
        )
    }

  // -- internals -------------------------------------------------------------

  private def sendRaw(
      load: IO[Option[Credentials]],
      part: GmailMime.Part,
      threadId: Option[String]
  ): IO[Either[ProviderError, ProviderMessageId]] =
    withFresh(load).flatMap {
      case Left(error)  => IO.pure(Left(error))
      case Right(creds) =>
        val body = Json.fromJsonObject(
          Json
            .obj("raw" -> Json.fromString(GmailMime.toGmailRaw(part)))
            .asObject
            .get
            .add("threadId", threadId.map(Json.fromString).getOrElse(Json.Null))
        )
        postJson(s"${endpoints.gmailApiBaseUrl}/messages/send", body, creds)
          .map(_.map(json => ProviderMessageId(str(json, "id").getOrElse(""))))
    }

  private def fetchInboxMessage(
      creds: GmailCredentials,
      messageId: String
  ): IO[InboxMessage] =
    getJson(
      s"${endpoints.gmailApiBaseUrl}/messages/${urlEncode(messageId)}?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date",
      creds
    ).map {
      case Left(_) =>
        InboxMessage(messageId, Some(messageId), None, "", None, epoch())
      case Right(json) =>
        val threadId = str(json, "threadId")
        val headers = json.hcursor
          .downField("payload")
          .downField("headers")
          .as[List[Json]]
          .getOrElse(Nil)
          .flatMap(h =>
            for
              name <- h.hcursor.get[String]("name").toOption
              value <- h.hcursor.get[String]("value").toOption
            yield name.toLowerCase -> value
          )
          .toMap
        val internalDate = str(json, "internalDate")
          .flatMap(v =>
            v.toLongOption.map(ms =>
              Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC)
            )
          )
        InboxMessage(
          providerMessageId = messageId,
          providerThreadId = threadId,
          internetMessageId = None,
          fromAddress = headers.getOrElse("from", ""),
          subject = headers.get("subject"),
          receivedAt = internalDate
            .orElse(headers.get("date").flatMap(parseRfc1123))
            .getOrElse(epoch())
        )
    }

  /** Ensures the access token is fresh (>= 60 s left), refreshing+storing when
    * not.
    */
  private def withFresh(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, GmailCredentials]] =
    load.flatMap {
      case None =>
        IO.pure(
          Left(
            ProviderError.InvalidCredentials(
              "account has no stored credentials"
            )
          )
        )
      case Some(creds: GmailCredentials) =>
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
              // Refreshed access tokens live in memory only: the refresh
              // token is the durable secret, and Google allows multiple
              // outstanding access tokens, so re-refreshing is safe.
              refreshAccess(token).map {
                case Left(error)                => Left(error)
                case Right((access, expiresIn)) =>
                  Right(creds.withRefreshedAccess(access, expiresIn))
              }
      case Some(_) =>
        IO.pure(
          Left(
            ProviderError.Unexpected(
              "stored credentials are not Gmail credentials"
            )
          )
        )
    }

  private def userInfo(
      accessToken: String
  ): IO[Either[ProviderError, (String, String)]] =
    val req = Request[IO](
      Method.GET,
      org.http4s.Uri.unsafeFromString(endpoints.userInfoUrl)
    )
      .withHeaders(
        org.http4s.headers.Authorization(
          org.http4s.Credentials
            .Token(org.http4s.AuthScheme.Bearer, accessToken)
        )
      )
    execute(req).map(
      _.map(json =>
        (str(json, "email").getOrElse(""), str(json, "name").getOrElse(""))
      )
    )

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
        val access = str(json, "access_token")
        val expiresIn = json.hcursor.get[Long]("expires_in").getOrElse(3600L)
        access match
          case None =>
            IO.pure(
              Left(
                ProviderError.InvalidCredentials(
                  "refresh grant returned no access token"
                )
              )
            )
          case Some(a) => IO.pure(Right((a, expiresIn)))
    }

  private def decodeTokenResponse(
      json: Json
  ): Either[String, (Option[String], String, Long)] =
    for
      access <- str(json, "access_token").toRight("access_token missing")
      refresh = str(json, "refresh_token")
      expiresIn = json.hcursor.get[Long]("expires_in").getOrElse(3600L)
    yield (refresh, access, expiresIn)

  private def postForm(
      url: String,
      form: UrlForm
  ): IO[Either[ProviderError, Json]] =
    val uri = org.http4s.Uri.unsafeFromString(url)
    val req = Request[IO](Method.POST, uri).withEntity(form)
    execute(req)

  private def postJson(
      url: String,
      body: Json,
      creds: GmailCredentials
  ): IO[Either[ProviderError, Json]] =
    val uri = org.http4s.Uri.unsafeFromString(url)
    val req = Request[IO](Method.POST, uri)
      .withEntity(body)
      .withHeaders(authHeader(creds))
    execute(req)

  private def getJson(
      url: String,
      creds: GmailCredentials
  ): IO[Either[ProviderError, Json]] =
    val uri = org.http4s.Uri.unsafeFromString(url)
    val req = Request[IO](Method.GET, uri).withHeaders(authHeader(creds))
    execute(req)

  private def authHeader(creds: GmailCredentials) =
    org.http4s.headers.Authorization(
      org.http4s.Credentials
        .Token(org.http4s.AuthScheme.Bearer, creds.accessToken)
    )

  /** Single HTTP execution with Google error mapping — never throws. */
  private def execute(request: Request[IO]): IO[Either[ProviderError, Json]] =
    client
      .run(request)
      .use(res =>
        res.body.through(text.utf8.decode).compile.string.map { bodyText =>
          val json = parse(bodyText).toOption.getOrElse(Json.Null)
          res.status.code match
            case code if code == 200 || code == 201 => Right(json)
            case 400 if googleReason(json).exists(r => r.contains("invalid")) =>
              // OAuth token endpoint: invalid_grant / invalid_client (400)
              Left(ProviderError.InvalidCredentials(detail(json, bodyText)))
            case 401 =>
              Left(ProviderError.InvalidCredentials(detail(json, bodyText)))
            case 403 =>
              val reason = googleReason(json)
              reason match
                case Some(r)
                    if r.contains("rateLimit") || r.contains("quota") =>
                  Left(ProviderError.RateLimited(retryAfterSeconds(res)))
                case Some(r)
                    if r.contains("insufficientPermissions") || r
                      .contains("forbidden") =>
                  Left(ProviderError.PermissionDenied(detail(json, bodyText)))
                case _ => Left(ProviderError.Throttled(detail(json, bodyText)))
            case 429 =>
              Left(ProviderError.RateLimited(retryAfterSeconds(res)))
            case code if code >= 500 =>
              Left(ProviderError.Unreachable(detail(json, bodyText)))
            case _ =>
              Left(ProviderError.Unexpected(detail(json, bodyText)))
        }
      )
      .handleError(t =>
        Left(
          ProviderError.Unreachable(
            Option(t.getMessage).getOrElse(t.getClass.getSimpleName)
          )
        )
      )

object GmailProvider:

  def str(json: Json, field: String): Option[String] =
    json.hcursor.get[String](field).toOption

  def googleReason(json: Json): Option[String] =
    json.hcursor
      .downField("error")
      .downField("errors")
      .as[List[Json]]
      .toOption match
      case Some(errors) =>
        errors.flatMap(_.hcursor.get[String]("reason").toOption).headOption
      case None =>
        json.hcursor.downField("error").as[String].toOption // OAuth-style error

  def detail(json: Json, fallback: String): String =
    val oauthDescription = str(json, "error_description")
    val gmailMessage =
      json.hcursor.downField("error").get[String]("message").toOption
    oauthDescription
      .orElse(gmailMessage)
      .orElse(str(json, "error"))
      .map(m => s"$m (HTTP)") // no tokens in messages
      .getOrElse(fallback.take(200))

  def retryAfterSeconds(response: org.http4s.Response[IO]): Option[Int] =
    response.headers
      .get(CIString("Retry-After"))
      .flatMap(_.head.value.toIntOption)

  def urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)

  def parseRfc1123(value: String): Option[OffsetDateTime] =
    try
      Some(
        OffsetDateTime.parse(
          value,
          jtimeformat.DateTimeFormatter.RFC_1123_DATE_TIME
        )
      )
    catch case _: DateTimeException | _: IllegalArgumentException => None

  def epoch(): OffsetDateTime = Instant.EPOCH.atOffset(ZoneOffset.UTC)
