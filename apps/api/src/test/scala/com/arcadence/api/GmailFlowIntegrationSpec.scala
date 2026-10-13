package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.crypto.{CredentialEncryptor, OAuthStateSigner}
import com.arcadence.core.email.gmail.{GmailEndpoints, GmailProvider}
import com.arcadence.core.db.DbUrl
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{Header, HttpRoutes, Method, Request, Response, Status, Uri}

import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** Gmail connect flow end-to-end against a stub Google server and the real
  * database (Phase 2.2): state-signed start, callback validation (tamper,
  * expiry, consent denial), account + encrypted-credential persistence,
  * reconnection and the authorized test send.
  *
  * Runs against a real database — set TEST_DATABASE_URL (local stack:
  * postgresql://postgres:postgres@127.0.0.1:54322/postgres).
  */
final class GmailFlowIntegrationSpec extends CatsEffectSuite:

  private val dbUrl = sys.env.getOrElse("TEST_DATABASE_URL", "")
  private lazy val jdbc =
    DbUrl.parse(dbUrl).fold(_ => sys.error("bad TEST_DATABASE_URL"), identity)
  private lazy val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    jdbc.jdbcUrl,
    jdbc.user,
    jdbc.password,
    logHandler = None
  )

  // -- stub Google ------------------------------------------------------------

  private val stubToken = Json.obj(
    "access_token" -> Json.fromString("at-flow"),
    "refresh_token" -> Json.fromString("rt-flow"),
    "expires_in" -> Json.fromLong(3600L)
  )

  private def stubClient(sendId: String = "msg-flow-1"): Client[IO] = {
    def json(status: Status, body: Json): Response[IO] =
      Response[IO](status)
        .withEntity(body.noSpaces)(org.http4s.EntityEncoder.stringEncoder[IO])
        .withContentType(
          org.http4s.headers
            .`Content-Type`(org.http4s.MediaType.application.json)
        )
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "token" =>
        IO.pure(json(Status.Ok, stubToken))
      case GET -> Root / "userinfo" =>
        IO.pure(
          json(
            Status.Ok,
            Json.obj(
              "email" -> Json.fromString("flow@gmail.test"),
              "name" -> Json.fromString("Flow Sender")
            )
          )
        )
      case POST -> Root / "messages" / "send" =>
        IO.pure(json(Status.Ok, Json.obj("id" -> Json.fromString(sendId))))
      case GET -> Root / "revoke" =>
        IO.pure(Response[IO](Status.Ok))
    }
    Client.fromHttpApp[IO](routes.orNotFound)
  }

  private val stubEndpoints = GmailEndpoints(
    authorizeUrl = "https://accounts.google.test/auth",
    tokenUrl = "https://accounts.google.test/token",
    userInfoUrl = "https://accounts.google.test/userinfo",
    gmailApiBaseUrl = "https://gmail.google.test",
    revokeUrl = "https://accounts.google.test/revoke"
  )

  // -- registry + routes --------------------------------------------------------

  private val keyBytes =
    CredentialEncryptor
      .decodeKey(Base64.getEncoder.encodeToString(Array.fill(32)(9.toByte)))
      .fold(
        sys.error(_),
        identity
      )
  private lazy val registry = {
    val r = new ProviderRegistry(
      encryptor = new CredentialEncryptor(keyBytes),
      stateSigner =
        new OAuthStateSigner(ProviderRegistry.deriveStateSecret(keyBytes)),
      appOrigin = "http://localhost:3000",
      redirectUri =
        "http://localhost:8080/api/v1/email-connections/oauth/google/callback"
    )
    r.registerGmail(
      new GmailProvider(
        "cid",
        "csecret",
        r.redirectUri,
        stubEndpoints,
        stubClient()
      )
    )
    r
  }

  private val userA = UUID.randomUUID() // workspace owner
  private val userB = UUID.randomUUID() // viewer
  private val userC = UUID.randomUUID() // outsider owner
  private val emailA =
    s"it-2.2-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailB =
    s"it-2.2-b-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailC =
    s"it-2.2-c-${UUID.randomUUID().toString.take(8)}@arcadence.test"

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, emailA),
    "token-B" -> AuthenticatedUser(userB, emailB),
    "token-C" -> AuthenticatedUser(userC, emailC)
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val repos = new EmailAccountRepos(xa)
  private lazy val app = (
    new GmailRoutes(
      authenticator = fakeAuth,
      repos = repos,
      authorizer = new Authorizer(xa),
      registry = registry
    ).http
      <+> new EmailAccountRoutes(
        authenticator = fakeAuth,
        repos = repos,
        authorizer = new Authorizer(xa),
        registry = Some(registry)
      ).http
      <+> new WorkspaceRoutes(
        authenticator = fakeAuth,
        repos = new WorkspaceRepos(xa),
        authorizer = new Authorizer(xa),
        appOrigin = "http://localhost:3000"
      ).http
  ).orNotFound

  private def request(
      method: Method,
      path: String,
      token: Option[String] = None,
      body: Option[Json] = None
  ): IO[(Status, Json)] =
    val base = Request[IO](method, Uri.unsafeFromString(path))
    val withAuth = token.fold(base)(t =>
      base.withHeaders(
        Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $t")
      )
    )
    val req = body
      .fold(withAuth)(json =>
        withAuth
          .withEntity(json.noSpaces)(org.http4s.EntityEncoder.stringEncoder[IO])
          .withContentType(
            org.http4s.headers
              .`Content-Type`(org.http4s.MediaType.application.json)
          )
      )
    app
      .run(req)
      .flatMap(res =>
        res.body.through(fs2.text.utf8.decode).compile.string.map { text =>
          (res.status, parse(text).toOption.getOrElse(Json.Null))
        }
      )

  private def requestRedirect(
      method: Method,
      path: String
  ): IO[(Status, String)] =
    app
      .run(Request[IO](method, Uri.unsafeFromString(path)))
      .flatMap { res =>
        val location = res.headers
          .get[org.http4s.headers.Location]
          .map(_.uri.toString)
          .getOrElse("")
        IO.pure((res.status, location))
      }

  private def jsonField(body: Json, field: String): String =
    body.hcursor.get[String](field).toOption.getOrElse("")

  private def signedState(
      workspaceId: UUID,
      userId: UUID,
      verifier: String,
      ttlSeconds: Long
  ): String =
    val payload = Json.obj(
      "ws" -> Json.fromString(workspaceId.toString),
      "uid" -> Json.fromString(userId.toString),
      "exp" -> Json.fromLong(
        OffsetDateTime.now.plusSeconds(ttlSeconds).toEpochSecond
      ),
      "nonce" -> Json.fromString("nonce-it"),
      "v" -> Json.fromString(verifier)
    )
    registry.stateSigner.sign(payload.noSpaces)

  private def seedUser(id: UUID, email: String): IO[Unit] =
    (sql"""
      insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
      values ('00000000-0000-0000-0000-000000000000', $id, 'authenticated', 'authenticated',
              $email, '', now(), now(), now())
    """.update.run *>
      sql"insert into public.profile (id, display_name) values ($id, ${email.take(8)}) on conflict (id) do nothing".update.run).void
      .transact(xa)

  private var workspaceA: UUID = null
  private var workspaceB: UUID = null
  private var connectedAccountId: UUID = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  override def beforeAll(): Unit =
    if dbUrl.nonEmpty then
      (seedUser(userA, emailA) *> seedUser(userB, emailB) *> seedUser(
        userC,
        emailC
      ))
        .unsafeRunSync()
      super.beforeAll()

  override def afterAll(): Unit =
    if dbUrl.nonEmpty then
      sql"delete from auth.users where id in ($userA, $userB, $userC)".update.run.void
        .transact(xa)
        .unsafeRunSync()
      super.afterAll()

  test("setup: owners create workspaces via API; viewer membership seeded") {
    for
      created <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Gmail WS A")))
      )
      createdB <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-C"),
        Some(Json.obj("name" -> Json.fromString("Gmail WS B")))
      )
      _ <- IO {
        assertEquals(created._1, Status.Created)
        assertEquals(createdB._1, Status.Created)
        workspaceA = UUID.fromString(jsonField(created._2, "id"))
        workspaceB = UUID.fromString(jsonField(createdB._2, "id"))
      }
      _ <- sql"""
        insert into public.workspace_member (workspace_id, user_id, role)
        values ($workspaceA, $userB, 'viewer')
      """.update.run.void.transact(xa)
    yield ()
  }

  test("capability matrix reports gmail configured, others not") {
    request(Method.GET, "/api/v1/email-providers", Some("token-A")).map {
      (status, body) =>
        assertEquals(status, Status.Ok)
        val providers = body.asArray.getOrElse(Vector.empty)
        val gmail = providers
          .find(_.hcursor.get[String]("provider").toOption.contains("gmail"))
          .get
        assertEquals(
          gmail.hcursor.get[Boolean]("configured").toOption.getOrElse(true),
          true
        )
        val microsoft = providers
          .find(
            _.hcursor.get[String]("provider").toOption.contains("microsoft")
          )
          .get
        assertEquals(
          microsoft.hcursor.get[Boolean]("configured").toOption.getOrElse(true),
          false
        )
    }
  }

  test(
    "start returns an authorize URL bound to a verifiable state + PKCE challenge"
  ) {
    for
      started <- request(
        Method.POST,
        "/api/v1/email-connections/oauth/google/start",
        Some("token-A"),
        Some(Json.obj("workspaceId" -> Json.fromString(workspaceA.toString)))
      )
      _ <- IO {
        assertEquals(started._1, Status.Ok)
        val url = jsonField(started._2, "authorizeUrl")
        assert(url.startsWith("https://accounts.google.test/auth?"))
        assert(url.contains("client_id=cid"))
        assert(url.contains("code_challenge_method=S256"))
        assert(url.contains("access_type=offline"))
        val state = url.split("state=")(1).split("&")(0)
        // state verifies under the registry signer (signature + decodable payload)
        registry.stateSigner.verify(
          java.net.URLDecoder.decode(state, "UTF-8")
        ) match
          case Right(payload) =>
            val json = parse(payload).toOption.get
            assertEquals(
              json.hcursor.get[String]("ws").toOption,
              Some(workspaceA.toString)
            )
            assert(json.hcursor.get[String]("v").toOption.exists(_.nonEmpty))
          case Left(other) => fail(s"state did not verify: $other")
      }
    yield ()
  }

  test("start without configuration answers 503 not_configured") {
    val bareRegistry = new ProviderRegistry(
      encryptor = new CredentialEncryptor(keyBytes),
      stateSigner =
        new OAuthStateSigner(ProviderRegistry.deriveStateSecret(keyBytes)),
      appOrigin = "http://localhost:3000",
      redirectUri =
        "http://localhost:8080/api/v1/email-connections/oauth/google/callback"
    )
    val bareApp = new GmailRoutes(
      fakeAuth,
      repos,
      new Authorizer(xa),
      bareRegistry
    ).http.orNotFound
    bareApp
      .run(
        Request[IO](
          Method.POST,
          Uri.unsafeFromString("/api/v1/email-connections/oauth/google/start")
        )
          .withHeaders(
            Header
              .Raw(org.typelevel.ci.CIString("Authorization"), "Bearer token-A")
          )
          .withEntity(
            Json
              .obj("workspaceId" -> Json.fromString(workspaceA.toString))
              .noSpaces
          )(org.http4s.EntityEncoder.stringEncoder[IO])
          .withContentType(
            org.http4s.headers
              .`Content-Type`(org.http4s.MediaType.application.json)
          )
      )
      .flatMap(res => res.bodyText.compile.string)
      .map { text =>
        val body = parse(text).toOption.getOrElse(Json.Null)
        assertEquals(jsonField(body, "code"), "not_configured")
      }
  }

  test("callback rejects a tampered state") {
    requestRedirect(
      Method.GET,
      s"/api/v1/email-connections/oauth/google/callback?code=abc&state=forged.state"
    ).map { (status, location) =>
      assertEquals(status, Status.SeeOther)
      assert(location.contains("gmail=invalid_state"), location)
    }
  }

  test("callback rejects an expired state") {
    val state = signedState(workspaceA, userA, "verifier-exp", ttlSeconds = -10)
    requestRedirect(
      Method.GET,
      s"/api/v1/email-connections/oauth/google/callback?code=abc&state=${java.net.URLEncoder.encode(state, "UTF-8")}"
    ).map { (status, location) =>
      assertEquals(status, Status.SeeOther)
      assert(location.contains("gmail=expired"), location)
    }
  }

  test("callback records Google consent denial") {
    requestRedirect(
      Method.GET,
      "/api/v1/email-connections/oauth/google/callback?error=access_denied"
    ).map { (status, location) =>
      assertEquals(status, Status.SeeOther)
      assert(location.contains("gmail=denied"), location)
    }
  }

  test(
    "callback completes: account + encrypted credentials + health + audit, redirects connected"
  ) {
    val state = signedState(workspaceA, userA, "verifier-ok", ttlSeconds = 600)
    for
      redirect <- requestRedirect(
        Method.GET,
        s"/api/v1/email-connections/oauth/google/callback?code=good-code&state=${java.net.URLEncoder.encode(state, "UTF-8")}"
      )
      account <- repos.listAccounts(workspaceA)
      _ <- IO {
        assertEquals(redirect._1, Status.SeeOther)
        assert(redirect._2.contains("gmail=connected"), redirect._2)
        assertEquals(account.map(_.emailAddress), List("flow@gmail.test"))
        assertEquals(account.map(_.status), List("connected"))
        connectedAccountId =
          account.headOption.map(_.id).getOrElse(UUID.randomUUID())
      }
      creds <- repos.loadCredentials(workspaceA, connectedAccountId)
      healthEvents <- repos.listHealthEvents(workspaceA, connectedAccountId)
      audits <-
        sql"select action from public.audit_log where workspace_id = $workspaceA and target_id = ${connectedAccountId.toString}"
          .query[String]
          .to[List]
          .transact(xa)
      cursors <-
        sql"select direction from public.provider_sync_state where email_account_id = $connectedAccountId"
          .query[String]
          .to[List]
          .transact(xa)
    yield
      creds match
        case None      => fail("credentials row missing")
        case Some(row) =>
          assertEquals(row.kind, "oauth2")
          registry.decryptCredentials(row.encryptedBlob) match
            case Right(g) =>
              assertEquals(g.refreshToken, Some("rt-flow"))
              assertEquals(g.accessToken, "at-flow")
            case Left(err) => fail(s"credentials not decryptable: $err")
      assert(healthEvents.map(_.eventType).contains("connected"))
      assert(audits.contains("email_account.connected"))
      assertEquals(cursors.sorted, List("inbox", "sending"))
  }

  test("reconnection reuses the account row instead of duplicating it") {
    val state =
      signedState(workspaceA, userA, "verifier-again", ttlSeconds = 600)
    for
      redirect <- requestRedirect(
        Method.GET,
        s"/api/v1/email-connections/oauth/google/callback?code=good-code-2&state=${java.net.URLEncoder.encode(state, "UTF-8")}"
      )
      accounts <- repos.listAccounts(workspaceA)
    yield
      assertEquals(redirect._1, Status.SeeOther)
      assert(redirect._2.contains("gmail=reconnected"), redirect._2)
      assertEquals(accounts.map(_.emailAddress), List("flow@gmail.test"))
      assertEquals(accounts.map(_.status), List("connected"))
      connectedAccountId = accounts.head.id
  }

  test(
    "authorized test send records health and returns the provider message id"
  ) {
    for
      sent <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$connectedAccountId/test",
        Some("token-A"),
        Some(Json.obj("to" -> Json.fromString("recipient@example.test")))
      )
      health <- repos.listHealthEvents(workspaceA, connectedAccountId)
    yield
      assertEquals(sent._1, Status.Ok)
      assertEquals(jsonField(sent._2, "messageId"), "msg-flow-1")
      assert(health.map(_.eventType).contains("test_succeeded"))
  }

  test("viewer cannot test-send; outsider gets 404") {
    for
      forbidden <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$connectedAccountId/test",
        Some("token-B"),
        Some(Json.obj("to" -> Json.fromString("x@example.test")))
      )
      outsider <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$connectedAccountId/test",
        Some("token-C"),
        Some(Json.obj("to" -> Json.fromString("x@example.test")))
      )
    yield
      assertEquals(forbidden._1, Status.Forbidden)
      assertEquals(outsider._1, Status.NotFound)
  }

  test("disconnect purges credentials and attempts provider revocation") {
    for
      revoked <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$connectedAccountId/disconnect",
        Some("token-A")
      )
      creds <- repos.loadCredentials(workspaceA, connectedAccountId)
      account <- repos.findAccount(workspaceA, connectedAccountId)
    yield
      assertEquals(revoked._1, Status.Ok)
      assertEquals(jsonField(revoked._2, "status"), "disconnected")
      assertEquals(creds, None)
      assertEquals(account.map(_.status), Some("disconnected"))
  }
