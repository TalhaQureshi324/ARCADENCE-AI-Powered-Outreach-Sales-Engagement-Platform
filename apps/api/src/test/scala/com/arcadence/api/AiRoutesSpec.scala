package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.ai.AiGateway
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

/** AI infrastructure routes (Phase 11.1): configuration honesty, budget
  * enforcement, run/usage logging with redaction, agents, templates, approvals
  * lifecycle, and tenant isolation.
  */
final class AiRoutesSpec extends CatsEffectSuite:

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
  private val ws2 = UUID.randomUUID()
  private val userA = UUID.randomUUID()
  private val userB = UUID.randomUUID()
  private val suffix = ws.toString.take(8)

  private val tokens = Map[String, AuthenticatedUser](
    "token-A" -> AuthenticatedUser(userA, s"aia-$suffix@t.test"),
    "token-B" -> AuthenticatedUser(userB, s"aib-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private val stub = AiGateway.Stub(
    "Draft: reach out to ada@example.com about the rollout. sk-verysecretkey12345"
  )

  private lazy val routes = new AiRoutes(
    fakeAuth,
    xa,
    gateway = Some(stub),
    defaultModel = "test-model"
  )
  private lazy val app = routes.http.orNotFound

  private def request(
      method: Method,
      path: String,
      token: String = "token-A",
      body: Option[Json] = None
  ): IO[(Status, Json)] =
    val base = Uri.unsafeFromString(path)
    val baseReq = Request[IO](method, base).withHeaders(
      Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $token")
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

  private def jsonField(body: Json, field: String): String =
    body.hcursor.get[String](field).toOption.getOrElse("")

  private def jsonLong(body: Json, field: String): Long =
    body.hcursor.get[Long](field).toOption.getOrElse(-1L)

  private def jsonBool(body: Json, field: String): Boolean =
    body.hcursor.get[Boolean](field).toOption.getOrElse(!field.isEmpty)

  private def jsonInt(body: Json, field: String): Int =
    body.hcursor.get[Int](field).toOption.getOrElse(-1)

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: org, workspaces, owners; ws2 gets a zero AI budget") {
    for _ <-
        (sql"delete from public.workspace where id in ($ws, $ws2)".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'AI Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'AI WS', ${"ai-" + suffix})".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug, ai_monthly_token_limit, ai_monthly_spend_micros) values ($ws2, $orgId, 'AI WS2', ${"ai2-" + suffix}, 0, 0)".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"aia-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userB, 'authenticated', 'authenticated',
                    ${"aib-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'aia') on conflict (id) do nothing".update.run *>
          sql"insert into public.profile (id, display_name) values ($userB, 'aib') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws2, $userB, 'owner')".update.run).void
          .transact(xa)
    yield ()
  }

  test("config reports a configured gateway with usage and budget") {
    for
      pair <- request(Method.GET, s"/api/v1/workspaces/$ws/ai/config")
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(jsonBool(pair._2, "gatewayConfigured"), true)
      _ = assertEquals(jsonField(pair._2, "defaultModel"), "test-model")
      usage = pair._2.hcursor
        .downField("monthlyUsage")
        .get[Long]("tokens")
        .toOption
        .getOrElse(-1L)
      _ = assertEquals(usage, 0L)
    yield ()
  }

  test("complete records a run with redacted previews, usage row and step") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/complete",
        body = Some(
          Json.obj(
            "system" -> Json.fromString("You are a B2B writer."),
            "user" -> Json.fromString("Draft a note for ada@example.com")
          )
        )
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assert(jsonField(pair._2, "text").startsWith("Draft:"))
      _ = assertEquals(jsonLong(pair._2, "inputTokens"), 11L)
      _ = assertEquals(jsonLong(pair._2, "outputTokens"), 7L)
      runId = jsonField(pair._2, "runId")
      detail <- request(Method.GET, s"/api/v1/workspaces/$ws/ai/runs/$runId")
      _ = assertEquals(detail._1, Status.Ok)
      runOut = detail._2.hcursor
        .downField("run")
        .get[String]("outputPreview")
        .toOption
        .getOrElse("")
      _ = assert(
        !runOut.contains("ada@example.com"),
        s"preview not redacted: $runOut"
      )
      _ = assert(
        !runOut.contains("sk-verysecretkey12345"),
        "api key not redacted"
      )
      _ = assert(runOut.contains("[email]"))
      steps = detail._2.hcursor
        .downField("steps")
        .values
        .getOrElse(Vector.empty)
      _ = assertEquals(steps.size, 1)
      usage <- request(Method.GET, s"/api/v1/workspaces/$ws/ai/usage")
      tokens = usage._2.hcursor
        .downField("month")
        .get[Long]("totalTokens")
        .toOption
        .getOrElse(-1L)
      _ = assertEquals(tokens, 18L)
    yield ()
  }

  test("zero-budget workspace blocks complete before the provider is called") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws2/ai/complete",
        token = "token-B",
        body = Some(Json.obj("user" -> Json.fromString("hello")))
      )
      _ = assertEquals(pair._1, Status.PaymentRequired)
      _ = assertEquals(jsonField(pair._2, "code"), "insufficient_budget")
      _ = assertEquals(stub.calls, 1) // only the ws test above reached the stub
      runs <-
        sql"select count(*) from public.ai_runs where workspace_id = $ws2"
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(runs, 0)
    yield ()
  }

  test("agents: create, list, patch; unknown kind rejected") {
    for
      bad <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/agents",
        body = Some(
          Json.obj(
            "name" -> Json.fromString("X"),
            "kind" -> Json.fromString("evil")
          )
        )
      )
      _ = assertEquals(bad._1, Status.UnprocessableEntity)
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/agents",
        body = Some(
          Json.obj(
            "name" -> Json.fromString("Outreach writer"),
            "kind" -> Json.fromString("writer"),
            "requiresApproval" -> Json.fromBoolean(true),
            "maxCredits" -> Json.fromInt(25)
          )
        )
      )
      _ = assertEquals(created._1, Status.Created)
      agentId = jsonField(created._2, "id")
      patched <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$ws/ai/agents/$agentId",
        body = Some(Json.obj("enabled" -> Json.fromBoolean(false)))
      )
      _ = assertEquals(jsonBool(patched._2, "enabled"), false)
      listed <- request(Method.GET, s"/api/v1/workspaces/$ws/ai/agents")
      names = listed._2.asArray
        .getOrElse(Vector.empty)
        .flatMap(_.hcursor.get[String]("name").toOption)
      _ = assert(names.contains("Outreach writer"))
    yield ()
  }

  test("templates: versioned creation under the same name") {
    for
      v1 <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/templates",
        body = Some(
          Json.obj(
            "name" -> Json.fromString("intro"),
            "body" -> Json.fromString("Hello {{first_name}}")
          )
        )
      )
      _ = assertEquals(v1._1, Status.Created)
      _ = assertEquals(jsonInt(v1._2, "version"), 1)
      v2 <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/templates",
        body = Some(
          Json.obj(
            "name" -> Json.fromString("intro"),
            "body" -> Json.fromString("Hi {{first_name}}")
          )
        )
      )
      _ = assertEquals(jsonInt(v2._2, "version"), 2)
    yield ()
  }

  test("approvals: create pending, decide, unknown id 404") {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/approvals",
        body = Some(
          Json.obj(
            "action" -> Json.fromString("send_reply"),
            "payload" -> Json.obj("draftId" -> Json.fromString("d1"))
          )
        )
      )
      _ = assertEquals(created._1, Status.Created)
      _ = assertEquals(jsonField(created._2, "status"), "pending")
      approvalId = jsonField(created._2, "id")
      decided <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/approvals/$approvalId/decide",
        body = Some(Json.obj("decision" -> Json.fromString("approved")))
      )
      _ = assertEquals(jsonField(decided._2, "status"), "approved")
      again <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/approvals/$approvalId/decide",
        body = Some(Json.obj("decision" -> Json.fromString("rejected")))
      )
      _ = assertEquals(again._1, Status.NotFound)
      pending <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/ai/approvals?status=pending"
      )
      count = pending._2.asArray.getOrElse(Vector.empty).size
      _ = assertEquals(count, 0)
    yield ()
  }

  test("tenant isolation: another workspace's owner cannot read AI resources") {
    for
      cfg <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/ai/config",
        token = "token-B"
      )
      _ = assertEquals(cfg._1, Status.NotFound)
      agents <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/ai/agents",
        token = "token-B"
      )
      _ = assertEquals(agents._1, Status.NotFound)
    yield ()
  }
