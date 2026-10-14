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

/** Lead module API (Phase 3.1): contact CRUD with email normalization + dedup,
  * cursor pagination stability, batch semantics, custom-field validation,
  * companies/lists/tags/fields CRUD, permission gating and cross-tenant
  * isolation. Runs against a real database — set TEST_DATABASE_URL (local:
  * postgresql://postgres:postgres@127.0.0.1:54322/postgres).
  */
final class LeadsIntegrationSpec extends CatsEffectSuite:

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

  private val userA = UUID.randomUUID() // owner of WS-A
  private val userB = UUID.randomUUID() // viewer in WS-A
  private val userC = UUID.randomUUID() // owner of WS-B (outsider)
  private val emailA =
    s"it-3.1-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailB =
    s"it-3.1-b-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailC =
    s"it-3.1-c-${UUID.randomUUID().toString.take(8)}@arcadence.test"

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, emailA),
    "token-B" -> AuthenticatedUser(userB, emailB),
    "token-C" -> AuthenticatedUser(userC, emailC)
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val repos = new LeadsRepos(xa)
  private lazy val app = (
    new ContactRoutes(
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
      token: Option[String] = None,
      body: Option[Json] = None,
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

  private def seedUser(id: UUID, email: String): IO[Unit] =
    (sql"""
      insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
      values ('00000000-0000-0000-0000-000000000000', $id, 'authenticated', 'authenticated',
              $email, '', now(), now(), now())
    """.update.run *>
      sql"insert into public.profile (id, display_name) values ($id, ${email.take(8)}) on conflict (id) do nothing".update.run).void
      .transact(xa)

  private var wsA: UUID = null
  private var wsB: UUID = null

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
      )).unsafeRunSync()
      super.beforeAll()

  override def afterAll(): Unit =
    if dbUrl.nonEmpty then
      sql"delete from auth.users where id in ($userA, $userB, $userC)".update.run.void
        .transact(xa)
        .unsafeRunSync()
      super.afterAll()

  test("setup: owners create workspaces; viewer seeded into WS-A") {
    for
      a <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Leads WS A")))
      )
      b <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-C"),
        Some(Json.obj("name" -> Json.fromString("Leads WS B")))
      )
      _ <- IO {
        assertEquals(a._1, Status.Created)
        assertEquals(b._1, Status.Created)
        wsA = UUID.fromString(jsonField(a._2, "id"))
        wsB = UUID.fromString(jsonField(b._2, "id"))
      }
      _ <- sql"""
        insert into public.workspace_member (workspace_id, user_id, role)
        values ($wsA, $userB, 'viewer')
      """.update.run.void.transact(xa)
    yield ()
  }

  test("custom field must exist before its values can be used") {
    for
      rejected <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        Some(
          Json.obj(
            "email" -> Json.fromString("cf@x.test"),
            "customFields" -> Json.obj("unknown_field" -> Json.fromString("v"))
          )
        )
      )
      field <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/custom-fields",
        Some("token-A"),
        Some(
          Json.obj(
            "name" -> Json.fromString("industry"),
            "fieldType" -> Json.fromString("text")
          )
        )
      )
      accepted <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        Some(
          Json.obj(
            "email" -> Json.fromString("cf@x.test"),
            "customFields" -> Json.obj("industry" -> Json.fromString("SaaS"))
          )
        )
      )
    yield
      assertEquals(rejected._1, Status.UnprocessableEntity)
      assertEquals(field._1, Status.Created)
      assertEquals(accepted._1, Status.Created)
      assertEquals(jsonField(accepted._2, "verificationStatus"), "unknown")
  }

  test(
    "emails are normalized (lowercase) with display value retained; duplicates conflict"
  ) {
    for
      first <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        Some(
          Json.obj(
            "email" -> Json.fromString("Mixed.Case@X.test"),
            "firstName" -> Json.fromString("Mixed")
          )
        )
      )
      duplicate <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        Some(Json.obj("email" -> Json.fromString("mixed.case@x.test")))
      )
    yield
      assertEquals(first._1, Status.Created)
      assertEquals(jsonField(first._2, "email"), "mixed.case@x.test")
      assertEquals(jsonField(first._2, "emailDisplay"), "Mixed.Case@X.test")
      assertEquals(duplicate._1, Status.Conflict)
  }

  test("viewer can read but cannot create contacts (403)") {
    for
      allowed <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-B")
      )
      forbidden <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-B"),
        Some(Json.obj("email" -> Json.fromString("v@x.test")))
      )
    yield
      assertEquals(allowed._1, Status.Ok)
      assertEquals(forbidden._1, Status.Forbidden)
  }

  test("outsider sees 404 on another workspace's contacts") {
    request(
      Method.GET,
      s"/api/v1/workspaces/$wsA/contacts",
      Some("token-C")
    ).map((status, _) => assertEquals(status, Status.NotFound))
  }

  test(
    "batch: creates new contacts and skips existing, batch rejects invalid emails"
  ) {
    for
      result <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts/batch",
        Some("token-A"),
        Some(
          Json.obj(
            "contacts" -> Json.arr(
              Json.obj(
                "email" -> Json.fromString("batch-1@x.test"),
                "firstName" -> Json.fromString("B1")
              ),
              Json.obj("email" -> Json.fromString("cf@x.test")),
              Json.obj("email" -> Json.fromString("not-an-email"))
            )
          )
        )
      )
      after <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        query = Map("q" -> "batch-1@x.test")
      )
    yield
      assertEquals(result._1, Status.UnprocessableEntity)
      assertEquals(after._1, Status.Ok)
      assertEquals(
        after._2.hcursor
          .downField("total")
          .focus
          .flatMap(_.as[Long].toOption)
          .getOrElse(1L),
        0L
      )
  }

  test("batch valid subset: creates and skips, returns per-item outcomes") {
    for result <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts/batch",
        Some("token-A"),
        Some(
          Json.obj(
            "contacts" -> Json.arr(
              Json.obj("email" -> Json.fromString("batch-2@x.test")),
              Json.obj("email" -> Json.fromString("cf@x.test"))
            )
          )
        )
      )
    yield
      assertEquals(result._1, Status.Ok)
      assertEquals(
        result._2.hcursor
          .downField("created")
          .focus
          .flatMap(_.as[Int].toOption)
          .getOrElse(0),
        1
      )
      assertEquals(
        result._2.hcursor
          .downField("skipped")
          .focus
          .flatMap(_.as[Int].toOption)
          .getOrElse(0),
        1
      )
  }

  test("cursor pagination is stable and does not repeat rows") {
    // create 6 distinct contacts
    val create = (1 to 6).toList.traverse { i =>
      request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        Some(Json.obj("email" -> Json.fromString(s"page-$i@x.test")))
      )
    }
    for
      _ <- create
      page1 <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        query = Map("limit" -> "3", "sort" -> "email_asc")
      )
      cursor = jsonField(page1._2, "nextCursor")
      page2 <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        query = Map("limit" -> "3", "sort" -> "email_asc", "cursor" -> cursor)
      )
    yield
      assertEquals(page1._1, Status.Ok)
      val p1 = page1._2.hcursor
        .downField("items")
        .as[List[Json]]
        .toOption
        .getOrElse(Nil)
      val p2 = page2._2.hcursor
        .downField("items")
        .as[List[Json]]
        .toOption
        .getOrElse(Nil)
      assertEquals(p1.size, 3)
      assert(cursor.nonEmpty, "expected a next cursor")
      val emails1 =
        p1.map(_.hcursor.get[String]("email").toOption.getOrElse("")).toSet
      val emails2 =
        p2.map(_.hcursor.get[String]("email").toOption.getOrElse("")).toSet
      assertEquals(
        emails1.intersect(emails2),
        Set.empty,
        "pages must not overlap"
      )
  }

  test(
    "companies, lists, membership and tags round-trip; provenance records manual source"
  ) {
    val company =
      for created <- request(
          Method.POST,
          s"/api/v1/workspaces/$wsA/companies",
          Some("token-A"),
          Some(
            Json.obj(
              "name" -> Json.fromString("Globex"),
              "domain" -> Json.fromString("globex.test")
            )
          )
        )
      yield created

    company.flatMap { created =>
      val companyId = jsonField(created._2, "id")
      for
        list <- request(
          Method.POST,
          s"/api/v1/workspaces/$wsA/lead-lists",
          Some("token-A"),
          Some(Json.obj("name" -> Json.fromString("Q3 founders")))
        )
        listId = jsonField(list._2, "id")
        contact <- request(
          Method.POST,
          s"/api/v1/workspaces/$wsA/contacts",
          Some("token-A"),
          Some(
            Json.obj(
              "email" -> Json.fromString("tagged@x.test"),
              "companyId" -> Json.fromString(companyId)
            )
          )
        )
        contactId = jsonField(contact._2, "id")
        tag <- request(
          Method.POST,
          s"/api/v1/workspaces/$wsA/tags",
          Some("token-A"),
          Some(Json.obj("name" -> Json.fromString("warm")))
        )
        tagId = jsonField(tag._2, "id")
        tagged <- request(
          Method.POST,
          s"/api/v1/workspaces/$wsA/contacts/$contactId/tags",
          Some("token-A"),
          Some(Json.obj("tagIds" -> Json.arr(Json.fromString(tagId))))
        )
        byTag <- request(
          Method.GET,
          s"/api/v1/workspaces/$wsA/contacts",
          Some("token-A"),
          query = Map("tagId" -> tagId)
        )
        added <- request(
          Method.POST,
          s"/api/v1/workspaces/$wsA/lead-lists/$listId/members",
          Some("token-A"),
          Some(Json.obj("contactIds" -> Json.arr(Json.fromString(contactId))))
        )
        members <- request(
          Method.GET,
          s"/api/v1/workspaces/$wsA/lead-lists/$listId/members",
          Some("token-A")
        )
        provenance <- request(
          Method.GET,
          s"/api/v1/workspaces/$wsA/contacts/$contactId/provenance",
          Some("token-A")
        )
      yield
        assertEquals(created._1, Status.Created)
        assertEquals(jsonField(contact._2, "companyName"), "Globex")
        assertEquals(tagged._1, Status.NoContent)
        assertEquals(
          byTag._2.hcursor
            .downField("total")
            .focus
            .flatMap(_.as[Long].toOption)
            .getOrElse(0L),
          1L
        )
        assertEquals(
          added._2.hcursor
            .downField("added")
            .focus
            .flatMap(_.as[Int].toOption)
            .getOrElse(0),
          1
        )
        assertEquals(
          members._2.hcursor
            .downField("items")
            .as[List[Json]]
            .toOption
            .getOrElse(Nil)
            .size,
          1
        )
        val sources = provenance._2.asArray
          .getOrElse(Vector.empty)
          .map(_.hcursor.get[String]("source").toOption.getOrElse(""))
        assert(sources.contains("manual"), provenance._2.noSpaces)
    }
  }

  test("PATCH clears fields with explicit null; contact delete removes it") {
    for
      contact <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        Some(
          Json.obj(
            "email" -> Json.fromString("patch-me@x.test"),
            "phone" -> Json.fromString("+1 555")
          )
        )
      )
      contactId = jsonField(contact._2, "id")
      patched <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/contacts/$contactId",
        Some("token-A"),
        Some(
          Json.obj("phone" -> Json.Null, "jobTitle" -> Json.fromString("CTO"))
        )
      )
      deleted <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$wsA/contacts/$contactId",
        Some("token-A")
      )
      gone <- repos.findContact(wsA, UUID.fromString(contactId))
    yield
      assertEquals(patched._1, Status.Ok)
      assert(patched._2.hcursor.downField("phone").focus.exists(_.isNull))
      assertEquals(jsonField(patched._2, "jobTitle"), "CTO")
      assertEquals(deleted._1, Status.NoContent)
      assertEquals(gone, None)
  }

  test("workspace B stays empty — cross-tenant isolation end state") {
    request(Method.GET, s"/api/v1/workspaces/$wsB/contacts", Some("token-C"))
      .map { (status, body) =>
        assertEquals(status, Status.Ok)
        assertEquals(
          body.hcursor
            .downField("total")
            .focus
            .flatMap(_.as[Long].toOption)
            .getOrElse(1L),
          0L
        )
      }
  }
