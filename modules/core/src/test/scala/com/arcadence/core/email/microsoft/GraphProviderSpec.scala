package com.arcadence.core.email.microsoft

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.email.{
  AuthorizationResult,
  OutboundMessage,
  ProviderError,
  ReplyMessage
}
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{HttpRoutes, Response}

import java.time.OffsetDateTime

/** Microsoft Graph adapter against a stub identity-platform/Graph server: OAuth
  * code exchange + PKCE, transparent refresh, draft-then-send submission, reply
  * flow, inbox listing with skip-token cursor, typed error mapping (consent
  * failures, missing permissions, throttling) and session revocation (Phase
  * 2.3).
  */
final class GraphProviderSpec extends CatsEffectSuite:

  private val tokenResponse = Json.obj(
    "access_token" -> Json.fromString("msat-1"),
    "refresh_token" -> Json.fromString("msrt-1"),
    "expires_in" -> Json.fromLong(3600L)
  )

  private val calls =
    scala.collection.mutable.ListBuffer.empty[(String, String)]

  override def beforeEach(context: munit.BeforeEach): Unit =
    calls.clear()
    super.beforeEach(context)

  private def jsonResponse(
      status: org.http4s.Status,
      body: Json
  ): Response[IO] =
    Response[IO](status)
      .withEntity(body.noSpaces)(org.http4s.EntityEncoder.stringEncoder[IO])
      .withContentType(
        org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)
      )

  private def stub(
      tokenStatus: org.http4s.Status = org.http4s.Status.Ok,
      tokenBody: Json = tokenResponse,
      meStatus: org.http4s.Status = org.http4s.Status.Ok,
      sendStatus: org.http4s.Status = org.http4s.Status.Accepted,
      retryAfter: Option[String] = None
  ): Client[IO] = {
    val routes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "organizations" / "oauth2" / "v2.0" / "token" =>
        req.bodyText.compile.string.flatMap { form =>
          calls += (("/token", form))
          IO.pure(jsonResponse(tokenStatus, tokenBody))
        }
      case GET -> Root / "me" =>
        calls += (("/me", ""))
        IO.pure(
          jsonResponse(
            meStatus,
            if meStatus.isSuccess then
              Json.obj(
                "mail" -> Json.fromString("sender@contoso.test"),
                "displayName" -> Json.fromString("Contoso Sender"),
                "id" -> Json.fromString("ms-id-1")
              )
            else
              Json.obj(
                "error" -> Json.obj(
                  "code" -> Json.fromString("InvalidAuthenticationToken"),
                  "message" -> Json.fromString("token failure")
                )
              )
          )
        )
      case req @ POST -> Root / "me" / "messages" =>
        req.bodyText.compile.string.flatMap { body =>
          calls += (("/messages-create", body))
          IO.pure(
            jsonResponse(
              org.http4s.Status.Created,
              Json.obj("id" -> Json.fromString("draft-1"))
            )
          )
        }
      case req @ POST -> Root / "me" / "messages" / "draft-1" / "send" =>
        calls += (("/messages-send", ""))
        IO.pure(Response[IO](org.http4s.Status.Accepted))
      case req @ PATCH -> Root / "me" / "messages" / "draft-1" =>
        req.bodyText.compile.string.flatMap { body =>
          calls += (("/messages-patch", body))
          IO.pure(
            jsonResponse(
              org.http4s.Status.Ok,
              Json.obj("id" -> Json.fromString("draft-1"))
            )
          )
        }
      case req @ POST -> Root / "me" / "messages" / "parent-1" / "createReply" =>
        calls += (("/createReply", ""))
        IO.pure(
          jsonResponse(
            org.http4s.Status.Created,
            Json.obj("id" -> Json.fromString("draft-1"))
          )
        )
      case GET -> Root / "me" / "mailFolders" / "inbox" / "messages" =>
        calls += (("/inbox", ""))
        IO.pure(
          jsonResponse(
            org.http4s.Status.Ok,
            Json.obj(
              "value" -> Json.arr(
                Json.obj(
                  "id" -> Json.fromString("im-1"),
                  "conversationId" -> Json.fromString("conv-1"),
                  "subject" -> Json.fromString("Hello"),
                  "from" -> Json.obj(
                    "emailAddress" -> Json.obj(
                      "address" -> Json.fromString("p@e.test")
                    )
                  ),
                  "receivedDateTime" -> Json.fromString("2026-10-13T09:30:00Z")
                )
              ),
              "@odata.nextLink" -> Json.fromString(
                "https://graph.google.test/mailFolders/inbox/messages?%24skiptoken=Tok2"
              )
            )
          )
        )
      case POST -> Root / "me" / "revokeSignInSessions" =>
        calls += (("/revokeSignInSessions", ""))
        retryAfter match
          case None    => IO.pure(Response[IO](org.http4s.Status.NoContent))
          case Some(_) =>
            IO.pure(
              jsonResponse(
                org.http4s.Status.TooManyRequests,
                Json.obj(
                  "error" -> Json.obj(
                    "code" -> Json.fromString("tooManyRequests"),
                    "message" -> Json.fromString("throttled")
                  )
                )
              ).putHeaders(
                org.http4s.headers.`Retry-After`.unsafeFromLong(7)
              )
            )
    }
    Client.fromHttpApp[IO](routes.orNotFound)
  }

  private def provider(client: Client[IO]) =
    new GraphProvider(
      clientId = "ms-cid",
      clientSecret = "ms-csecret",
      redirectUri =
        "http://localhost:8080/api/v1/email-connections/oauth/microsoft/callback",
      endpoints = GraphEndpoints(
        tenant = "organizations",
        graphApiBaseUrl = "https://graph.google.test"
      ),
      client = client
    )

  private def freshCredentials =
    MicrosoftCredentials(
      refreshToken = Some("msrt-stored"),
      accessToken = "msat-stored",
      accessTokenExpiresAt = OffsetDateTime.now.plusHours(1)
    )

  test(
    "authorizationUrl is tenant-aware and carries scopes, PKCE S256 and state"
  ) {
    val url = provider(stub()).authorizationUrl("state-x", "challenge-x")
    assert(
      url.startsWith(
        "https://login.microsoftonline.com/organizations/oauth2/v2.0/authorize?"
      )
    )
    List(
      "client_id=ms-cid",
      "response_mode=query",
      "code_challenge_method=S256",
      "state=state-x",
      java.net.URLEncoder.encode(GraphScopes.MailSend, "UTF-8"),
      java.net.URLEncoder.encode(GraphScopes.MailRead, "UTF-8"),
      "offline_access"
    ).foreach(f => assert(url.contains(f), s"missing $f"))
  }

  test(
    "completeAuthorization exchanges the code with the PKCE verifier and stores credentials"
  ) {
    val buf = scala.collection.mutable.ListBuffer.empty[MicrosoftCredentials]
    val result = provider(stub())
      .completeAuthorization(
        AuthorizationResult("code-1", "http://cb", "s", Some("verifier-1")),
        creds => IO { buf += creds.asInstanceOf[MicrosoftCredentials] }.void
      )
      .unsafeRunSync()
    result match
      case Left(error)     => fail(s"expected identity, got $error")
      case Right(identity) =>
        assertEquals(identity.emailAddress, "sender@contoso.test")
        assertEquals(identity.displayName, Some("Contoso Sender"))
        assertEquals(identity.externalAccountId, Some("ms-id-1"))
    assertEquals(buf.size, 1)
    assertEquals(buf.head.refreshToken, Some("msrt-1"))
    val (_, form) = calls.find(_._1 == "/token").get
    assert(form.contains("code_verifier=verifier-1"))
    assert(form.contains("grant_type=authorization_code"))
  }

  test("consent failures (invalid_grant 400) map to InvalidCredentials") {
    val result = provider(
      stub(
        tokenStatus = org.http4s.Status.BadRequest,
        tokenBody = Json.obj(
          "error" -> Json.obj(
            "code" -> Json.fromString("invalid_grant"),
            "message" -> Json.fromString("AADSTS90003: user declined consent")
          )
        )
      )
    )
      .completeAuthorization(
        AuthorizationResult("c", "r", "s", None),
        _ => IO.unit
      )
      .unsafeRunSync()
    assert(result.left.exists(_.isInstanceOf[ProviderError.InvalidCredentials]))
  }

  test("submit creates a draft then sends it and returns the draft id") {
    val result = provider(stub())
      .submit(
        IO.pure(Some(freshCredentials)),
        OutboundMessage(
          from = "sender@contoso.test",
          to = List("dest@example.test"),
          subject = "Connection test",
          textBody = "Hello from Arcadence",
          htmlBody = None,
          headers = Map.empty
        )
      )
      .unsafeRunSync()
    assertEquals(result.map(_.value), Right("draft-1"))
    val (_, draftBody) = calls.find(_._1 == "/messages-create").get
    val json = parse(draftBody).toOption.get
    assertEquals(
      json.hcursor.downField("subject").as[String].toOption,
      Some("Connection test")
    )
    assertEquals(
      json.hcursor.downField("body").get[String]("contentType").toOption,
      Some("Text")
    )
    assert(calls.exists(_._1 == "/messages-send"))
  }

  test(
    "submit transparently refreshes an expired access token before drafting"
  ) {
    val expired = freshCredentials.copy(
      accessToken = "msat-expired",
      accessTokenExpiresAt = OffsetDateTime.now.minusMinutes(5)
    )
    val result = provider(stub())
      .submit(
        IO.pure(Some(expired)),
        OutboundMessage("s@c.test", List("d@e.test"), "s", "b", None, Map.empty)
      )
      .unsafeRunSync()
    assertEquals(result.map(_.value), Right("draft-1"))
    val (_, form) = calls.find(_._1 == "/token").get
    assert(form.contains("grant_type=refresh_token"))
    assert(form.contains("refresh_token=msrt-stored"))
  }

  test("submitReply creates a reply draft, patches the body, then sends") {
    val result = provider(stub())
      .submitReply(
        IO.pure(Some(freshCredentials)),
        ReplyMessage(
          providerThreadId = "conv-1",
          inReplyToMessageId = "parent-1",
          from = "sender@contoso.test",
          to = List("p@e.test"),
          subject = "RE: Hello",
          textBody = "Our reply",
          htmlBody = None
        )
      )
      .unsafeRunSync()
    assertEquals(result.map(_.value), Right("draft-1"))
    assert(calls.exists(_._1 == "/createReply"))
    val (_, patched) = calls.find(_._1 == "/messages-patch").get
    assert(patched.contains("Our reply"))
    assert(calls.exists(_._1 == "/messages-send"))
  }

  test("syncInbox maps Graph messages and extracts the skiptoken cursor") {
    val result = provider(stub())
      .syncInbox(IO.pure(Some(freshCredentials)), None)
      .unsafeRunSync()
    result match
      case Left(error)  => fail(s"expected batch, got $error")
      case Right(batch) =>
        assertEquals(batch.messages.map(_.providerMessageId), List("im-1"))
        assertEquals(batch.messages.head.fromAddress, "p@e.test")
        assertEquals(batch.nextCursor, Some("Tok2"))
  }

  test(
    "missing permissions (403) map to PermissionDenied; throttling (429) carries Retry-After"
  ) {
    provider(
      stub(meStatus = org.http4s.Status.Forbidden)
    )
      .validateConnection(IO.pure(Some(freshCredentials)))
      .unsafeRunSync()
      .left
      .foreach(e =>
        assert(e.isInstanceOf[ProviderError.PermissionDenied], s"got $e")
      )

    provider(stub(retryAfter = Some("7")))
      .revokeAccess(IO.pure(Some(freshCredentials)))
      .unsafeRunSync()
      .left
      .foreach {
        case ProviderError.RateLimited(Some(7)) => assert(true)
        case other => fail(s"expected RateLimited(Some(7)), got $other")
      }
  }

  test("refresh keeps the stored refresh token and updates the access token") {
    val buf = scala.collection.mutable.ListBuffer.empty[MicrosoftCredentials]
    val result = provider(stub())
      .refresh(
        IO.pure(Some(freshCredentials)),
        c => IO { buf += c.asInstanceOf[MicrosoftCredentials] }.void
      )
      .unsafeRunSync()
    assertEquals(result, Right(()))
    assertEquals(buf.head.accessToken, "msat-1")
    assertEquals(buf.head.refreshToken, Some("msrt-stored"))
  }

  test(
    "revokeAccess calls revokeSignInSessions for connected accounts, no-op otherwise"
  ) {
    val result = provider(stub())
      .revokeAccess(IO.pure(Some(freshCredentials)))
      .unsafeRunSync()
    assertEquals(result, Right(()))
    assert(calls.exists(_._1 == "/revokeSignInSessions"))
    assertEquals(
      provider(stub()).revokeAccess(IO.pure(None)).unsafeRunSync(),
      Right(())
    )
  }
