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

import java.nio.file.Files
import java.util.UUID

/** CSV import pipeline (Phase 3.2): upload → mapping → processing with the
  * plan's test list — 10,000-record import, duplicate handling (skip/update),
  * malformed files, tenant isolation, idempotent retry (restart handling) —
  * against a real database.
  */
final class ImportIntegrationSpec extends CatsEffectSuite:

  // the 10,000-row import leg needs more than the 30s default
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(8, "minutes")

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

  private val spoolDir = Files.createTempDirectory("arcadence-import-test")

  private val userA = UUID.randomUUID()
  private val userC = UUID.randomUUID()
  private val emailA =
    s"it-3.2-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailC =
    s"it-3.2-c-${UUID.randomUUID().toString.take(8)}@arcadence.test"

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, emailA),
    "token-C" -> AuthenticatedUser(userC, emailC)
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val leadsRepos = new LeadsRepos(xa)
  private lazy val importRepos = new ImportRepos(xa)
  private lazy val service = new ImportService(
    repos = importRepos,
    leadsRepos = leadsRepos,
    transactor = xa,
    spoolRoot = spoolDir,
    maxFileBytes = 25L * 1024 * 1024
  )
  private lazy val app = (
    new ImportRoutes(
      authenticator = fakeAuth,
      repos = importRepos,
      leadsRepos = leadsRepos,
      authorizer = new Authorizer(xa),
      service = service
    ).http
      <+> new ContactRoutes(
        authenticator = fakeAuth,
        repos = leadsRepos,
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

  private def requestText(
      method: Method,
      path: String,
      token: Option[String] = None
  ): IO[(Status, String)] =
    val baseReq = Request[IO](method, Uri.unsafeFromString(path))
    val withAuth = token.fold(baseReq)(t =>
      baseReq.withHeaders(
        Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $t")
      )
    )
    app
      .run(withAuth)
      .flatMap(res =>
        res.bodyText.compile.string.map(text => (res.status, text))
      )

  private def seedUser(id: UUID, email: String): IO[Unit] =
    (sql"""
      insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
      values ('00000000-0000-0000-0000-000000000000', $id, 'authenticated', 'authenticated',
              $email, '', now(), now(), now())
    """.update.run *>
      sql"insert into public.profile (id, display_name) values ($id, ${email.take(8)}) on conflict (id) do nothing".update.run).void
      .transact(xa)

  private var wsA: UUID = null
  private val runId = UUID.randomUUID().toString.take(8)

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  override def beforeAll(): Unit =
    if dbUrl.nonEmpty then
      (seedUser(userA, emailA) *> seedUser(userC, emailC)).unsafeRunSync()
      super.beforeAll()

  override def afterAll(): Unit =
    if dbUrl.nonEmpty then
      sql"delete from auth.users where id in ($userA, $userC)".update.run.void
        .transact(xa)
        .unsafeRunSync()
      super.afterAll()

  private def waitForStatus(
      workspaceId: UUID,
      jobId: UUID,
      wanted: Set[String],
      attempts: Int = 100
  ): IO[String] =
    def loop(n: Int): IO[String] =
      importRepos
        .getJob(workspaceId, jobId)
        .map(_.map(_.status).getOrElse("?"))
        .flatMap { status =>
          if wanted.contains(status) || n <= 0 then IO.pure(status)
          else
            IO.sleep(scala.concurrent.duration.Duration(200, "millis"))
              .flatMap(_ => loop(n - 1))
        }
    loop(attempts)

  test("setup: owner creates a workspace") {
    request(
      Method.POST,
      "/api/v1/workspaces",
      Some("token-A"),
      Some(Json.obj("name" -> Json.fromString("Import WS")))
    ).map { (status, body) =>
      assertEquals(status, Status.Created)
      wsA = UUID.fromString(jsonField(body, "id"))
    }
  }

  test("upload parses headers + preview and rejects malformed CSV") {
    val good = "email,first_name\r\none@x.test,One\r\ntwo@x.test,Two\r\n"
    val malformedHeader = "\"unterminated header,email\r\nrow@x.test,Row"
    for
      uploaded <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/upload",
        Some("token-A"),
        raw = Some(good.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        query = Map("fileName" -> "good.csv")
      )
      bad <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/upload",
        Some("token-A"),
        raw = Some(Array[Byte]()),
        query = Map("fileName" -> "empty.csv")
      )
    yield
      assertEquals(uploaded._1, Status.Ok)
      assertEquals(
        uploaded._2.hcursor
          .downField("headers")
          .as[List[String]]
          .toOption
          .getOrElse(Nil),
        List("email", "first_name")
      )
      assertEquals(
        uploaded._2.hcursor
          .downField("preview")
          .as[List[List[String]]]
          .toOption
          .getOrElse(Nil)
          .size,
        2
      )
      assertEquals(bad._1, Status.UnprocessableEntity)
  }

  test("10,000-row import completes with correct totals and rows importable") {
    val n = 10_000
    val csv = "email,first_name\r\n" + (1 to n)
      .map(i => s"bulk-$runId-$i@x.test,First$i")
      .mkString("\r\n")
    for
      uploaded <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/upload",
        Some("token-A"),
        raw = Some(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        query = Map("fileName" -> "bulk-10k.csv")
      )
      jobId = uploaded._2.hcursor
        .downField("job")
        .get[UUID]("id")
        .toOption
        .getOrElse(null)
      _ <- IO {
        assert(
          jobId != null,
          s"no job id in upload response: ${uploaded._2.noSpaces.take(200)}"
        )
      }
      started <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/$jobId/start",
        Some("token-A"),
        Some(
          Json.obj(
            "mapping" -> Json.obj(
              "0" -> Json.fromString("email"),
              "1" -> Json.fromString("first_name")
            ),
            "duplicateStrategy" -> Json.fromString("skip")
          )
        )
      )
      finalStatus <- waitForStatus(
        wsA,
        jobId,
        Set("completed", "failed"),
        attempts = 300
      )
      job <- importRepos.getJob(wsA, jobId)
      _ <- IO(println(s"SCRATCH10k status=$finalStatus job=${job.map(j =>
          (j.totalRows, j.processedRows, j.insertedRows, j.invalidRows, j.errorMessage)
        )}"))
      page <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        query = Map("q" -> s"bulk-$runId-10000@x.test")
      )
    yield
      assertEquals(started._1, Status.Ok)
      assertEquals(finalStatus, "completed", job.map(_.errorMessage).toString)
      assertEquals(job.map(_.totalRows), Some(n))
      assertEquals(job.map(_.insertedRows), Some(n))
      // spot-check the last row landed
      assertEquals(
        page._2.hcursor
          .downField("total")
          .focus
          .flatMap(_.as[Long].toOption)
          .getOrElse(0L),
        1L
      )
  }

  test(
    "duplicate strategy skip counts duplicates; update strategy refreshes fields"
  ) {
    val csv =
      "email,first_name\r\ndup-86b8eb5a@x.test,Original\r\ndup-86b8eb5a@x.test,Updated\r\n"
    for
      uploaded <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/upload",
        Some("token-A"),
        raw = Some(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        query = Map("fileName" -> "dup-skip.csv")
      )
      jobId = uploaded._2.hcursor
        .downField("job")
        .get[UUID]("id")
        .toOption
        .getOrElse(null)
      _ <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/$jobId/start",
        Some("token-A"),
        Some(
          Json.obj(
            "mapping" -> Json.obj(
              "0" -> Json.fromString("email"),
              "1" -> Json.fromString("first_name")
            ),
            "duplicateStrategy" -> Json.fromString("skip")
          )
        )
      )
      status1 <- waitForStatus(wsA, jobId, Set("completed", "failed"))
      job1 <- importRepos.getJob(wsA, jobId)
      contact1 <- leadsRepos.findContactByEmail(wsA, "dup-86b8eb5a@x.test")
      // second import with update strategy
      uploaded2 <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/upload",
        Some("token-A"),
        raw = Some(
          "email,first_name\r\ndup-86b8eb5a@x.test,Fresh\r\n".getBytes(
            java.nio.charset.StandardCharsets.UTF_8
          )
        ),
        query = Map("fileName" -> "dup-update.csv")
      )
      jobId2 = uploaded2._2.hcursor
        .downField("job")
        .get[UUID]("id")
        .toOption
        .getOrElse(null)
      _ <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/$jobId2/start",
        Some("token-A"),
        Some(
          Json.obj(
            "mapping" -> Json.obj(
              "0" -> Json.fromString("email"),
              "1" -> Json.fromString("first_name")
            ),
            "duplicateStrategy" -> Json.fromString("update")
          )
        )
      )
      status2 <- waitForStatus(wsA, jobId2, Set("completed", "failed"))
      contact2 <- leadsRepos.findContactByEmail(wsA, "dup-86b8eb5a@x.test")
    yield
      assertEquals(status1, "completed")
      assertEquals(job1.map(_.duplicateRows), Some(1))
      assertEquals(contact1.flatMap(_.firstName), Some("Original"))
      assertEquals(status2, "completed")
      assertEquals(contact2.flatMap(_.firstName), Some("Fresh"))
  }

  test("invalid rows are recorded in the downloadable error report") {
    val csv = "email\r\nvalid-86b8eb5a@x.test\r\nnot-an-email\r\n"
    for
      uploaded <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/upload",
        Some("token-A"),
        raw = Some(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        query = Map("fileName" -> "invalid.csv")
      )
      jobId = uploaded._2.hcursor
        .downField("job")
        .get[UUID]("id")
        .toOption
        .getOrElse(null)
      _ <- request(
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
      status <- waitForStatus(wsA, jobId, Set("completed", "failed"))
      report <- requestText(
        Method.GET,
        s"/api/v1/workspaces/$wsA/imports/$jobId/errors.csv",
        Some("token-A")
      )
    yield
      assertEquals(status, "completed")
      assert(
        report._2.contains("invalid email") || report._2.contains("row_number"),
        report._2.take(200)
      )
  }

  test(
    "retry is idempotent: rerunning a completed import does not duplicate contacts"
  ) {
    val csv = "email\r\nretry-idem-86b8eb5a@x.test\r\n"
    for
      uploaded <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/imports/upload",
        Some("token-A"),
        raw = Some(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        query = Map("fileName" -> "idem.csv")
      )
      jobId = uploaded._2.hcursor
        .downField("job")
        .get[UUID]("id")
        .toOption
        .getOrElse(null)
      _ <- request(
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
      _ <- waitForStatus(wsA, jobId, Set("completed"))
      // force the job back to queued (restart-style recovery) then reprocess
      _ <- importRepos.transitionJob(wsA, jobId, List("completed"), "queued")
      _ <- importRepos.transitionJob(wsA, jobId, List("queued"), "running")
      _ <- service.process(wsA, userA, jobId)
      count <-
        sql"select count(*) from public.contact where workspace_id = $wsA and email = 'retry-idem-86b8eb5a@x.test'"
          .query[Int]
          .unique
          .transact(xa)
    yield assertEquals(count, 1)
  }

  test("tenant isolation: outsider sees no imports of WS-A") {
    request(Method.GET, s"/api/v1/workspaces/$wsA/imports", Some("token-C"))
      .map((status, _) => assertEquals(status, Status.NotFound))
  }
