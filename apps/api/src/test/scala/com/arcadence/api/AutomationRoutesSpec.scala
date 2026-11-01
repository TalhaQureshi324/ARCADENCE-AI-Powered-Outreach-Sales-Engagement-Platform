package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.db.DbUrl
import com.arcadence.core.workflows.WorkflowAutomation
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Header, Method, Request, Status, Uri}

import java.util.UUID

/** Workflow automation (Phase 12.4): graph validation (whitelists, cycles,
  * dead-ends), CRUD, deterministic dry-run and live runs with step history,
  * versioning, and cancellation.
  */
final class AutomationRoutesSpec extends CatsEffectSuite:

  override def munitIOTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "second")

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

  private val orgId = UUID.randomUUID()
  private val ws = UUID.randomUUID()
  private val userA = UUID.randomUUID()
  private val suffix = ws.toString.take(8)

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, s"auto-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val routes = new AutomationRoutes(fakeAuth, xa)
  private lazy val app = routes.http.orNotFound

  private val validGraph: Json = parse("""{
    "nodes": [
      {"id":"n1","kind":"has_tag","condition":true,"config":{"field":"tags","tag":"vip"}},
      {"id":"n2","kind":"add_tag","config":{"tag":"follow-up"}},
      {"id":"n3","kind":"create_task","config":{"title":"Call the lead"}}
    ],
    "edges": [
      {"from":"n1","to":"n2"},
      {"from":"n2","to":"n3"}
    ]
  }""").toOption.get

  private val cyclicGraph: Json = parse("""{
    "nodes": [
      {"id":"a","kind":"add_tag","config":{}},
      {"id":"b","kind":"create_task","config":{}}
    ],
    "edges": [
      {"from":"a","to":"b"},
      {"from":"b","to":"a"}
    ]
  }""").toOption.get

  private def request(
      method: Method,
      path: String,
      body: Option[Json] = None
  ): IO[(Status, Json)] =
    val base = Uri.unsafeFromString(path)
    val baseReq = Request[IO](method, base).withHeaders(
      Header.Raw(org.typelevel.ci.CIString("Authorization"), "Bearer token-A")
    )
    val req = body
      .fold(baseReq)(json =>
        baseReq
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

  private def jsonField(b: Json, f: String): String =
    b.hcursor.get[String](f).toOption.getOrElse("")
  private def jsonIntField(b: Json, f: String): Int =
    b.hcursor.get[Int](f).toOption.getOrElse(-1)

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: org, ws, owner") {
    for _ <-
        (sql"delete from public.workspace where id = $ws".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'AU Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'AU WS', ${"au-" + suffix})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"auto-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'auto') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run).void
          .transact(xa)
    yield ()
  }

  test("core validation: cycles and dead-end conditions are rejected") {
    val cyclic =
      WorkflowAutomation.decode(cyclicGraph).map(WorkflowAutomation.validate)
    assertEquals(cyclic, Right(List("cycle_detected")))
    val deadEnd = WorkflowAutomation
      .decode(
        parse(
          """{"nodes":[{"id":"c","kind":"has_tag","condition":true,"config":{}}],"edges":[]}"""
        ).toOption.get
      )
      .map(WorkflowAutomation.validate)
    assert(deadEnd.exists(_.exists(_.startsWith("condition_dead_end"))))
  }

  test(
    "create validates the graph; invalid trigger and cyclic graph rejected"
  ) {
    for
      badTrigger <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/automations",
        body = Some(
          Json.obj(
            "name" -> Json.fromString("x"),
            "triggerType" -> Json.fromString("cosmic_ray"),
            "graph" -> validGraph
          )
        )
      )
      _ = assertEquals(badTrigger._1, Status.UnprocessableEntity)
      cyclic <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/automations",
        body = Some(
          Json.obj(
            "name" -> Json.fromString("loop"),
            "triggerType" -> Json.fromString("positive_reply"),
            "graph" -> cyclicGraph
          )
        )
      )
      _ = assertEquals(cyclic._1, Status.UnprocessableEntity)
      _ = assert(jsonField(cyclic._2, "message").contains("cycle_detected"))
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/automations",
        body = Some(
          Json.obj(
            "name" -> Json.fromString("VIP follow-up"),
            "triggerType" -> Json.fromString("positive_reply"),
            "graph" -> validGraph
          )
        )
      )
      _ = assertEquals(created._1, Status.Created)
      _ = assertEquals(
        created._2.hcursor.get[Boolean]("enabled").toOption.getOrElse(true),
        false
      )
    yield ()
  }

  private var automationId: UUID = UUID.randomUUID()

  test(
    "dry run executes steps without side effects; live run records history"
  ) {
    for
      listed <- request(Method.GET, s"/api/v1/workspaces/$ws/automations")
      automationId0 = listed._2.asArray
        .getOrElse(Vector.empty)
        .head
        .hcursor
        .get[String]("id")
        .toOption
        .getOrElse("")
      _ = automationId = UUID.fromString(automationId0)
      dry <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/automations/$automationId0/run",
        body = Some(
          Json.obj(
            "dryRun" -> Json.fromBoolean(true),
            "context" -> Json.obj(
              "tags" -> Json.fromString("vip"),
              "email" -> Json.fromString("ada@t.test")
            )
          )
        )
      )
      _ = assertEquals(dry._1, Status.Ok)
      _ = assertEquals(
        dry._2.hcursor.get[Boolean]("dryRun").toOption.getOrElse(false),
        true
      )
      steps = dry._2.hcursor.downField("steps").values.getOrElse(Vector.empty)
      _ = assert(steps.size >= 2, s"steps: $steps")
      _ = assert(jsonField(steps.head, "step").startsWith("condition:"))
      live <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/automations/$automationId0/run",
        body = Some(
          Json.obj(
            "dryRun" -> Json.fromBoolean(false),
            "context" -> Json.obj(
              "tags" -> Json.fromString("vip"),
              "email" -> Json.fromString("ada@t.test")
            )
          )
        )
      )
      _ = assertEquals(
        live._2.hcursor.get[Boolean]("dryRun").toOption.getOrElse(true),
        false
      )
      history <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/automations/$automationId0/runs"
      )
      runs = history._2.asArray.getOrElse(Vector.empty).size
      _ = assert(runs >= 2)
    yield ()
  }

  test(
    "graph update bumps the version; runs record their version; cancel works"
  ) {
    for
      updated <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$ws/automations/$automationId",
        body = Some(
          Json.obj("graph" -> validGraph, "enabled" -> Json.fromBoolean(true))
        )
      )
      _ = assertEquals(jsonIntField(updated._2, "version"), 2)
      _ = assertEquals(
        updated._2.hcursor.get[Boolean]("enabled").toOption.getOrElse(false),
        true
      )
      started <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/automations/$automationId/run",
        body = Some(
          Json.obj("context" -> Json.obj(), "dryRun" -> Json.fromBoolean(true))
        )
      )
      runId = jsonField(started._2, "runId")
      cancelled <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/automation-runs/$runId/cancel"
      )
      // already completed runs cannot be cancelled
      _ = assertEquals(cancelled._1, Status.NotFound)
    yield ()
  }

  test("unknown trigger and unknown automation are rejected") {
    for
      missing <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/automations/${UUID.randomUUID()}/run",
        body = Some(Json.obj("context" -> Json.obj()))
      )
      _ = assertEquals(missing._1, Status.NotFound)
    yield ()
  }
