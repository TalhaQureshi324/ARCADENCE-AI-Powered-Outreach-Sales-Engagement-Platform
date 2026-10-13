package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.db.DbUrl
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Header, Method, Request, Status, Uri}

import java.util.UUID

/** Email account management API (Phase 2.1): capability matrix, group CRUD,
  * account lifecycle transitions, health trail, permission gating and
  * cross-tenant isolation. Runs against a real database — set TEST_DATABASE_URL
  * (local stack: postgresql://postgres:postgres@127.0.0.1:54322/postgres).
  */
final class EmailAccountIntegrationSpec extends CatsEffectSuite:

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

  private val userA = UUID.randomUUID() // owner of workspace A
  private val userB = UUID.randomUUID() // viewer in workspace A
  private val userC = UUID.randomUUID() // owner of workspace B (outsider for A)
  private val emailA =
    s"it-2.1-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailB =
    s"it-2.1-b-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailC =
    s"it-2.1-c-${UUID.randomUUID().toString.take(8)}@arcadence.test"

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
    new EmailAccountRoutes(
      authenticator = fakeAuth,
      repos = repos,
      authorizer = new Authorizer(xa)
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
      token: String,
      body: Option[Json] = None
  ): IO[(Status, Json)] =
    val base = Request[IO](method, Uri.unsafeFromString(path))
      .withHeaders(
        Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $token")
      )
    val req = body
      .fold(base)(json =>
        base
          .withEntity(json.noSpaces)
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

  private def jsonField(body: Json, field: String): String =
    body.hcursor.get[String](field).toOption.getOrElse("")

  private def seedUser(id: UUID, email: String): IO[Unit] =
    (sql"""
      insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
      values ('00000000-0000-0000-0000-000000000000', $id, 'authenticated', 'authenticated',
              $email, '', now(), now(), now())
    """.update.run *>
      sql"insert into public.profile (id, display_name) values ($id, ${email.take(8)}) on conflict (id) do nothing".update.run).void
      .transact(xa)

  private def seedAccount(
      workspaceId: UUID,
      email: String,
      status: String = "connected"
  ): IO[UUID] =
    sql"""
      insert into public.email_accounts (workspace_id, provider, email_address, status)
      values ($workspaceId, 'gmail', $email, $status::public.connection_status)
      returning id
    """.query[UUID].unique.transact(xa)

  private def seedCredential(accountId: UUID): IO[Unit] =
    sql"""
      insert into public.account_credentials (email_account_id, kind, encrypted_blob, encryption_key_version)
      values ($accountId, 'oauth2', decode('41424344', 'hex'), 1)
    """.update.run.void.transact(xa)

  private var workspaceA: UUID = null
  private var workspaceB: UUID = null
  private var accountId: UUID = null

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

  test(
    "setup: owners create workspaces via API and viewer membership is seeded"
  ) {
    for
      created <- request(
        Method.POST,
        "/api/v1/workspaces",
        "token-A",
        Some(Json.obj("name" -> Json.fromString("Accounts WS A")))
      )
      createdB <- request(
        Method.POST,
        "/api/v1/workspaces",
        "token-C",
        Some(Json.obj("name" -> Json.fromString("Accounts WS B")))
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

  test("providers endpoint lists all three providers with capabilities") {
    request(Method.GET, "/api/v1/email-providers", "token-A").map {
      (status, body) =>
        assertEquals(status, Status.Ok)
        val providers = body.asArray.getOrElse(Vector.empty)
        assertEquals(providers.size, 3)
        val ids = providers.map(
          _.hcursor.get[String]("provider").toOption.getOrElse("")
        )
        assertEquals(ids.toSet, Set("gmail", "microsoft", "smtp_imap"))
        val smtp = providers
          .find(
            _.hcursor.get[String]("provider").toOption.contains("smtp_imap")
          )
          .get
        assertEquals(
          smtp.hcursor.get[String]("authType").toOption.getOrElse(""),
          "password_tls"
        )
        assertEquals(
          smtp.hcursor.get[Boolean]("canRefreshToken").toOption.getOrElse(true),
          false
        )
        // No secret material anywhere in the capability matrix
        assert(!body.noSpaces.toLowerCase.contains("secret"))
    }
  }

  test("owner creates an account group; duplicates are rejected with 409") {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/account-groups",
        "token-A",
        Some(Json.obj("name" -> Json.fromString("EU senders")))
      )
      duplicate <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/account-groups",
        "token-A",
        Some(Json.obj("name" -> Json.fromString("eu senders")))
      )
    yield
      assertEquals(created._1, Status.Created)
      assert(jsonField(created._2, "id").nonEmpty)
      assertEquals(duplicate._1, Status.Conflict)
  }

  test(
    "viewer cannot create groups but can read accounts (read/manage split)"
  ) {
    for
      forbidden <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/account-groups",
        "token-B",
        Some(Json.obj("name" -> Json.fromString("nope")))
      )
      allowed <- request(
        Method.GET,
        s"/api/v1/workspaces/$workspaceA/account-groups",
        "token-B"
      )
    yield
      assertEquals(forbidden._1, Status.Forbidden)
      assertEquals(allowed._1, Status.Ok)
  }

  test("seeded account is listed for owner and viewer; non-member gets 404") {
    for
      seeded <- seedAccount(
        workspaceA,
        s"sender-${UUID.randomUUID().toString.take(8)}@arcadence.test"
      )
      _ <- IO { accountId = seeded }
      ownerView <- request(
        Method.GET,
        s"/api/v1/workspaces/$workspaceA/email-accounts",
        "token-A"
      )
      viewerView <- request(
        Method.GET,
        s"/api/v1/workspaces/$workspaceA/email-accounts",
        "token-B"
      )
      outsider <- request(
        Method.GET,
        s"/api/v1/workspaces/$workspaceA/email-accounts",
        "token-C"
      )
    yield
      assertEquals(ownerView._1, Status.Ok)
      assertEquals(ownerView._2.asArray.getOrElse(Vector.empty).size, 1)
      assertEquals(viewerView._1, Status.Ok)
      assertEquals(outsider._1, Status.NotFound)
  }

  test("account payloads never expose credential fields") {
    request(
      Method.GET,
      s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId",
      "token-A"
    ).map { (status, body) =>
      assertEquals(status, Status.Ok)
      val text = body.noSpaces.toLowerCase
      assert(!text.contains("credential"))
      assert(!text.contains("encrypted"))
      assert(!text.contains("token"))
    }
  }

  test("owner updates display name, group and daily cap; null clears the cap") {
    for
      groups <- request(
        Method.GET,
        s"/api/v1/workspaces/$workspaceA/account-groups",
        "token-A"
      )
      groupId = groups._2.asArray
        .getOrElse(Vector.empty)
        .head
        .hcursor
        .get[String]("id")
        .toOption
        .getOrElse("")
      updated <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId",
        "token-A",
        Some(
          Json.obj(
            "displayName" -> Json.fromString("Primary sender"),
            "accountGroupId" -> Json.fromString(groupId),
            "dailySendCap" -> Json.fromInt(250)
          )
        )
      )
      cleared <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId",
        "token-A",
        Some(Json.obj("dailySendCap" -> Json.Null))
      )
    yield
      assertEquals(updated._1, Status.Ok)
      assertEquals(jsonField(updated._2, "displayName"), "Primary sender")
      assertEquals(
        updated._2.hcursor.get[Int]("dailySendCap").toOption.getOrElse(0),
        250
      )
      assertEquals(jsonField(updated._2, "accountGroupName"), "EU senders")
      assertEquals(cleared._1, Status.Ok)
      assert(
        cleared._2.hcursor.downField("dailySendCap").focus.exists(_.isNull)
      )
  }

  test("viewer cannot mutate an account (403)") {
    request(
      Method.PATCH,
      s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId",
      "token-B",
      Some(Json.obj("displayName" -> Json.fromString("hijacked")))
    ).map((status, _) => assertEquals(status, Status.Forbidden))
  }

  test("outsider cannot mutate or delete another workspace's account (404)") {
    for
      patched <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId",
        "token-C",
        Some(Json.obj("displayName" -> Json.fromString("hijacked")))
      )
      deleted <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId",
        "token-C"
      )
    yield
      assertEquals(patched._1, Status.NotFound)
      assertEquals(deleted._1, Status.NotFound)
  }

  test(
    "lifecycle: pause, resume, disconnect; illegal pause on disconnected is 409"
  ) {
    for
      paused <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId/pause",
        "token-A"
      )
      resumed <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId/resume",
        "token-A"
      )
      disconnected <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId/disconnect",
        "token-A"
      )
      illegal <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId/pause",
        "token-A"
      )
    yield
      assertEquals(paused._1, Status.Ok)
      assertEquals(jsonField(paused._2, "status"), "suspended")
      assertEquals(resumed._1, Status.Ok)
      assertEquals(jsonField(resumed._2, "status"), "connected")
      assertEquals(disconnected._1, Status.Ok)
      assertEquals(jsonField(disconnected._2, "status"), "disconnected")
      assertEquals(illegal._1, Status.Conflict)
  }

  test("disconnect purges stored credentials") {
    for
      fresh <- seedAccount(
        workspaceA,
        s"purge-${UUID.randomUUID().toString.take(8)}@arcadence.test"
      )
      _ <- seedCredential(fresh)
      _ <- request(
        Method.POST,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$fresh/disconnect",
        "token-A"
      )
      remaining <-
        sql"select count(*) from public.account_credentials where email_account_id = $fresh"
          .query[Int]
          .unique
          .transact(xa)
    yield assertEquals(remaining, 0)
  }

  test("health trail records transitions and is readable by viewers") {
    for
      events <- request(
        Method.GET,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId/health",
        "token-A"
      )
      viewer <- request(
        Method.GET,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$accountId/health",
        "token-B"
      )
    yield
      assertEquals(events._1, Status.Ok)
      val types = events._2.asArray
        .getOrElse(Vector.empty)
        .map(_.hcursor.get[String]("eventType").toOption.getOrElse(""))
      assert(types.contains("paused"))
      assert(types.contains("resumed"))
      assert(types.contains("disconnected"))
      assertEquals(viewer._1, Status.Ok)
  }

  test("delete removes the account and cascades; viewer delete is forbidden") {
    for
      fresh <- seedAccount(
        workspaceA,
        s"delete-${UUID.randomUUID().toString.take(8)}@arcadence.test"
      )
      _ <- seedCredential(fresh)
      forbidden <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$fresh",
        "token-B"
      )
      deleted <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$workspaceA/email-accounts/$fresh",
        "token-A"
      )
      gone <- repos.findAccount(workspaceA, fresh)
      events <-
        sql"select count(*) from public.account_health_events where email_account_id = $fresh"
          .query[Int]
          .unique
          .transact(xa)
    yield
      assertEquals(forbidden._1, Status.Forbidden)
      assertEquals(deleted._1, Status.NoContent)
      assertEquals(gone, None)
      assertEquals(events, 0)
  }

  test("workspace B stays empty — cross-tenant isolation end state") {
    request(
      Method.GET,
      s"/api/v1/workspaces/$workspaceB/email-accounts",
      "token-C"
    ).map { (status, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body.asArray.getOrElse(Vector.empty).size, 0)
    }
  }
