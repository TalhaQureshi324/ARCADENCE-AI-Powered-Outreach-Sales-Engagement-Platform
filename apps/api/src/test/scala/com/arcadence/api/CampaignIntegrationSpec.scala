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

/** Campaign domain API (Phase 4.1): CRUD, lifecycle transitions with strict
  * permitted-transition rules, structural edit locking outside draft,
  * activation prerequisites, safe draft-only deletion, deep duplication,
  * idempotent enrollment into active campaigns, version snapshots, and
  * cross-tenant isolation. Runs against a real database — set TEST_DATABASE_URL
  * (local: postgresql://postgres:postgres@127.0.0.1:54322/postgres).
  */
final class CampaignIntegrationSpec extends CatsEffectSuite:

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
    s"it-4.1-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailB =
    s"it-4.1-b-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailC =
    s"it-4.1-c-${UUID.randomUUID().toString.take(8)}@arcadence.test"

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, emailA),
    "token-B" -> AuthenticatedUser(userB, emailB),
    "token-C" -> AuthenticatedUser(userC, emailC)
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val repos = new CampaignRepos(xa)
  private lazy val leadsRepos = new LeadsRepos(xa)
  private lazy val app = (
    new CampaignRoutes(
      authenticator = fakeAuth,
      repos = repos,
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

  private def emailStep(
      subject: String = "Hello",
      body: String = "Hi there"
  ): Json =
    Json.obj(
      "type" -> Json.fromString("email"),
      "name" -> Json.fromString("Intro"),
      "variants" -> Json.arr(
        Json.obj(
          "variantKey" -> Json.fromString("A"),
          "subject" -> Json.fromString(subject),
          "bodyHtml" -> Json.fromString(s"<p>$body</p>"),
          "bodyText" -> Json.fromString(body)
        )
      )
    )

  private val waitStep: Json =
    Json.obj("type" -> Json.fromString("wait"), "waitDays" -> Json.fromInt(2))

  private var wsA: UUID = null
  private var wsB: UUID = null
  private var mailboxA: UUID = null
  private var listA: UUID = null

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

  test("setup: workspaces, a sending account and a lead list exist") {
    for
      a <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Campaigns WS A")))
      )
      b <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-C"),
        Some(Json.obj("name" -> Json.fromString("Campaigns WS B")))
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
      _ <- sql"""
        insert into public.email_accounts (workspace_id, provider, email_address, display_name)
        values ($wsA, 'smtp_imap', ${s"sender-$wsA@arcadence.test"}, 'Sender')
      """.update.run.void.transact(xa)
      _ <- sql"select id from public.email_accounts where workspace_id = $wsA"
        .query[UUID]
        .unique
        .transact(xa)
        .map(id => mailboxA = id)
      list <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/lead-lists",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString(s"audience-$wsA")))
      )
      _ <- IO {
        assertEquals(list._1, Status.Created)
        listA = UUID.fromString(jsonField(list._2, "id"))
      }
    yield ()
  }

  test(
    "create: draft campaign with validation; duplicate name 409; viewer 403"
  ) {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(
          Json.obj(
            "name" -> Json.fromString("Outbound Q4"),
            "tags" -> Json.arr(Json.fromString("b2b"))
          )
        )
      )
      _ <- IO {
        assertEquals(created._1, Status.Created)
        assertEquals(jsonField(created._2, "status"), "draft")
        assertEquals(jsonField(created._2, "timezone"), "Etc/UTC")
      }
      dup <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(
          Json.obj("name" -> Json.fromString("outbound q4"))
        ) // case-insensitive
      )
      _ <- IO {
        assertEquals(dup._1, Status.Conflict)
        assertEquals(jsonField(dup._2, "code"), "conflict")
      }
      badTz <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(
          Json.obj(
            "name" -> Json.fromString("Bad TZ"),
            "timezone" -> Json.fromString("Mars/Olympus")
          )
        )
      )
      _ <- IO {
        assertEquals(badTz._1, Status.UnprocessableEntity)
        assertEquals(jsonField(badTz._2, "code"), "validation_failed")
      }
      viewer <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-B"),
        Some(Json.obj("name" -> Json.fromString("Nope")))
      )
      _ <- IO {
        assertEquals(viewer._1, Status.Forbidden)
        assertEquals(jsonField(viewer._2, "code"), "insufficient_role")
      }
    yield ()
  }

  test(
    "update draft: full structural edit; bad step shape 422; foreign refs 422"
  ) {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Draft edit")))
      )
      cid = UUID.fromString(jsonField(created._2, "id"))
      updated <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A"),
        Some(
          Json.obj(
            "description" -> Json.fromString("Sequence v1"),
            "steps" -> Json
              .arr(emailStep(), waitStep, emailStep("Follow up", "Again")),
            "mailboxIds" -> Json.arr(Json.fromString(mailboxA.toString)),
            "audiences" -> Json.arr(
              Json.obj("leadListId" -> Json.fromString(listA.toString))
            )
          )
        )
      )
      _ <- IO {
        assertEquals(updated._1, Status.Ok)
        val steps = updated._2.hcursor.downField("steps").values.toList.flatten
        assertEquals(steps.size, 3)
        assertEquals(
          steps.head.hcursor.get[String]("type").toOption.getOrElse(""),
          "email"
        )
        assertEquals(
          steps(1).hcursor.get[Int]("waitDays").toOption.getOrElse(-1),
          2
        )
        assertEquals(
          updated._2.hcursor.downField("mailboxIds").values.toList.flatten.size,
          1
        )
        assertEquals(
          updated._2.hcursor.downField("audiences").values.toList.flatten.size,
          1
        )
      }
      badWait <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A"),
        Some(
          Json.obj(
            "steps" -> Json.arr(Json.obj("type" -> Json.fromString("wait")))
          )
        )
      )
      _ <- IO {
        assertEquals(badWait._1, Status.UnprocessableEntity)
        assertEquals(jsonField(badWait._2, "code"), "validation_failed")
      }
      foreignMailbox <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A"),
        Some(
          Json.obj(
            "mailboxIds" -> Json.arr(
              Json.fromString(UUID.randomUUID().toString)
            )
          )
        )
      )
      _ <- IO {
        assertEquals(foreignMailbox._1, Status.UnprocessableEntity)
        assert(
          jsonField(foreignMailbox._2, "message").contains("sending accounts")
        )
      }
      bothAud <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A"),
        Some(
          Json.obj(
            "audiences" -> Json.arr(
              Json.obj(
                "leadListId" -> Json.fromString(listA.toString),
                "segmentId" -> Json.fromString(UUID.randomUUID().toString)
              )
            )
          )
        )
      )
      _ <- IO {
        assertEquals(bothAud._1, Status.UnprocessableEntity)
        assert(jsonField(bothAud._2, "message").contains("exactly one"))
      }
    yield ()
  }

  test("lifecycle: activation needs prerequisites; valid transitions only") {
    val suffix = "lifecycle"
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString(s"L $suffix")))
      )
      cid = UUID.fromString(jsonField(created._2, "id"))
      early <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("activate")))
      )
      _ <- IO {
        assertEquals(early._1, Status.Conflict)
        assert(jsonField(early._2, "message").contains("at least one step"))
      }
      _ <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A"),
        Some(
          Json.obj(
            "steps" -> Json.arr(emailStep()),
            "mailboxIds" -> Json.arr(Json.fromString(mailboxA.toString)),
            "audiences" -> Json.arr(
              Json.obj("leadListId" -> Json.fromString(listA.toString))
            )
          )
        )
      )
      activate <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("activate")))
      )
      _ <- IO {
        assertEquals(activate._1, Status.Ok)
        assertEquals(jsonField(activate._2, "status"), "active")
      }
      reActivate <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("activate")))
      )
      _ <- IO {
        assertEquals(reActivate._1, Status.Conflict)
        assert(
          jsonField(reActivate._2, "message").contains(
            "Cannot activate a campaign in"
          )
        )
      }
      pause <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("pause")))
      )
      resume <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("resume")))
      )
      cancel <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("cancel")))
      )
      archive <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("archive")))
      )
      _ <- IO {
        assertEquals(
          pause._2.hcursor.get[String]("status").toOption.getOrElse(""),
          "paused"
        )
        assertEquals(
          resume._2.hcursor.get[String]("status").toOption.getOrElse(""),
          "active"
        )
        assertEquals(
          cancel._2.hcursor.get[String]("status").toOption.getOrElse(""),
          "cancelled"
        )
        assertEquals(
          archive._2.hcursor.get[String]("status").toOption.getOrElse(""),
          "archived"
        )
      }
      frozen <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("activate")))
      )
      _ <- IO {
        assertEquals(frozen._1, Status.Conflict)
        assert(
          jsonField(frozen._2, "message").contains(
            "Cannot activate a campaign in"
          )
        )
      }
    yield ()
  }

  test("edit locking: structural and identity changes rejected outside draft") {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Locks")))
      )
      cid = UUID.fromString(jsonField(created._2, "id"))
      _ <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A"),
        Some(
          Json.obj(
            "steps" -> Json.arr(emailStep()),
            "mailboxIds" -> Json.arr(Json.fromString(mailboxA.toString)),
            "audiences" -> Json.arr(
              Json.obj("leadListId" -> Json.fromString(listA.toString))
            )
          )
        )
      )
      _ <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("activate")))
      )
      rename <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Renamed")))
      )
      _ <- IO {
        assertEquals(rename._1, Status.Conflict)
        assert(jsonField(rename._2, "message").contains("locked"))
      }
      restructure <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A"),
        Some(Json.obj("steps" -> Json.arr(emailStep(), waitStep)))
      )
      _ <- IO {
        assertEquals(restructure._1, Status.Conflict)
        assert(jsonField(restructure._2, "message").contains("locked"))
      }
      safeEdit <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A"),
        Some(
          Json.obj(
            "description" -> Json.fromString("Still editable"),
            "dailyCap" -> Json.fromInt(80)
          )
        )
      )
      _ <- IO {
        assertEquals(safeEdit._1, Status.Ok)
        assertEquals(
          safeEdit._2.hcursor
            .downField("campaign")
            .get[Int]("dailyCap")
            .toOption
            .getOrElse(-1),
          80
        )
      }
      // complete → fully read-only
      _ <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("complete")))
      )
      readonly <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A"),
        Some(Json.obj("description" -> Json.fromString("nope")))
      )
      _ <- IO {
        assertEquals(readonly._1, Status.Conflict)
        assert(jsonField(readonly._2, "message").contains("read-only"))
      }
    yield ()
  }

  test("enroll: only active campaigns; idempotent per contact") {
    val suffix = wsA.toString.take(8)
    for
      contact <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        Some(
          Json.obj("email" -> Json.fromString(s"enroll-$suffix@arcadence.test"))
        )
      )
      contactId = UUID.fromString(jsonField(contact._2, "id"))
      draft <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString(s"E-$suffix")))
      )
      draftId = UUID.fromString(jsonField(draft._2, "id"))
      draftEnroll <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$draftId/enroll",
        Some("token-A"),
        Some(
          Json.obj(
            "contactIds" -> Json.arr(Json.fromString(contactId.toString))
          )
        )
      )
      _ <- IO {
        assertEquals(draftEnroll._1, Status.Conflict)
        assert(jsonField(draftEnroll._2, "message").contains("active"))
      }
      _ <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$draftId",
        Some("token-A"),
        Some(
          Json.obj(
            "steps" -> Json.arr(emailStep()),
            "mailboxIds" -> Json.arr(Json.fromString(mailboxA.toString)),
            "audiences" -> Json.arr(
              Json.obj("leadListId" -> Json.fromString(listA.toString))
            )
          )
        )
      )
      _ <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$draftId/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("activate")))
      )
      first <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$draftId/enroll",
        Some("token-A"),
        Some(
          Json.obj(
            "contactIds" -> Json.arr(Json.fromString(contactId.toString))
          )
        )
      )
      again <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$draftId/enroll",
        Some("token-A"),
        Some(
          Json.obj(
            "contactIds" -> Json.arr(Json.fromString(contactId.toString))
          )
        )
      )
      rows <-
        sql"select count(*) from public.campaign_enrollment where campaign_id = $draftId"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO {
        assertEquals(first._1, Status.Ok)
        assertEquals(
          first._2.hcursor.get[Int]("enrolled").toOption.getOrElse(-1),
          1
        )
        assertEquals(
          again._2.hcursor.get[Int]("enrolled").toOption.getOrElse(-1),
          0
        )
        assertEquals(rows, 1L)
      }
    yield ()
  }

  test(
    "delete: drafts only; duplicate makes a draft copy with version snapshot"
  ) {
    val suffix = wsA.toString.take(8)
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString(s"D-$suffix")))
      )
      cid = UUID.fromString(jsonField(created._2, "id"))
      _ <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A"),
        Some(
          Json.obj(
            "steps" -> Json.arr(emailStep(subject = "D", body = "dup body")),
            "mailboxIds" -> Json.arr(Json.fromString(mailboxA.toString)),
            "audiences" -> Json.arr(
              Json.obj("leadListId" -> Json.fromString(listA.toString))
            )
          )
        )
      )
      versions1 <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/versions",
        Some("token-A")
      )
      _ <- IO {
        assertEquals(versions1._1, Status.Ok)
        assertEquals(
          versions1._2.asArray.map(_.size).getOrElse(0),
          1
        ) // structural save snapshots
      }
      copy1 <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/duplicate",
        Some("token-A")
      )
      _ <- IO {
        assertEquals(copy1._1, Status.Created)
        assertEquals(
          copy1._2.hcursor
            .downField("campaign")
            .get[String]("status")
            .toOption
            .getOrElse(""),
          "draft"
        )
        assert(
          copy1._2.hcursor
            .downField("campaign")
            .get[String]("name")
            .toOption
            .getOrElse("")
            .contains("(copy)")
        )
        assertEquals(
          copy1._2.hcursor.downField("steps").values.toList.flatten.size,
          1
        )
      }
      copy2 <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/duplicate",
        Some("token-A")
      )
      _ <- IO {
        assertEquals(copy2._1, Status.Created)
        assert(
          copy2._2.hcursor
            .downField("campaign")
            .get[String]("name")
            .toOption
            .getOrElse("")
            .contains("(copy 2)")
        )
      }
      draftDelete <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A")
      )
      gone <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-A")
      )
      _ <- IO {
        assertEquals(draftDelete._1, Status.NoContent)
        assertEquals(gone._1, Status.NotFound)
      }
      // activate-then-delete must be refused
      active <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString(s"DA-$suffix")))
      )
      activeId = UUID.fromString(jsonField(active._2, "id"))
      _ <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$activeId",
        Some("token-A"),
        Some(
          Json.obj(
            "steps" -> Json.arr(emailStep()),
            "mailboxIds" -> Json.arr(Json.fromString(mailboxA.toString)),
            "audiences" -> Json.arr(
              Json.obj("leadListId" -> Json.fromString(listA.toString))
            )
          )
        )
      )
      _ <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$activeId/transition",
        Some("token-A"),
        Some(Json.obj("action" -> Json.fromString("activate")))
      )
      refused <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$wsA/campaigns/$activeId",
        Some("token-A")
      )
      _ <- IO {
        assertEquals(refused._1, Status.Conflict)
        assert(jsonField(refused._2, "message").contains("Only drafts"))
      }
    yield ()
  }

  test("tenant isolation and viewer read access") {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Private campaign")))
      )
      cid = UUID.fromString(jsonField(created._2, "id"))
      viewerList <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-B")
      )
      viewerGet <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-B")
      )
      outsiderList <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-C")
      )
      outsiderGet <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/campaigns/$cid",
        Some("token-C")
      )
      outsiderTransition <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$cid/transition",
        Some("token-C"),
        Some(Json.obj("action" -> Json.fromString("activate")))
      )
      _ <- IO {
        assertEquals(viewerList._1, Status.Ok)
        assertEquals(viewerGet._1, Status.Ok)
        assertEquals(
          outsiderList._1,
          Status.NotFound
        ) // not a member → workspace hidden
        assertEquals(outsiderGet._1, Status.NotFound)
        assertEquals(outsiderTransition._1, Status.NotFound)
      }
    yield ()
  }
