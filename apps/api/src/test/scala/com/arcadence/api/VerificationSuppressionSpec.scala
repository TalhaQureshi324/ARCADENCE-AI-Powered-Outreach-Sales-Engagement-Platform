package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.db.DbUrl
import com.arcadence.core.email.verification.{
  EmailVerificationProvider,
  VerificationOutcome,
  VerificationStatus
}
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Header, Method, Request, Status, Uri}

import java.time.OffsetDateTime
import java.util.UUID

/** Verification + suppression (Phase 3.3): provider-driven verification with
  * provenance, auto-suppression of invalid results, suppression CRUD with the
  * reactivation guard (unsubscribe/complaint permanent, delete = admin+), and
  * import enforcement. Stub provider — no network.
  */
final class VerificationSuppressionSpec extends CatsEffectSuite:

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

  /** Deterministic stub: passes for "ok.*" addresses, risky for "risky.*",
    * invalid otherwise.
    */
  private val stubProvider = new EmailVerificationProvider:
    val id: String = "stub"
    def verify(email: String): IO[VerificationOutcome] =
      val status =
        if email.startsWith("ok.") then
          VerificationStatus.Risky // syntax+MX can never verify
        else if email.startsWith("risky.") then VerificationStatus.Risky
        else VerificationStatus.Invalid
      IO.pure(
        VerificationOutcome(
          email,
          status,
          id,
          OffsetDateTime.now,
          s"stub says $status"
        )
      )

  private val userA = UUID.randomUUID() // owner of WS-A
  private val userB = UUID.randomUUID() // viewer in WS-A
  private val userD = UUID.randomUUID() // admin in WS-A
  private val emailA =
    s"it-3.3-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailB =
    s"it-3.3-b-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailD =
    s"it-3.3-d-${UUID.randomUUID().toString.take(8)}@arcadence.test"

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, emailA),
    "token-B" -> AuthenticatedUser(userB, emailB),
    "token-D" -> AuthenticatedUser(userD, emailD)
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val repos = new LeadsRepos(xa)
  private lazy val importRepos = new ImportRepos(xa)
  private lazy val app = (
    new VerificationRoutes(
      authenticator = fakeAuth,
      repos = repos,
      authorizer = new Authorizer(xa),
      provider = stubProvider
    ).http
      <+> new ContactRoutes(
        authenticator = fakeAuth,
        repos = repos,
        authorizer = new Authorizer(xa)
      ).http
      <+> new ImportRoutes(
        authenticator = fakeAuth,
        repos = new ImportRepos(xa),
        leadsRepos = repos,
        authorizer = new Authorizer(xa),
        service = new ImportService(
          new ImportRepos(xa),
          repos,
          xa,
          java.nio.file.Paths.get(
            System.getProperty("java.io.tmpdir"),
            s"it-33-${UUID.randomUUID()}"
          ),
          25L * 1024 * 1024
        )
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
      body: Option[Json] = None,
      raw: Option[Array[Byte]] = None,
      query: Map[String, String] = Map.empty
  ): IO[(Status, Json)] =
    val qs = query
      .map { (k, v) =>
        s"$k=${java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)}"
      }
      .mkString("&")
    val base = Uri.unsafeFromString(if qs.isEmpty then path else s"$path?$qs")
    val baseReq = Request[IO](method, base)
    val withAuth = token.fold(baseReq)(t =>
      baseReq.withHeaders(
        Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $t")
      )
    )
    val req = raw.fold(
      body.fold(withAuth)(json =>
        withAuth
          .withEntity(json.noSpaces)(org.http4s.EntityEncoder.stringEncoder[IO])
          .withContentType(
            org.http4s.headers
              .`Content-Type`(org.http4s.MediaType.application.json)
          )
      )
    )(bytes =>
      withAuth
        .withEntity(bytes)(org.http4s.EntityEncoder.byteArrayEncoder[IO])
        .withContentType(
          org.http4s.headers
            .`Content-Type`(org.http4s.MediaType.application.`octet-stream`)
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

  private def seedUser(id: UUID, email: String): IO[Unit] =
    (sql"""
      insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
      values ('00000000-0000-0000-0000-000000000000', $id, 'authenticated', 'authenticated',
              $email, '', now(), now(), now())
    """.update.run *>
      sql"insert into public.profile (id, display_name) values ($id, ${email.take(8)}) on conflict (id) do nothing".update.run).void
      .transact(xa)

  private var wsA: UUID = null
  private var okContactId: UUID = null
  private var badContactId: UUID = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  override def beforeAll(): Unit =
    if dbUrl.nonEmpty then
      (seedUser(userA, emailA) *> seedUser(userB, emailB) *> seedUser(
        userD,
        emailD
      )).unsafeRunSync()
      super.beforeAll()

  override def afterAll(): Unit =
    if dbUrl.nonEmpty then
      sql"delete from auth.users where id in ($userA, $userB, $userD)".update.run.void
        .transact(xa)
        .unsafeRunSync()
      super.afterAll()

  test("setup: workspace + two contacts + admin membership") {
    for
      created <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Verify WS")))
      )
      _ <- IO {
        assertEquals(created._1, Status.Created, created._2.noSpaces.take(300))
        wsA = UUID.fromString(jsonField(created._2, "id"))
      }
      ok <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        Some(
          Json.obj(
            "email" -> Json.fromString(
              s"ok.${UUID.randomUUID().toString.take(8)}@x.test"
            )
          )
        )
      )
      bad <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        Some(
          Json.obj(
            "email" -> Json.fromString(
              s"bad.${UUID.randomUUID().toString.take(8)}@x.test"
            )
          )
        )
      )
      _ <- sql"""
        insert into public.workspace_member (workspace_id, user_id, role)
        values ($wsA, $userB, 'viewer'), ($wsA, $userD, 'admin')
      """.update.run.void.transact(xa)
    yield
      okContactId = UUID.fromString(jsonField(ok._2, "id"))
      badContactId = UUID.fromString(jsonField(bad._2, "id"))
  }

  test(
    "verify marks risky (never verified from syntax+MX), records provenance"
  ) {
    request(
      Method.POST,
      s"/api/v1/workspaces/$wsA/contacts/$okContactId/verify",
      Some("token-A")
    ).map { (status, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(jsonField(body, "status"), "risky")
      assertEquals(jsonField(body, "provider"), "stub")
      assertEquals(
        body.hcursor.get[Boolean]("suppressed").toOption,
        Some(false)
      )
    }
  }

  test(
    "invalid verification auto-suppresses and the contact leaves the active pool"
  ) {
    for
      verified <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts/$badContactId/verify",
        Some("token-A")
      )
      suppression =
        if verified._2.hcursor
            .get[Boolean]("suppressed")
            .toOption
            .contains(true)
        then "true"
        else "false"
      contact <- repos.findContact(wsA, badContactId)
      suppressedRows <-
        sql"select count(*) from public.suppression where workspace_id = $wsA and contact_id = $badContactId"
          .query[Int]
          .unique
          .transact(xa)
    yield
      assertEquals(verified._1, Status.Ok)
      assertEquals(jsonField(verified._2, "status"), "invalid")
      assertEquals(suppression, "true")
      assertEquals(contact.map(_.state), Some("bounced"))
      assertEquals(suppressedRows, 1)
  }

  test("manual suppression: create by email, dedupe on conflict, list") {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/suppressions",
        Some("token-A"),
        Some(
          Json.obj(
            "email" -> Json.fromString("manual.block@x.test"),
            "reason" -> Json.fromString("manual")
          )
        )
      )
      listed <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/suppressions",
        Some("token-A")
      )
    yield
      assertEquals(created._1, Status.Created)
      assertEquals(jsonField(created._2, "source"), "manual")
      val emails = listed._2.asArray
        .getOrElse(Vector.empty)
        .map(_.hcursor.get[String]("email").toOption.getOrElse(""))
      assert(emails.contains("manual.block@x.test"), emails.toString)
  }

  test("viewer cannot create suppressions (403)") {
    request(
      Method.POST,
      s"/api/v1/workspaces/$wsA/suppressions",
      Some("token-B"),
      Some(
        Json.obj(
          "email" -> Json.fromString("nope@x.test"),
          "reason" -> Json.fromString("manual")
        )
      )
    ).map((status, _) => assertEquals(status, Status.Forbidden))
  }

  test(
    "reactivation guard: unsubscribe suppression cannot be removed even by owner"
  ) {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/suppressions",
        Some("token-A"),
        Some(
          Json.obj(
            "email" -> Json.fromString("perm.unsub@x.test"),
            "reason" -> Json.fromString("unsubscribe")
          )
        )
      )
      suppressionId = jsonField(created._2, "id")
      deleted <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$wsA/suppressions/$suppressionId",
        Some("token-A")
      )
    yield assertEquals(deleted._1, Status.Conflict)
  }

  test(
    "admin can remove a manual suppression; contact reactivates; action audited"
  ) {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/suppressions",
        Some("token-A"),
        Some(
          Json.obj(
            "email" -> Json.fromString("temp.block@x.test"),
            "reason" -> Json.fromString("manual")
          )
        )
      )
      suppressionId = jsonField(created._2, "id")
      viewerDeleted <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$wsA/suppressions/$suppressionId",
        Some("token-B")
      )
      adminDeleted <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$wsA/suppressions/$suppressionId",
        Some("token-D")
      )
      audits <-
        sql"select action from public.audit_log where workspace_id = $wsA and action = 'suppression.removed'"
          .query[String]
          .to[List]
          .transact(xa)
    yield
      assertEquals(viewerDeleted._1, Status.Forbidden)
      assertEquals(adminDeleted._1, Status.NoContent)
      assert(audits.contains("suppression.removed"))
  }

  test("import enforcement: suppressed emails never import") {
    val csv =
      s"email\r\nsuppressed-import-${UUID.randomUUID().toString.take(8)}@x.test\r\n".getBytes
    for
      uploaded <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/upload",
        Some("token-A"),
        raw = Some(csv),
        query = Map("fileName" -> "suppressed.csv")
      )
      jobId = uploaded._2.hcursor
        .downField("job")
        .get[UUID]("id")
        .toOption
        .getOrElse(null)
      // pre-seed the suppression for this exact email
      email = new String(csv, java.nio.charset.StandardCharsets.UTF_8)
        .split("\r\n")
        .drop(1)
        .head
      _ <- repos.addSuppression(wsA, email, None, "manual", "manual", userA)
      started <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/$jobId/start",
        Some("token-A"),
        Some(
          Json.obj(
            "mapping" -> Json.obj("0" -> Json.fromString("email")),
            "duplicateStrategy" -> Json.fromString("skip")
          )
        )
      )
      _ <- IO.sleep(scala.concurrent.duration.Duration(2, "seconds"))
      job <- importRepos.getJob(wsA, jobId)
      count <-
        sql"select count(*) from public.contact where workspace_id = $wsA and email = $email"
          .query[Int]
          .unique
          .transact(xa)
    yield
      assertEquals(started._1, Status.Ok)
      assertEquals(job.map(_.insertedRows), Some(0))
      assertEquals(count, 0)
  }
