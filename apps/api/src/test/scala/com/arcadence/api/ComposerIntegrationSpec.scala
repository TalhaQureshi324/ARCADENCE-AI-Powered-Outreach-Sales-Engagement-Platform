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

/** Composer API (Phase 4.2): draft-only variant content editing, preview
  * against a real contact with missing-variable reporting and HTML
  * sanitization, saved templates and reusable blocks with per-workspace
  * uniqueness and tenant isolation.
  */
final class ComposerIntegrationSpec extends CatsEffectSuite:

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

  private val userA = UUID.randomUUID()
  private val userC = UUID.randomUUID()
  private val emailA =
    s"it-4.2-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailC =
    s"it-4.2-c-${UUID.randomUUID().toString.take(8)}@arcadence.test"

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, emailA),
    "token-C" -> AuthenticatedUser(userC, emailC)
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val campaignRepos = new CampaignRepos(xa)
  private lazy val leadsRepos = new LeadsRepos(xa)
  private lazy val emailAccounts = new EmailAccountRepos(xa)
  private lazy val app = (
    new CampaignRoutes(
      authenticator = fakeAuth,
      repos = campaignRepos,
      authorizer = new Authorizer(xa)
    ).http
      <+> new ComposerRoutes(
        authenticator = fakeAuth,
        repos = campaignRepos,
        leadsRepos = leadsRepos,
        emailAccounts = emailAccounts,
        authorizer = new Authorizer(xa),
        registry = None
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
      body: Option[Json] = None
  ): IO[(Status, Json)] =
    val base = Uri.unsafeFromString(path)
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
        res.bodyText.compile.string
          .map(t => (res.status, parse(t).toOption.getOrElse(Json.Null)))
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
  private var campaignId: UUID = null
  private var stepId: UUID = null
  private var contactId: UUID = null

  private def emailStep(subject: String, body: String): Json =
    Json.obj(
      "type" -> Json.fromString("email"),
      "name" -> Json.fromString("Intro"),
      "variants" -> Json.arr(
        Json.obj(
          "variantKey" -> Json.fromString("A"),
          "subject" -> Json.fromString(subject),
          "bodyHtml" -> Json.fromString(body),
          "bodyText" -> Json.fromString(body)
        )
      )
    )

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

  test("setup: workspace, campaign with an email step, sample contact") {
    for
      ws <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Composer WS")))
      )
      wsB2 <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-C"),
        Some(Json.obj("name" -> Json.fromString("Composer WS B")))
      )
      _ <- IO {
        assertEquals(ws._1, Status.Created)
        assertEquals(wsB2._1, Status.Created)
        wsA = UUID.fromString(jsonField(ws._2, "id"))
        wsB = UUID.fromString(jsonField(wsB2._2, "id"))
      }
      campaign <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Composer campaign")))
      )
      _ <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/${jsonField(campaign._2, "id")}",
        Some("token-A"),
        Some(Json.obj("steps" -> Json.arr(emailStep("Hello", "<p>Body</p>"))))
      )
      detail <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/campaigns/${jsonField(campaign._2, "id")}",
        Some("token-A")
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
      _ <- IO(assertEquals(field._1, Status.Created))
      contact <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/contacts",
        Some("token-A"),
        Some(
          Json.obj(
            "email" -> Json.fromString("sample@arcadence.test"),
            "firstName" -> Json.fromString("Ada"),
            "customFields" -> Json.obj("industry" -> Json.fromString("SaaS"))
          )
        )
      )
      _ <- IO {
        assertEquals(detail._1, Status.Ok)
        campaignId = UUID.fromString(jsonField(campaign._2, "id"))
        stepId = UUID.fromString(
          detail._2.hcursor
            .downField("steps")
            .values
            .toList
            .flatten
            .head
            .hcursor
            .get[String]("id")
            .toOption
            .getOrElse("")
        )
        assertEquals(contact._1, Status.Created)
        contactId = UUID.fromString(jsonField(contact._2, "id"))
      }
    yield ()
  }

  test("variant content: PUT saves, autosave-friendly; draft-only guard") {
    for
      saved <- request(
        Method.PUT,
        s"/api/v1/workspaces/$wsA/campaigns/$campaignId/steps/$stepId/variants/A",
        Some("token-A"),
        Some(
          Json.obj(
            "subject" -> Json.fromString("Hi {{first_name|there}}"),
            "preheader" -> Json.fromString("Preview text"),
            "bodyHtml" -> Json.fromString(
              "<p>Body for {{first_name}} at {{custom.industry}}</p><script>x</script>"
            ),
            "bodyText" -> Json.fromString("Body for {{first_name}}"),
            "signature" -> Json.fromString("<p>— The Team</p>")
          )
        )
      )
      _ <- IO {
        assertEquals(saved._1, Status.Ok)
        assertEquals(
          saved._2.hcursor
            .downField("variant")
            .get[String]("subject")
            .toOption
            .getOrElse(""),
          "Hi {{first_name|there}}"
        )
        // html sanitized on the way IN
        val html = saved._2.hcursor
          .downField("variant")
          .get[String]("bodyHtml")
          .toOption
          .getOrElse("")
        assert(!html.contains("<script>"), html)
        val validation = saved._2.hcursor.downField("validation")
        assertEquals(
          validation
            .downField("tokens")
            .values
            .toList
            .flatten
            .map(_.hcursor.get[String]("field").toOption.getOrElse("")),
          List("first_name", "custom.industry")
        )
      }
      // activate prerequisite blocked earlier; move to active via full prereqs is not
      // needed here — draft-only guard is enforced by status, so flip status directly.
      _ <-
        sql"update public.campaign set status = 'active', activated_at = now() where id = $campaignId".update.run.void
          .transact(xa)
      blocked <- request(
        Method.PUT,
        s"/api/v1/workspaces/$wsA/campaigns/$campaignId/steps/$stepId/variants/A",
        Some("token-A"),
        Some(
          Json.obj(
            "subject" -> Json.fromString("nope"),
            "preheader" -> Json.fromString(""),
            "bodyHtml" -> Json.fromString("<p>x</p>"),
            "bodyText" -> Json.fromString("x"),
            "signature" -> Json.fromString("")
          )
        )
      )
      _ <- IO {
        assertEquals(blocked._1, Status.Conflict)
        assert(jsonField(blocked._2, "message").contains("draft"))
      }
      _ <-
        sql"update public.campaign set status = 'draft' where id = $campaignId".update.run.void
          .transact(xa)
    yield ()
  }

  test(
    "preview: renders against the real contact, reports missing, sanitizes"
  ) {
    for
      preview <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$campaignId/preview",
        Some("token-A"),
        Some(
          Json.obj(
            "stepId" -> Json.fromString(stepId.toString),
            "variantKey" -> Json.fromString("A"),
            "contactId" -> Json.fromString(contactId.toString)
          )
        )
      )
      _ <- IO {
        assertEquals(preview._1, Status.Ok)
        assertEquals(
          jsonField(preview._2, "subject"),
          "Hi Ada" // fallback applied for empty? no: first_name present
        )
        val html = jsonField(preview._2, "html")
        assert(html.contains("Body for Ada at SaaS"), html)
        assert(!html.contains("<script>"))
        val report = preview._2.hcursor.downField("report")
        val missing = report
          .downField("missing")
          .values
          .toList
          .flatten
          .map(_.asString.getOrElse(""))
        assert(missing.isEmpty, missing.toString)
        assertEquals(
          report.get[Boolean]("sanitized").toOption.getOrElse(false),
          true
        )
      }
      // token the contact lacks (last_name) must be reported, never fabricated
      _ <- request(
        Method.PUT,
        s"/api/v1/workspaces/$wsA/campaigns/$campaignId/steps/$stepId/variants/A",
        Some("token-A"),
        Some(
          Json.obj(
            "subject" -> Json.fromString("Yo {{last_name}}"),
            "preheader" -> Json.fromString(""),
            "bodyHtml" -> Json.fromString("<p>x</p>"),
            "bodyText" -> Json.fromString(""),
            "signature" -> Json.fromString("")
          )
        )
      )
      preview2 <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$campaignId/preview",
        Some("token-A"),
        Some(
          Json.obj(
            "stepId" -> Json.fromString(stepId.toString),
            "variantKey" -> Json.fromString("A"),
            "contactId" -> Json.fromString(contactId.toString)
          )
        )
      )
      _ <- IO {
        assertEquals(preview2._1, Status.Ok)
        assertEquals(
          jsonField(preview2._2, "subject"),
          "Yo "
        ) // empty, NOT fabricated
        val missing = preview2._2.hcursor
          .downField("report")
          .downField("missing")
          .values
          .toList
          .flatten
          .map(_.asString.getOrElse(""))
        assertEquals(missing, List("last_name"))
      }
      // tenant isolation: outsider's workspace cannot see campaign or contact
      outsider <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$campaignId/preview",
        Some("token-C"),
        Some(
          Json.obj(
            "stepId" -> Json.fromString(stepId.toString),
            "variantKey" -> Json.fromString("A"),
            "contactId" -> Json.fromString(contactId.toString)
          )
        )
      )
      _ <- IO { assertEquals(outsider._1, Status.NotFound); }
    yield ()
  }

  test(
    "templates and blocks: CRUD with per-workspace uniqueness and isolation"
  ) {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/email-templates",
        Some("token-A"),
        Some(
          Json.obj(
            "name" -> Json.fromString("Cold intro"),
            "subject" -> Json.fromString("Hi {{first_name}}"),
            "preheader" -> Json.fromString(""),
            "bodyHtml" -> Json.fromString("<p>Template body</p>"),
            "bodyText" -> Json.fromString("Template body")
          )
        )
      )
      _ <- IO {
        assertEquals(created._1, Status.Created)
        assertEquals(jsonField(created._2, "name"), "Cold intro")
      }
      dup <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/email-templates",
        Some("token-A"),
        Some(
          Json.obj(
            "name" -> Json.fromString("cold intro"),
            "subject" -> Json.fromString(""),
            "preheader" -> Json.fromString(""),
            "bodyHtml" -> Json.fromString(""),
            "bodyText" -> Json.fromString("")
          )
        )
      )
      _ <- IO { assertEquals(dup._1, Status.Conflict); }
      listed <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/email-templates",
        Some("token-A")
      )
      _ <- IO { assertEquals(listed._2.asArray.map(_.size).getOrElse(-1), 1); }
      outsiderList <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/email-templates",
        Some("token-C")
      )
      _ <- IO { assertEquals(outsiderList._1, Status.NotFound); }
      // other workspace can reuse the same name
      other <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsB/email-templates",
        Some("token-C"),
        Some(
          Json.obj(
            "name" -> Json.fromString("Cold intro"),
            "subject" -> Json.fromString(""),
            "preheader" -> Json.fromString(""),
            "bodyHtml" -> Json.fromString(""),
            "bodyText" -> Json.fromString("")
          )
        )
      )
      _ <- IO { assertEquals(other._1, Status.Created); }
      block <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/reusable-blocks",
        Some("token-A"),
        Some(
          Json.obj(
            "name" -> Json.fromString("Cal link"),
            "bodyHtml" -> Json.fromString(
              "<p><a href='https://cal.example/me'>Book</a></p>"
            ),
            "bodyText" -> Json.fromString("Book: https://cal.example/me")
          )
        )
      )
      _ <- IO {
        assertEquals(block._1, Status.Created)
        assertEquals(jsonField(block._2, "name"), "Cal link")
      }
      blocks <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/reusable-blocks",
        Some("token-A")
      )
      _ <- IO { assertEquals(blocks._2.asArray.map(_.size).getOrElse(-1), 1); }
    yield ()
  }
