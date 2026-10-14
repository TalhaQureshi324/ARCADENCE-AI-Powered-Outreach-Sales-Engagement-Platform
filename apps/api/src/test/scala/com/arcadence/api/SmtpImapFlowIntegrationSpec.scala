package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.db.DbUrl
import com.icegreen.greenmail.util.{GreenMail, ServerSetup}
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Status, Uri}

import java.util.UUID

/** SMTP/IMAP connect flow against a REAL mail server (GreenMail) and the real
  * database (Phase 2.4): credential validation happens server-side before
  * account creation, duplicates conflict, wrong passwords surface as provider
  * errors (502) without leaking the password, and the account is fully usable
  * (test send + disconnect purge).
  */
final class SmtpImapFlowIntegrationSpec extends CatsEffectSuite:

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

  private var greenmail: GreenMail = null

  override def beforeAll(): Unit =
    if dbUrl.nonEmpty then
      System.setProperty("mail.smtp.ssl.trust", "*")
      System.setProperty("mail.imap.ssl.trust", "*")
      System.setProperty("mail.smtp.ssl.checkserveridentity", "false")
      System.setProperty("mail.imap.ssl.checkserveridentity", "false")
      val smtp = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS)
      val imap = new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS)
      greenmail = new GreenMail(Array(smtp, imap))
      greenmail.start()
      greenmail.setUser("flow@greenmail.test", "app-password-9")
      (seedUser(userA, emailA) *> seedUser(userB, emailB)).unsafeRunSync()
      ()
    super.beforeAll()

  override def afterAll(): Unit =
    if greenmail != null then greenmail.stop()
    if dbUrl.nonEmpty then
      sql"delete from auth.users where id in ($userA, $userB)".update.run.void
        .transact(xa)
        .unsafeRunSync()
      System.clearProperty("mail.smtp.ssl.trust")
      System.clearProperty("mail.imap.ssl.trust")
      System.clearProperty("mail.smtp.ssl.checkserveridentity")
      System.clearProperty("mail.imap.ssl.checkserveridentity")
    super.afterAll()

  private val userA = UUID.randomUUID() // owner
  private val userB = UUID.randomUUID() // viewer
  private val emailA =
    s"it-2.4-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailB =
    s"it-2.4-b-${UUID.randomUUID().toString.take(8)}@arcadence.test"

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, emailA),
    "token-B" -> AuthenticatedUser(userB, emailB)
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val repos = new EmailAccountRepos(xa)
  private lazy val registry = {
    val keyBytes = com.arcadence.core.crypto.CredentialEncryptor
      .decodeKey(
        java.util.Base64.getEncoder.encodeToString(Array.fill(32)(13.toByte))
      )
      .fold(sys.error(_), identity)
    val r = new ProviderRegistry(
      encryptor = new com.arcadence.core.crypto.CredentialEncryptor(keyBytes),
      stateSigner = new com.arcadence.core.crypto.OAuthStateSigner(
        ProviderRegistry.deriveStateSecret(keyBytes)
      ),
      appOrigin = "http://localhost:3000",
      redirectUri =
        "http://localhost:8080/api/v1/email-connections/oauth/google/callback"
    )
    r.registerSmtpImap(new com.arcadence.core.email.smtpimap.SmtpImapProvider)
    r
  }

  private lazy val app = (
    new EmailAccountRoutes(
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

  private def jsonField(body: Json, field: String): String =
    body.hcursor.get[String](field).toOption.getOrElse("")

  private def connectBody(
      address: String = "flow@greenmail.test",
      password: String = "app-password-9"
  ): Json = Json.obj(
    "emailAddress" -> Json.fromString(address),
    "displayName" -> Json.fromString("Flow Sender"),
    "username" -> Json.fromString(address),
    "password" -> Json.fromString(password),
    "smtpHost" -> Json.fromString(greenmail.getSmtps.getBindTo),
    "smtpPort" -> Json.fromInt(greenmail.getSmtps.getPort),
    "smtpSecurity" -> Json.fromString("ssl"),
    "imapHost" -> Json.fromString(greenmail.getImaps.getBindTo),
    "imapPort" -> Json.fromInt(greenmail.getImaps.getPort),
    "imapSecurity" -> Json.fromString("ssl")
  )

  private var workspaceA: UUID = null
  private var accountId: UUID = null

  private def seedUser(id: UUID, email: String): IO[Unit] =
    (sql"""
      insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
      values ('00000000-0000-0000-0000-000000000000', $id, 'authenticated', 'authenticated',
              $email, '', now(), now(), now())
    """.update.run *>
      sql"insert into public.profile (id, display_name) values ($id, ${email.take(8)}) on conflict (id) do nothing".update.run).void
      .transact(xa)

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty && greenmail != null,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: owner creates a workspace; viewer membership seeded") {
    for
      created <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Smtp WS A")))
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

  test(
    "connect validates credentials against the real servers and creates the account"
  ) {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts",
        Some("token-A"),
        Some(connectBody())
      )
      accounts <- repos.listAccounts(workspaceA)
      _ <- IO {
        assertEquals(created._1, Status.Created)
        assertEquals(jsonField(created._2, "provider"), "smtp_imap")
        assertEquals(jsonField(created._2, "status"), "connected")
        assertEquals(accounts.map(_.emailAddress), List("flow@greenmail.test"))
        accountId = accounts.headOption.map(_.id).getOrElse(UUID.randomUUID())
        // sync info comes through the list payload
        assertEquals(accounts.head.syncStatus, Some("idle"))
      }
      creds <- repos.loadCredentials(workspaceA, accountId)
    yield
      creds match
        case None      => fail("credentials row missing")
        case Some(row) =>
          assertEquals(row.kind, "password")
          // decryptable and holding the real password (only server-side)
          registry.decryptCredentials(row.encryptedBlob, "smtp_imap") match
            case Right(
                  c: com.arcadence.core.email.smtpimap.SmtpImapCredentials
                ) =>
              assertEquals(c.password, "app-password-9")
            case Right(other) => fail(s"wrong credential type: $other")
            case Left(err)    => fail(s"not decryptable: $err")
      // the response payload never contains the password
      assert(!created._2.noSpaces.contains("app-password-9"))
  }

  test(
    "wrong password surfaces a provider error (502) that never echoes the password"
  ) {
    val response = request(
      Method.POST,
      s"/api/v1/workspaces/$workspaceA/email-accounts",
      Some("token-A"),
      Some(
        connectBody(
          address = "other@greenmail.test",
          password = "definitely-wrong"
        )
      )
    )
    val (status, body) = response.unsafeRunSync()
    assertEquals(status, Status.BadGateway)
    assertEquals(jsonField(body, "code"), "provider_error")
    assert(!body.noSpaces.contains("definitely-wrong"))
    val remaining = repos.listAccounts(workspaceA).unsafeRunSync()
    assertEquals(remaining.map(_.emailAddress), List("flow@greenmail.test"))
  }

  test(
    "duplicate address conflicts (409) without another validation round-trip"
  ) {
    request(
      Method.POST,
      s"/api/v1/workspaces/$workspaceA/email-accounts",
      Some("token-A"),
      Some(connectBody())
    ).map { (status, body) =>
      assertEquals(status, Status.Conflict)
      assertEquals(jsonField(body, "code"), "conflict")
    }
  }

  test("viewer cannot connect accounts (403)") {
    request(
      Method.POST,
      s"/api/v1/workspaces/$workspaceA/email-accounts",
      Some("token-B"),
      Some(connectBody(address = "viewer@greenmail.test"))
    ).map((status, _) => assertEquals(status, Status.Forbidden))
  }

  test("signature can be set and cleared via PATCH (null clears)") {
    for
      set <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId",
        Some("token-A"),
        Some(
          Json.obj("signature" -> Json.fromString("Best regards,\nFlow Sender"))
        )
      )
      cleared <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId",
        Some("token-A"),
        Some(Json.obj("signature" -> Json.Null))
      )
    yield
      assertEquals(set._1, Status.Ok)
      assertEquals(
        set._2.hcursor.get[String]("signature").toOption,
        Some("Best regards,\nFlow Sender")
      )
      assertEquals(cleared._1, Status.Ok)
      assert(cleared._2.hcursor.downField("signature").focus.exists(_.isNull))
  }

  test(
    "test send delivers through the real SMTP server; disconnect purges credentials"
  ) {
    for
      sent <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId/test",
        Some("token-A"),
        Some(Json.obj("to" -> Json.fromString("recipient@greenmail.test")))
      )
      delivered = greenmail.waitForIncomingEmail(10000, 1)
      health <- repos.listHealthEvents(workspaceA, accountId)
      revoked <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId/disconnect",
        Some("token-A")
      )
      creds <- repos.loadCredentials(workspaceA, accountId)
    yield
      assertEquals(sent._1, Status.Ok)
      assert(jsonField(sent._2, "messageId").nonEmpty)
      assert(delivered, "expected the test message to arrive at GreenMail")
      assert(health.map(_.eventType).contains("test_succeeded"))
      assertEquals(revoked._1, Status.Ok)
      assertEquals(jsonField(revoked._2, "status"), "disconnected")
      assertEquals(creds, None)
  }
