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

/** Sequence graph API (Phase 4.3): validated graph saves with stable client
  * keys (step UUIDs preserved across saves), branch/task/add_tag steps,
  * validate-graph endpoint, activation gating on stored graphs, and edge
  * duplication.
  */
final class SequenceGraphIntegrationSpec extends CatsEffectSuite:

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
    s"it-4.3-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailC =
    s"it-4.3-c-${UUID.randomUUID().toString.take(8)}@arcadence.test"

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, emailA),
    "token-C" -> AuthenticatedUser(userC, emailC)
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val campaignRepos = new CampaignRepos(xa)
  private lazy val leadsRepos = new LeadsRepos(xa)
  private lazy val app = (
    new CampaignRoutes(
      authenticator = fakeAuth,
      repos = campaignRepos,
      leadsRepos = leadsRepos,
      emailAccounts = new EmailAccountRepos(xa),
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

  private def emailStepJson(key: String, subject: String): Json =
    Json.obj(
      "type" -> Json.fromString("email"),
      "key" -> Json.fromString(key),
      "name" -> Json.fromString(s"Step $key"),
      "variants" -> Json.arr(
        Json.obj(
          "variantKey" -> Json.fromString("A"),
          "subject" -> Json.fromString(subject)
        )
      )
    )

  private def waitStepJson(
      key: String,
      days: Int,
      business: Boolean = false
  ): Json =
    Json.obj(
      "type" -> Json.fromString("wait"),
      "key" -> Json.fromString(key),
      "waitDays" -> Json.fromInt(days),
      "businessDays" -> Json.fromBoolean(business)
    )

  private def branchStepJson(
      key: String,
      field: String,
      op: String,
      value: String
  ): Json =
    Json.obj(
      "type" -> Json.fromString("branch"),
      "key" -> Json.fromString(key),
      "condition" -> Json.obj(
        "field" -> Json.fromString(field),
        "operator" -> Json.fromString(op),
        "value" -> Json.fromString(value)
      )
    )

  private def taskStepJson(key: String, due: Int): Json =
    Json.obj(
      "type" -> Json.fromString("task"),
      "key" -> Json.fromString(key),
      "taskDueDays" -> Json.fromInt(due)
    )

  private def edge(
      from: String,
      to: String,
      branch: Option[String] = None
  ): Json = {
    val fields = scala.collection.mutable.ListBuffer(
      "from" -> Json.fromString(from),
      "to" -> Json.fromString(to)
    )
    branch.foreach(b => fields += ("branchKey" -> Json.fromString(b)))
    Json.obj(fields.toList*)
  }

  private var wsA: UUID = null
  private var wsB: UUID = null
  private var campaignId: UUID = null

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

  test("setup: workspaces") {
    for
      ws <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Graph WS")))
      )
      wsB2 <- request(
        Method.POST,
        "/api/v1/workspaces",
        Some("token-C"),
        Some(Json.obj("name" -> Json.fromString("Graph WS B")))
      )
      _ <- IO {
        assertEquals(ws._1, Status.Created)
        wsA = UUID.fromString(jsonField(ws._2, "id"))
        wsB = UUID.fromString(jsonField(wsB2._2, "id"))
      }
    yield ()
  }

  test("setup: draft campaign") {
    for
      campaign <- request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns",
        Some("token-A"),
        Some(Json.obj("name" -> Json.fromString("Graph campaign")))
      )
      _ <- IO {
        assertEquals(campaign._1, Status.Created)
        campaignId = UUID.fromString(jsonField(campaign._2, "id"))
      }
    yield ()
  }

  test("graph save: branch flow with task; keys and subjects round-trip") {
    val steps = Json.arr(
      emailStepJson("s1", "Welcome"),
      waitStepJson("s2", 2, business = true),
      branchStepJson("s3", "region", "eq", "EU"),
      emailStepJson("s4", "EU track"),
      emailStepJson("s5", "Other track"),
      taskStepJson("s6", 3)
    )
    val edges = Json.arr(
      edge("s1", "s2"),
      edge("s2", "s3"),
      edge("s3", "s4", Some("yes")),
      edge("s3", "s5", Some("no")),
      edge("s4", "s6"),
      edge("s5", "s6")
    )
    for
      saved <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$campaignId",
        Some("token-A"),
        Some(Json.obj("steps" -> steps, "edges" -> edges))
      )
      _ <- IO {
        assertEquals(saved._1, Status.Ok)
        val gotSteps = saved._2.hcursor.downField("steps").values.toList.flatten
        assertEquals(gotSteps.size, 6)
        val keys = gotSteps.flatMap(_.hcursor.get[String]("key").toOption)
        assertEquals(keys.toSet, Set("s1", "s2", "s3", "s4", "s5", "s6"))
        val gotEdges = saved._2.hcursor.downField("edges").values.toList.flatten
        assertEquals(gotEdges.size, 6)
        val branchEdges =
          gotEdges.flatMap(_.hcursor.get[String]("branchKey").toOption).sorted
        assertEquals(branchEdges, List("no", "yes"))
        val wait = gotSteps
          .find(_.hcursor.get[String]("key").toOption.contains("s2"))
          .get
        assertEquals(
          wait.hcursor.get[Boolean]("businessDays").toOption.getOrElse(false),
          true
        )
      }
      // re-save with the SAME keys but renamed subject: step UUIDs must persist
      detailBefore <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/campaigns/$campaignId",
        Some("token-A")
      )
      idOfS1Before = detailBefore._2.hcursor
        .downField("steps")
        .values
        .toList
        .flatten
        .find(_.hcursor.get[String]("key").toOption.contains("s1"))
        .flatMap(_.hcursor.get[String]("id").toOption)
        .getOrElse("")
      renamed <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$wsA/campaigns/$campaignId",
        Some("token-A"),
        Some(
          Json.obj(
            "steps" -> Json.arr(
              emailStepJson("s1", "Welcome v2"),
              waitStepJson("s2", 2),
              branchStepJson("s3", "region", "eq", "EU"),
              emailStepJson("s4", "EU track"),
              emailStepJson("s5", "Other track"),
              taskStepJson("s6", 3)
            ),
            "edges" -> edges
          )
        )
      )
      detailAfter <- request(
        Method.GET,
        s"/api/v1/workspaces/$wsA/campaigns/$campaignId",
        Some("token-A")
      )
      idOfS1After = detailAfter._2.hcursor
        .downField("steps")
        .values
        .toList
        .flatten
        .find(_.hcursor.get[String]("key").toOption.contains("s1"))
        .flatMap(_.hcursor.get[String]("id").toOption)
        .getOrElse("")
      subjectAfter = detailAfter._2.hcursor
        .downField("steps")
        .values
        .toList
        .flatten
        .find(_.hcursor.get[String]("key").toOption.contains("s1"))
        .flatMap(
          _.hcursor.downField("variants").values.toList.flatten.headOption
        )
        .flatMap(_.hcursor.get[String]("subject").toOption)
        .getOrElse("")
      _ <- IO {
        assertEquals(renamed._1, Status.Ok)
        assertEquals(idOfS1After, idOfS1Before) // stable identity
        assertEquals(subjectAfter, "Welcome v2")
      }
    yield ()
  }

  test(
    "validate-graph: unreachable, incomplete branches, merges, bad conditions"
  ) {
    def validate(steps: Json, edges: Json): IO[(Status, Json)] =
      request(
        Method.POST,
        s"/api/v1/workspaces/$wsA/campaigns/$campaignId/validate-graph",
        Some("token-A"),
        Some(Json.obj("steps" -> steps, "edges" -> edges))
      )

    for
      // unreachable step
      unReachable <- validate(
        Json.arr(emailStepJson("a", "x"), emailStepJson("b", "y")),
        Json.arr()
      )
      _ <- IO {
        assertEquals(unReachable._1, Status.Ok)
        assertEquals(
          unReachable._2.hcursor.get[Boolean]("valid").toOption.getOrElse(true),
          false
        )
        val errors = unReachable._2.hcursor
          .downField("errors")
          .values
          .toList
          .flatten
          .map(_.asString.getOrElse(""))
        assert(errors.exists(_.contains("Unreachable")), errors.toString)
      }

      // branch without the no-edge + bad condition field
      badBranch <- validate(
        Json.arr(
          emailStepJson("a", "x"),
          branchStepJson("b", "password_hash", "eq", "x"),
          emailStepJson("c", "y"),
          emailStepJson("d", "z")
        ),
        Json.arr(edge("a", "b"), edge("b", "c", Some("yes")))
      )
      _ <- IO {
        val errors = badBranch._2.hcursor
          .downField("errors")
          .values
          .toList
          .flatten
          .map(_.asString.getOrElse(""))
        assert(errors.exists(_.contains("yes and no")), errors.toString)
        assert(errors.exists(_.contains("not allowed")), errors.toString)
      }

      // merge (two incoming) rejected
      merge <- validate(
        Json.arr(
          emailStepJson("a", "x"),
          emailStepJson("b", "y"),
          emailStepJson("c", "z")
        ),
        Json.arr(edge("a", "c"), edge("b", "c"))
      )
      _ <- IO {
        val errors = merge._2.hcursor
          .downField("errors")
          .values
          .toList
          .flatten
          .map(_.asString.getOrElse(""))
        assert(errors.exists(_.contains("starting step")), errors.toString)
      }

      // valid linear graph passes
      ok <- validate(
        Json.arr(emailStepJson("a", "x"), emailStepJson("b", "y")),
        Json.arr(edge("a", "b"))
      )
      _ <- IO {
        assertEquals(
          ok._2.hcursor.get[Boolean]("valid").toOption.getOrElse(false),
          true
        );
      }
    yield ()

    test("duplicate: graph edges copied to the draft copy") {
      for
        copy <- request(
          Method.POST,
          s"/api/v1/workspaces/$wsA/campaigns/$campaignId/duplicate",
          Some("token-A")
        )
        copyId = jsonField(copy._2, "id")
        detail <- request(
          Method.GET,
          s"/api/v1/workspaces/$wsA/campaigns/$copyId",
          Some("token-A")
        )
        _ <- IO {
          assertEquals(copy._1, Status.Created)
          assertEquals(
            detail._2.hcursor.downField("edges").values.toList.flatten.size,
            6
          )
          val keys = detail._2.hcursor
            .downField("steps")
            .values
            .toList
            .flatten
            .flatMap(_.hcursor.get[String]("key").toOption)
          assertEquals(keys.toSet, Set("s1", "s2", "s3", "s4", "s5", "s6"))
        }
        // tenant isolation on validate endpoint
        outsider <- request(
          Method.POST,
          s"/api/v1/workspaces/$wsA/campaigns/$campaignId/validate-graph",
          Some("token-C"),
          Some(Json.obj("steps" -> Json.arr(), "edges" -> Json.arr()))
        )
        _ <- IO { assertEquals(outsider._1, Status.NotFound); }
      yield ()
    }
  }
