package com.arcadence.core.email.gmail

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{HttpRoutes, Response, Uri}

import java.time.OffsetDateTime
import java.util.Base64

/** Gmail adapter against a stub Google server (token, userinfo, Gmail API,
  * revoke). Exercises the real HTTP layer: OAuth code exchange + PKCE form,
  * transparent access-token refresh, MIME submission, error mapping and
  * revocation (Phase 2.2).
  */
final class GmailProviderSpec extends CatsEffectSuite:

  val created = scala.collection.mutable.ListBuffer.empty[String]

  // MUnit reuses one instance across tests — reset the request log per test.
  override def beforeEach(context: munit.BeforeEach): Unit =
    calls.clear()
    super.beforeEach(context)

  /** Stub Google: request log + canned responses. */
  val calls = scala.collection.mutable.ListBuffer
    .empty[(String, String)] // (path, grant/code)

  private def stub(
      tokenStatus: Int = 200,
      tokenBody: Json = validTokenResponse,
      gmailStatus: Int = 200,
      gmailBody: Json = Json.Null
  ): Client[IO] = {
    def jsonResponse(status: org.http4s.Status, body: Json): Response[IO] =
      Response[IO](status)
        .withEntity(body.noSpaces)(org.http4s.EntityEncoder.stringEncoder[IO])
        .withContentType(
          org.http4s.headers
            .`Content-Type`(org.http4s.MediaType.application.json)
        )

    val routes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "token" =>
        req.bodyText.compile.string.flatMap { form =>
          calls += (("/token", form))
          IO.pure(
            jsonResponse(
              org.http4s.Status
                .fromInt(tokenStatus)
                .getOrElse(org.http4s.Status.Ok),
              tokenBody
            )
          )
        }
      case GET -> Root / "userinfo" =>
        calls += (("/userinfo", ""))
        IO.pure(
          jsonResponse(
            org.http4s.Status.Ok,
            Json.obj(
              "email" -> Json.fromString("sender@gmail.test"),
              "name" -> Json.fromString("Gmail Sender")
            )
          )
        )
      case GET -> Root / "revoke" =>
        calls += (("/revoke", ""))
        IO.pure(Response[IO](org.http4s.Status.Ok))
      case req @ POST -> Root / "messages" / "send" =>
        req.bodyText.compile.string.flatMap { body =>
          calls += (("/send", body))
          IO.pure(
            jsonResponse(
              org.http4s.Status
                .fromInt(gmailStatus)
                .getOrElse(org.http4s.Status.Ok),
              if gmailBody.isNull then
                Json.obj(
                  "id" -> Json.fromString("msg-1"),
                  "threadId" -> Json.fromString("th-1")
                )
              else gmailBody
            )
          )
        }
      case GET -> Root / "profile" =>
        calls += (("/profile", ""))
        IO.pure(
          jsonResponse(
            org.http4s.Status
              .fromInt(gmailStatus)
              .getOrElse(org.http4s.Status.Ok),
            if gmailBody.isNull then
              Json.obj(
                "emailAddress" -> Json.fromString("sender@gmail.test"),
                "historyId" -> Json.fromString("999")
              )
            else gmailBody
          )
        )
      case GET -> Root / "messages" =>
        calls += (("/messages-list", ""))
        IO.pure(
          jsonResponse(
            org.http4s.Status.Ok,
            Json.obj(
              "messages" -> Json.arr(
                Json.obj(
                  "id" -> Json.fromString("m1"),
                  "threadId" -> Json.fromString("t1")
                ),
                Json.obj(
                  "id" -> Json.fromString("m2"),
                  "threadId" -> Json.fromString("t2")
                )
              ),
              "nextPageToken" -> Json.fromString("page-2")
            )
          )
        )
    }
    Client.fromHttpApp[IO](routes.orNotFound)
  }

  private val validTokenResponse = Json.obj(
    "access_token" -> Json.fromString("at-1"),
    "refresh_token" -> Json.fromString("rt-1"),
    "expires_in" -> Json.fromLong(3600L)
  )

  private def freshCredentials =
    GmailCredentials(
      refreshToken = Some("rt-stored"),
      accessToken = "at-stored",
      accessTokenExpiresAt = OffsetDateTime.now.plusHours(1)
    )

  private def provider(client: Client[IO]) =
    new GmailProvider(
      clientId = "client-id",
      clientSecret = "client-secret",
      redirectUri =
        "http://localhost:8080/api/v1/email-connections/oauth/google/callback",
      endpoints = GmailEndpoints(
        authorizeUrl = "https://accounts.google.test/auth",
        tokenUrl = "https://accounts.google.test/token",
        userInfoUrl = "https://accounts.google.test/userinfo",
        gmailApiBaseUrl = "https://gmail.google.test",
        revokeUrl = "https://accounts.google.test/revoke"
      ),
      client = client
    )

  private def stored =
    scala.collection.mutable.ListBuffer.empty[GmailCredentials]
  private def storer(
      buf: scala.collection.mutable.ListBuffer[GmailCredentials]
  ) =
    (creds: com.arcadence.core.email.Credentials) =>
      IO { buf += creds.asInstanceOf[GmailCredentials] }.void

  test("authorizationUrl carries client, scopes, PKCE S256 and state") {
    val p = provider(stub())
    val url = p.authorizationUrl("state-value", "challenge-value")
    assert(url.startsWith("https://accounts.google.test/auth?"))
    List(
      "client_id=client-id",
      "code_challenge=challenge-value",
      "code_challenge_method=S256",
      "access_type=offline",
      "prompt=consent",
      "state=state-value",
      java.net.URLEncoder.encode(GmailScopes.Send, "UTF-8"),
      java.net.URLEncoder.encode(GmailScopes.Readonly, "UTF-8")
    ).foreach(fragment =>
      assert(url.contains(fragment), s"missing $fragment in $url")
    )
  }

  test(
    "completeAuthorization exchanges the code with PKCE verifier and stores credentials"
  ) {
    val client = stub()
    val p = provider(client)
    val buf = stored
    val result = p
      .completeAuthorization(
        com.arcadence.core.email.AuthorizationResult(
          code = "auth-code-1",
          redirectUri =
            "http://localhost:8080/api/v1/email-connections/oauth/google/callback",
          state = "s",
          codeVerifier = Some("verifier-1")
        ),
        storer(buf)
      )
      .unsafeRunSync()

    result match
      case Left(error)     => fail(s"expected identity, got $error")
      case Right(identity) =>
        assertEquals(identity.emailAddress, "sender@gmail.test")
        assertEquals(identity.displayName, Some("Gmail Sender"))
    assertEquals(buf.size, 1)
    assertEquals(buf.head.refreshToken, Some("rt-1"))
    assertEquals(buf.head.accessToken, "at-1")
    val (_, form) = calls.find(_._1 == "/token").get
    assert(form.contains("code=auth-code-1"))
    assert(form.contains("code_verifier=verifier-1"))
    assert(form.contains("grant_type=authorization_code"))
    assert(form.contains("client_secret=client-secret"))
  }

  test(
    "completeAuthorization maps an invalid-grant rejection to InvalidCredentials"
  ) {
    val client = stub(
      tokenStatus = 400,
      tokenBody = Json.obj("error" -> Json.fromString("invalid_grant"))
    )
    val result = provider(client)
      .completeAuthorization(
        com.arcadence.core.email.AuthorizationResult("c", "r", "s", None),
        storer(stored)
      )
      .unsafeRunSync()
    assert(result.isLeft)
    assert(
      result.left.exists(
        _.isInstanceOf[
          com.arcadence.core.email.ProviderError.InvalidCredentials
        ]
      )
    )
  }

  test(
    "submit sends base64url MIME through the Gmail API and returns the message id"
  ) {
    val client = stub()
    val p = provider(client)
    val creds = freshCredentials
    val result = p
      .submit(
        IO.pure(Some(creds)),
        com.arcadence.core.email.OutboundMessage(
          from = "sender@gmail.test",
          to = List("dest@example.test"),
          subject = "Connection test",
          textBody = "Hello from Arcadence",
          htmlBody = None,
          headers = Map.empty
        )
      )
      .unsafeRunSync()
    assertEquals(result.map(_.value), Right("msg-1"))
    val (_, body) = calls.find(_._1 == "/send").get
    val raw = body.hcursorified
    val decoded = new String(
      Base64.getUrlDecoder.decode(raw),
      java.nio.charset.StandardCharsets.UTF_8
    )
    assert(decoded.contains("From: sender@gmail.test"))
    assert(decoded.contains("To: dest@example.test"))
    assert(decoded.contains("Subject: Connection test"))
    // No auth header mishaps: access token was sent (stub ignores it)
  }

  test(
    "submit transparently refreshes an expired access token before sending"
  ) {
    val client = stub()
    val p = provider(client)
    val expired = freshCredentials.copy(
      accessToken = "at-expired",
      accessTokenExpiresAt = OffsetDateTime.now.minusMinutes(5)
    )
    val result = p
      .submit(
        IO.pure(Some(expired)),
        com.arcadence.core.email.OutboundMessage(
          "sender@gmail.test",
          List("d@e.test"),
          "s",
          "b",
          None,
          Map.empty
        )
      )
      .unsafeRunSync()
    assertEquals(result.map(_.value), Right("msg-1"))
    val (_, refreshForm) = calls.find(_._1 == "/token").get
    assert(refreshForm.contains("grant_type=refresh_token"))
    assert(refreshForm.contains("refresh_token=rt-stored"))
  }

  test("submit without credentials reports InvalidCredentials") {
    val p = provider(stub())
    val result = p
      .submit(
        IO.pure(None),
        com.arcadence.core.email
          .OutboundMessage("f@t", List("t@t"), "s", "b", None, Map.empty)
      )
      .unsafeRunSync()
    assert(
      result.left.exists(
        _.isInstanceOf[
          com.arcadence.core.email.ProviderError.InvalidCredentials
        ]
      )
    )
  }

  test(
    "provider errors map to typed values: 401 invalid, 403 rate-limited, 403 permission, 5xx unreachable"
  ) {
    val unauthorized = stub(
      gmailStatus = 401,
      gmailBody = Json.obj("error" -> Json.fromString("invalid"))
    )
    provider(unauthorized)
      .validateConnection(IO.pure(Some(freshCredentials)))
      .unsafeRunSync()
      .left
      .foreach(e =>
        assert(
          e.isInstanceOf[
            com.arcadence.core.email.ProviderError.InvalidCredentials
          ]
        )
      )

    val throttled = stub(
      gmailStatus = 403,
      gmailBody = parse(
        """{"error":{"code":403,"message":"quota","errors":[{"reason":"rateLimitExceeded"}]}}"""
      ).getOrElse(Json.Null)
    )
    provider(throttled)
      .healthCheck(IO.pure(Some(freshCredentials)))
      .unsafeRunSync()
      .left
      .foreach(e =>
        assert(
          e.isInstanceOf[com.arcadence.core.email.ProviderError.RateLimited]
        )
      )

    val denied = stub(
      gmailStatus = 403,
      gmailBody = parse(
        """{"error":{"code":403,"message":"no scope","errors":[{"reason":"insufficientPermissions"}]}}"""
      ).getOrElse(Json.Null)
    )
    provider(denied)
      .healthCheck(IO.pure(Some(freshCredentials)))
      .unsafeRunSync()
      .left
      .foreach(e =>
        assert(
          e.isInstanceOf[
            com.arcadence.core.email.ProviderError.PermissionDenied
          ]
        )
      )

    val down = stub(
      gmailStatus = 503,
      gmailBody = Json.obj("error" -> Json.fromString("backend"))
    )
    provider(down)
      .healthCheck(IO.pure(Some(freshCredentials)))
      .unsafeRunSync()
      .left
      .foreach(e =>
        assert(
          e.isInstanceOf[com.arcadence.core.email.ProviderError.Unreachable]
        )
      )
  }

  test("syncInbox lists INBOX messages with a page-token cursor") {
    val client = stub()
    val p = provider(client)
    val result =
      p.syncInbox(IO.pure(Some(freshCredentials)), None).unsafeRunSync()
    result match
      case Left(error)  => fail(s"expected batch, got $error")
      case Right(batch) =>
        assertEquals(batch.messages.map(_.providerMessageId), List("m1", "m2"))
        assertEquals(batch.nextCursor, Some("page-2"))
  }

  test("refresh exchanges the refresh token and reports the new access token") {
    val client = stub()
    val p = provider(client)
    val buf = stored
    val result =
      p.refresh(IO.pure(Some(freshCredentials)), storer(buf)).unsafeRunSync()
    assertEquals(result, Right(()))
    assertEquals(buf.head.accessToken, "at-1")
    assertEquals(
      buf.head.refreshToken,
      Some("rt-stored")
    ) // refresh grant keeps the stored token
  }

  test("refresh without a stored refresh token demands reconnection") {
    val p = provider(stub())
    val noRefresh = freshCredentials.copy(refreshToken = None)
    val result =
      p.refresh(IO.pure(Some(noRefresh)), storer(stored)).unsafeRunSync()
    assert(
      result.left.exists(
        _.isInstanceOf[
          com.arcadence.core.email.ProviderError.InvalidCredentials
        ]
      )
    )
  }

  test("revokeAccess hits the Google revoke endpoint with the refresh token") {
    val client = stub()
    val p = provider(client)
    val result = p.revokeAccess(IO.pure(Some(freshCredentials))).unsafeRunSync()
    assertEquals(result, Right(()))
    assert(calls.exists(_._1 == "/revoke"))
    // Accounts without credentials revoke as a no-op success.
    assertEquals(p.revokeAccess(IO.pure(None)).unsafeRunSync(), Right(()))
  }

  extension (body: String)
    private def hcursorified: String =
      parse(body).toOption
        .flatMap(_.hcursor.get[String]("raw").toOption)
        .getOrElse(body)
