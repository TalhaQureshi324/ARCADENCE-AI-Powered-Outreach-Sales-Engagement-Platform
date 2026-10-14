package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.crypto.{CredentialEncryptor, OAuthStateSigner}
import com.arcadence.core.email.microsoft.{GraphEndpoints, GraphProvider}
import com.arcadence.core.db.DbUrl
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{Header, HttpRoutes, Method, Request, Response, Status, Uri}

import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** Microsoft 365 connect flow end-to-end against a stub Graph server and the
  * real database (Phase 2.3): state-signed start, callback validation
  * (tamper/expiry/denial), account + encrypted-credential persistence,
  * reconnection, authorized test send via the shared endpoint, and disconnect
  * with best-effort session revocation.
  */
final class MicrosoftFlowIntegrationSpec extends CatsEffectSuite:

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

  private val msToken = Json.obj(
    "access_token" -> Json.fromString("msat-flow"),
    "refresh_token" -> Json.fromString("msrt-flow"),
    "expires_in" -> Json.fromLong(3600L)
  )

  private def stubClient: Client[IO] = {
    def json(status: Status, body: Json): Response[IO] =
      Response[IO](status)
        .withEntity(body.noSpaces)(org.http4s.EntityEncoder.stringEncoder[IO])
        .withContentType(
          org.http4s.headers
            .`Content-Type`(org.http4s.MediaType.application.json)
        )
    val routes = HttpRoutes.of[IO] {
      case POST -> Root / "organizations" / "oauth2" / "v2.0" / "token" =>
        IO.pure(json(Status.Ok, msToken))
      case GET -> Root / "me" =>
        IO.pure(
          json(
            Status.Ok,
            Json.obj(
              "mail" -> Json.fromString("flow@contoso.test"),
              "displayName" -> Json.fromString("Flow Contoso"),
              "id" -> Json.fromString("ms-flow-id")
            )
          )
        )
      case POST -> Root / "me" / "messages" =>
        IO.pure(
          json(Status.Created, Json.obj("id" -> Json.fromString("ms-draft-1")))
        )
      case POST -> Root / "me" / "messages" / "ms-draft-1" / "send" =>
        IO.pure(Response[IO](Status.Accepted))
      case POST -> Root / "me" / "revokeSignInSessions" =>
        IO.pure(Response[IO](Status.NoContent))
    }
    Client.fromHttpApp[IO](routes.orNotFound)
  }

  private val keyBytes =
    CredentialEncryptor
      .decodeKey(Base64.getEncoder.encodeToString(Array.fill(32)(11.toByte)))
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
    r.registerMicrosoft(
      new GraphProvider(
        "ms-cid",
        "ms-csecret",
        r.redirectUri.replace("/google/", "/microsoft/"),
        GraphEndpoints
          .Production("organizations")
          .copy(graphApiBaseUrl = "https://graph.google.test"),
        stubClient
      )
    )
    r
  }

  private val userA = UUID.randomUUID()
  private val userB = UUID.randomUUID()
  private val userC = UUID.randomUUID()
  private val emailA =
    s"it-2.3-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailB =
    s"it-2.3-b-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailC =
    s"it-2.3-c-${UUID.randomUUID().toString.take(8)}@arcadence.test"

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
    new MicrosoftRoutes(
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
        res.bodyText.compile.string.map { text =>
          (res.status, parse(text).toOption.getOrElse(Json.Null))
        }
      )

  private def requestRedirect(
      method: Method,
      path: String
  ): IO[(Status, String)] =
    app
      .run(Request[IO](method, Uri.unsafeFromString(path)))
      .map { res =>
        val location = res.headers
          .get[org.http4s.headers.Location]
          .map(_.uri.toString)
          .getOrElse("")
        (res.status, location)
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
      "nonce" -> Json.fromString("nonce-ms"),
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

  test("setup: owner creates a workspace via API; viewer membership seeded") {
    for
      created <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Graph WS A")))
      )
      _ <- IO {
        assertEquals(created._1, Status.Created)
        workspaceA = UUID.fromString(jsonField(created._2, "id"))
      }
      _ <- sql"""
        insert into public.workspace_member (workspace_id, user_id, role)
        values ($workspaceA, $userB, 'viewer')
      """.update.run.void.transact(xa)
    yield ()
  }

  test("capability matrix reports microsoft configured") {
    request(Method.GET, "/api/v1/email-providers", Some("token-A")).map {
      (status, body) =>
        assertEquals(status, Status.Ok)
        val microsoft = body.asArray
          .getOrElse(Vector.empty)
          .find(
            _.hcursor.get[String]("provider").toOption.contains("microsoft")
          )
          .get
        assertEquals(
          microsoft.hcursor
            .get[Boolean]("configured")
            .toOption
            .getOrElse(false),
          true
        )
    }
  }

  test("start returns a tenant-aware authorize URL with verifiable state") {
    request(
      Method.POST,
      "/api/v1/email-connections/oauth/microsoft/start",
      Some("token-A"),
      Some(Json.obj("workspaceId" -> Json.fromString(workspaceA.toString)))
    ).map { (status, body) =>
      assertEquals(status, Status.Ok)
      val url = jsonField(body, "authorizeUrl")
      assert(
        url.startsWith(
          "https://login.microsoftonline.com/organizations/oauth2/v2.0/authorize?"
        ),
        url
      )
      assert(url.contains("client_id=ms-cid"))
      val state = url.split("state=")(1).split("&")(0)
      registry.stateSigner.verify(
        java.net.URLDecoder.decode(state, "UTF-8")
      ) match
        case Right(payload) =>
          val json = parse(payload).toOption.get
          assertEquals(
            json.hcursor.get[String]("ws").toOption,
            Some(workspaceA.toString)
          )
        case Left(other) => fail(s"state did not verify: $other")
    }
  }

  test("callback rejects a tampered state") {
    requestRedirect(
      Method.GET,
      "/api/v1/email-connections/oauth/microsoft/callback?code=abc&state=forged.sig"
    ).map { (status, location) =>
      assertEquals(status, Status.SeeOther)
      assert(location.contains("microsoft=invalid_state"), location)
    }
  }

  test("callback rejects an expired state and records consent denial") {
    for
      expired <- requestRedirect(
        Method.GET,
        s"/api/v1/email-connections/oauth/microsoft/callback?code=abc&state=${java.net.URLEncoder.encode(signedState(workspaceA, userA, "v-exp", -10), "UTF-8")}"
      )
      denied <- requestRedirect(
        Method.GET,
        "/api/v1/email-connections/oauth/microsoft/callback?error=access_denied"
      )
    yield
      assertEquals(expired._1, Status.SeeOther)
      assert(expired._2.contains("microsoft=expired"), expired._2)
      assertEquals(denied._1, Status.SeeOther)
      assert(denied._2.contains("microsoft=denied"), denied._2)
  }

  test(
    "callback completes: account + encrypted credentials + cursors, redirects connected"
  ) {
    val state = signedState(workspaceA, userA, "v-ok", 600)
    for
      redirect <- requestRedirect(
        Method.GET,
        s"/api/v1/email-connections/oauth/microsoft/callback?code=good&state=${java.net.URLEncoder.encode(state, "UTF-8")}"
      )
      accounts <- repos.listAccounts(workspaceA)
      _ <- IO {
        assertEquals(redirect._1, Status.SeeOther)
        assert(redirect._2.contains("microsoft=connected"), redirect._2)
        assertEquals(accounts.map(_.emailAddress), List("flow@contoso.test"))
        assertEquals(accounts.map(_.provider), List("microsoft"))
        connectedAccountId =
          accounts.headOption.map(_.id).getOrElse(UUID.randomUUID())
      }
      creds <- repos.loadCredentials(workspaceA, connectedAccountId)
      cursors <-
        sql"select direction from public.provider_sync_state where email_account_id = $connectedAccountId order by direction"
          .query[String]
          .to[List]
          .transact(xa)
    yield
      creds match
        case None      => fail("credentials row missing")
        case Some(row) =>
          registry.decryptCredentials(row.encryptedBlob, "microsoft") match
            case Right(
                  m: com.arcadence.core.email.microsoft.MicrosoftCredentials
                ) =>
              assertEquals(m.refreshToken, Some("msrt-flow"))
              assertEquals(m.accessToken, "msat-flow")
            case Right(other) => fail(s"wrong credential type: $other")
            case Left(err)    => fail(s"not decryptable: $err")
      assertEquals(cursors, List("inbox", "sending"))
  }

  test("reconnection reuses the account row") {
    val state = signedState(workspaceA, userA, "v-again", 600)
    for
      redirect <- requestRedirect(
        Method.GET,
        s"/api/v1/email-connections/oauth/microsoft/callback?code=good-2&state=${java.net.URLEncoder.encode(state, "UTF-8")}"
      )
      accounts <- repos.listAccounts(workspaceA)
    yield
      assert(redirect._2.contains("microsoft=reconnected"), redirect._2)
      assertEquals(accounts.map(_.emailAddress), List("flow@contoso.test"))
      connectedAccountId = accounts.head.id
  }

  test(
    "authorized test send (shared endpoint) routes through the Graph adapter"
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
      assertEquals(jsonField(sent._2, "messageId"), "ms-draft-1")
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

  test("disconnect purges credentials after best-effort session revocation") {
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
