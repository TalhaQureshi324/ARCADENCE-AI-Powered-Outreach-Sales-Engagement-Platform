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

/** Segmentation module API (Phase 3.4): saved-segment CRUD, AND/OR filter
  * preview with field whitelisting, bulk tagging (idempotent), contact merge
  * (fill-empty + move tags/lists + delete source), CSV export correctness,
  * data-quality rollups, permission gating and cross-tenant isolation. Runs
  * against a real database — set TEST_DATABASE_URL (local:
  * postgresql://postgres:postgres@127.0.0.1:54322/postgres).
  */
final class SegmentIntegrationSpec extends CatsEffectSuite:

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
    s"it-3.4-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailB =
    s"it-3.4-b-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailC =
    s"it-3.4-c-${UUID.randomUUID().toString.take(8)}@arcadence.test"

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, emailA),
    "token-B" -> AuthenticatedUser(userB, emailB),
    "token-C" -> AuthenticatedUser(userC, emailC)
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val leadsRepos = new LeadsRepos(xa)
  private lazy val segmentRepos = new SegmentRepos(xa, leadsRepos)
  private lazy val app = (
    new SegmentRoutes(
      authenticator = fakeAuth,
      repos = segmentRepos,
      leadsRepos = leadsRepos,
      authorizer = new Authorizer(xa)
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
      query: Map[String, String] = Map.empty
  ): IO[(Status, Json)] =
    requestRaw(method, path, token, body, query).map { (status, text) =>
      (status, parse(text).toOption.getOrElse(Json.Null))
    }

  private def requestRaw(
      method: Method,
      path: String,
      token: Option[String] = None,
      body: Option[Json] = None,
      query: Map[String, String] = Map.empty
  ): IO[(Status, String)] =
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
    app.run(req).flatMap(res => res.bodyText.compile.string.map(t => (res.status, t)))

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

  private def createContact(
      ws: UUID,
      email: String,
      firstName: Option[String],
      jobTitle: Option[String],
      region: Option[String]
  ): IO[UUID] =
    request(
      Method.POST,
      s"/api/v1/workspaces/$ws/contacts",
      Some("token-A"),
      Some(
        Json
          .obj(
            "email" -> Json.fromString(email),
            "firstName" -> firstName.map(Json.fromString).getOrElse(Json.Null),
            "jobTitle" -> jobTitle.map(Json.fromString).getOrElse(Json.Null),
            "region" -> region.map(Json.fromString).getOrElse(Json.Null)
          )
          .dropNullValues
      )
    ).map { (status, body) =>
      assertEquals(status, Status.Created)
      UUID.fromString(jsonField(body, "id"))
    }

  private def cond(field: String, operator: String, value: String): Json =
    Json.obj(
      "field" -> Json.fromString(field),
      "operator" -> Json.fromString(operator),
      "value" -> Json.fromString(value)
    )

  private def group(op: String, conditions: Json*): Json =
    Json.obj(
      "op" -> Json.fromString(op),
      "conditions" -> Json.fromValues(conditions)
    )

  private var wsA: UUID = null
  private var wsB: UUID = null
  private var c1: UUID = null // EU / Engineer / Ada
  private var c2: UUID = null // EU / Designer / no first name
  private var c3: UUID = null // US / Engineer / Grace

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

  test("setup: workspaces and segmented contacts exist") {
    for
      a <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Segments WS A")))
      )
      b <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-C"),
        Some(Json.obj("name" -> Json.fromString("Segments WS B")))
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
      _ <- createContact(wsA, s"seg-eu-1-$wsA@arcadence.test", Some("Ada"), Some("Engineer"), Some("EU"))
        .map(id => c1 = id)
      _ <- createContact(wsA, s"seg-eu-2-$wsA@arcadence.test", None, Some("Designer"), Some("EU"))
        .map(id => c2 = id)
      _ <- createContact(wsA, s"seg-us-1-$wsA@arcadence.test", Some("Grace"), Some("Engineer"), Some("US"))
        .map(id => c3 = id)
    yield ()
  }

  test("segment CRUD: create, get, rename, list, delete, gone") {
    val filters = group("and", cond("region", "eq", "EU"), cond("job_title", "contains", "Engineer"))
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("EU engineers"), "filters" -> filters))
      )
      _ <- IO(assertEquals(created._1, Status.Created))
      segId = UUID.fromString(jsonField(created._2, "id"))
      got <- request(Method.GET, s"/api/v1/workspaces/$wsA/segments/$segId", Some("token-A"))
      _ <- IO {
        assertEquals(got._1, Status.Ok)
        assertEquals(jsonField(got._2, "name"), "EU engineers")
      }
      renamed <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/segments/$segId",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("EU eng v2")))
      )
      _ <- IO {
        assertEquals(renamed._1, Status.Ok)
        assertEquals(jsonField(renamed._2, "name"), "EU eng v2")
      }
      listed <- request(Method.GET, s"/api/v1/workspaces/$wsA/segments", Some("token-A"))
      _ <- IO {
        assertEquals(listed._1, Status.Ok)
        assert(listed._2.asArray.exists(_.exists(_.hcursor.get[String]("id").exists(_ == segId.toString))))
      }
      deleted <- request(Method.DELETE, s"/api/v1/workspaces/$wsA/segments/$segId", Some("token-A"))
      gone <- request(Method.GET, s"/api/v1/workspaces/$wsA/segments/$segId", Some("token-A"))
      _ <- IO {
        assertEquals(deleted._1, Status.NoContent)
        assertEquals(gone._1, Status.NotFound)
      }
    yield ()
  }

  test("AND-group preview matches only contacts satisfying every condition") {
    val filters = group("and", cond("region", "eq", "EU"), cond("job_title", "contains", "Engineer"))
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("AND seg"), "filters" -> filters))
      )
      segId = UUID.fromString(jsonField(created._2, "id"))
      preview <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments/$segId/preview",
        Some("token-A")
      )
      _ <- IO {
        assertEquals(preview._1, Status.Ok)
        assertEquals(preview._2.hcursor.get[Long]("count").toOption.getOrElse(-1L), 1L)
        val sampleEmails =
          preview._2.hcursor.downField("sample").values.toList.flatten
            .flatMap(_.hcursor.get[String]("email").toOption)
        assertEquals(sampleEmails, List(s"seg-eu-1-$wsA@arcadence.test"))
      }
    yield ()
  }

  test("OR-group preview unions matching branches") {
    val filters = group("or", cond("region", "eq", "EU"), cond("region", "eq", "US"))
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("OR seg"), "filters" -> filters))
      )
      segId = UUID.fromString(jsonField(created._2, "id"))
      preview <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments/$segId/preview",
        Some("token-B") // viewer can preview (view permission)
      )
      _ <- IO {
        assertEquals(preview._1, Status.Ok)
        assertEquals(preview._2.hcursor.get[Long]("count").toOption.getOrElse(-1L), 3L)
      }
    yield ()
  }

  test("filter whitelist: unknown field or operator is rejected with validation_failed") {
    val badField = group("and", cond("password_hash", "eq", "x"))
    val badOp = group("and", cond("region", "drop table", "x"))
    for
      r1 <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("bad field"), "filters" -> badField))
      )
      r2 <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("bad op"), "filters" -> badOp))
      )
      _ <- IO {
        assertEquals(r1._1, Status.UnprocessableEntity)
        assertEquals(jsonField(r1._2, "code"), "validation_failed")
        assertEquals(r2._1, Status.UnprocessableEntity)
        assertEquals(jsonField(r2._2, "code"), "validation_failed")
      }
    yield ()
  }

  test("bulk tag applies to the member set and is idempotent") {
    val filters = group("or", cond("region", "eq", "EU"), cond("region", "eq", "US"))
    for
      tag <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/tags",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString(s"hot-$wsA")))
      )
      tagId = UUID.fromString(jsonField(tag._2, "id"))
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("bulk seg"), "filters" -> filters))
      )
      segId = UUID.fromString(jsonField(created._2, "id"))
      first <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments/$segId/bulk-tag",
        Some("token-A"),
        Some(Json.obj("segmentId" -> Json.fromString(segId.toString), "tagId" -> Json.fromString(tagId.toString)))
      )
      again <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments/$segId/bulk-tag",
        Some("token-A"),
        Some(Json.obj("segmentId" -> Json.fromString(segId.toString), "tagId" -> Json.fromString(tagId.toString)))
      )
      rows <- sql"select count(*) from public.contact_tag where tag_id = $tagId"
        .query[Long].unique.transact(xa)
      _ <- IO {
        assertEquals(first._1, Status.Ok)
        assertEquals(first._2.hcursor.get[Int]("tagged").toOption.getOrElse(-1), 3)
        assertEquals(again._2.hcursor.get[Int]("tagged").toOption.getOrElse(-1), 0)
        assertEquals(rows, 3L)
      }
    yield ()
  }

  test("merge fills empty kept fields, moves tags, deletes the source") {
    val suffix = wsA.toString.take(8)
    for
      kept <- createContact(wsA, s"merge-kept-$suffix@arcadence.test", Some("Kept"), None, None)
      source <- createContact(wsA, s"merge-src-$suffix@arcadence.test", Some("Source"), Some("Engineer"), Some("US"))
      tag <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/tags",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString(s"merge-tag-$suffix")))
      )
      tagId = UUID.fromString(jsonField(tag._2, "id"))
      _ <- sql"insert into public.contact_tag (contact_id, tag_id) values ($source, $tagId)".update.run.void.transact(xa)
      list <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/lead-lists",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString(s"merge-list-$suffix")))
      )
      listId = UUID.fromString(jsonField(list._2, "id"))
      _ <- sql"insert into public.list_membership (list_id, contact_id) values ($listId, $source)".update.run.void.transact(xa)
      merged <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts/$kept/merge",
        Some("token-A"),
        Some(Json.obj("sourceContactId" -> Json.fromString(source.toString)))
      )
      _ <- IO {
        assertEquals(merged._1, Status.Ok)
        assertEquals(merged._2.hcursor.get[Int]("filledFields").toOption.getOrElse(-1), 2) // job_title + region
        assertEquals(merged._2.hcursor.get[Int]("movedTags").toOption.getOrElse(-1), 1)
        assertEquals(merged._2.hcursor.get[Int]("movedListMemberships").toOption.getOrElse(-1), 1)
      }
      sourceGone <- request(Method.GET, s"/api/v1/workspaces/$wsA/contacts/$source", Some("token-A"))
      keptNow <- request(Method.GET, s"/api/v1/workspaces/$wsA/contacts/$kept", Some("token-A"))
      tagRows <- sql"select count(*) from public.contact_tag where contact_id = $kept and tag_id = $tagId"
        .query[Long].unique.transact(xa)
      listRows <- sql"select count(*) from public.list_membership where contact_id = $kept and list_id = $listId"
        .query[Long].unique.transact(xa)
      _ <- IO {
        assertEquals(sourceGone._1, Status.NotFound)
        assertEquals(keptNow._1, Status.Ok)
        assertEquals(jsonField(keptNow._2, "jobTitle"), "Engineer")
        assertEquals(jsonField(keptNow._2, "email"), s"merge-kept-$suffix@arcadence.test")
        assertEquals(tagRows, 1L)
        assertEquals(listRows, 1L)
      }
      selfMerge <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts/$kept/merge",
        Some("token-A"),
        Some(Json.obj("sourceContactId" -> Json.fromString(kept.toString)))
      )
      _ <- IO {
        assertEquals(selfMerge._1, Status.UnprocessableEntity)
        assertEquals(jsonField(selfMerge._2, "code"), "validation_failed")
      }
    yield ()
  }

  test("export CSV: header plus every member email, no other tenant rows") {
    // EU-only: the merge test leaves its kept contact with region US, which
    // must NOT leak into this export.
    val filters = group("and", cond("region", "eq", "EU"))
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("export seg"), "filters" -> filters))
      )
      segId = UUID.fromString(jsonField(created._2, "id"))
      exported <- requestRaw(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments/$segId/export",
        Some("token-A")
      )
      csv = exported._2
      _ <- IO {
        assertEquals(exported._1, Status.Ok)
        assert(csv.toLowerCase.contains("email"))
        assert(csv.contains(s"seg-eu-1-$wsA@arcadence.test"))
        assert(csv.contains(s"seg-eu-2-$wsA@arcadence.test"))
        assert(!csv.contains(s"seg-us-1-$wsA@arcadence.test"))
        // header + 2 data rows (RFC4180; values here contain no embedded newlines)
        assertEquals(csv.trim.linesIterator.size, 3)
      }
    yield ()
  }

  test("data-quality endpoint returns rollups for the workspace") {
    for
      dq <- request(Method.GET, s"/api/v1/workspaces/$wsA/contacts/data-quality", Some("token-A"))
      _ <- IO {
        assertEquals(dq._1, Status.Ok)
        val total = dq._2.hcursor.get[Long]("total").toOption.getOrElse(-1L)
        assert(total >= 4, s"expected >=4 contacts, got $total")
        assert(dq._2.hcursor.get[Long]("missingName").toOption.exists(_ >= 1))
      }
    yield ()
  }

  test("tenant isolation: outsiders cannot touch WS-A segments; viewer cannot mutate") {
    val filters = group("and", cond("region", "eq", "EU"))
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("private seg"), "filters" -> filters))
      )
      segId = UUID.fromString(jsonField(created._2, "id"))
      outsiderList <- request(Method.GET, s"/api/v1/workspaces/$wsA/segments", Some("token-C"))
      outsiderGet <- request(Method.GET, s"/api/v1/workspaces/$wsA/segments/$segId", Some("token-C"))
      outsiderExport <- requestRaw(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments/$segId/export",
        Some("token-C")
      )
      viewerCreate <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/segments",
        Some("token-B"),
        Some(Json.obj("name" -> Json.fromString("nope"), "filters" -> filters))
      )
      viewerDelete <- request(Method.DELETE, s"/api/v1/workspaces/$wsA/segments/$segId", Some("token-B"))
      _ <- IO {
        assertEquals(outsiderList._1, Status.NotFound) // not a member → workspace hidden
        assertEquals(outsiderGet._1, Status.NotFound)
        assertEquals(outsiderExport._1, Status.NotFound)
        assertEquals(viewerCreate._1, Status.Forbidden)
        assertEquals(jsonField(viewerCreate._2, "code"), "insufficient_role")
        assertEquals(viewerDelete._1, Status.Forbidden)
      }
    yield ()
  }
