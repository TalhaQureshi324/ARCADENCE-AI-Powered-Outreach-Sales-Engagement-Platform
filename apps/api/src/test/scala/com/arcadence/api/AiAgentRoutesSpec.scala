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

/** Sales agent routes (Phase 11.4): the constrained 7-step run records steps +
  * tool invocations, stores sandbox contacts, produces flagged drafts and ends
  * at a pending human approval. Monitor/recommend and meeting qualification run
  * over real workspace data.
  */
final class AiAgentRoutesSpec extends CatsEffectSuite:

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

  private val tokens = Map[String, AuthenticatedUser](
    "token-A" -> AuthenticatedUser(userA, s"aag-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  /** Content-routed stub: picks the response shape from the prompt. */
  private class Scripted extends AiGateway.Gateway:
    val name = "scripted"
    private val briefJson =
      """{"audienceSummary":"SaaS sales leaders scaling outbound","goal":"book demos"}"""
    private val audJson =
      """{"industry":"software","region":"EU","jobTitle":"Head of Growth","companySize":"11-50"}"""
    private val writerJson =
      """{"drafts":[{"subject":"Scaling outreach?","body":"Saw Acme teams scale outbound - quick idea.","claims":[]}]}"""
    private val recJson =
      """{"recommendations":[{"action":"tighten segment","why":"replies concentrated"}]}"""
    private val meetJson =
      """{"meetings":[{"contact":"Ada","email":"ada@t.test","priority":"high","angle":"rollout plan"}]}"""
    def complete(
        model: String,
        messages: List[AiGateway.ChatMessage],
        max: Int,
        json: Boolean
    ): IO[Either[AiGateway.GatewayError, AiGateway.Completion]] = IO {
      val last = messages.lastOption.map(_.content).getOrElse("")
      val r =
        if last.contains("Recommend next changes") then recJson
        else if last.contains("Positive repliers") then meetJson
        else if last.contains("Length:") then writerJson
        else if last.contains("Audience:") then audJson
        else briefJson
      Right(AiGateway.Completion(r, AiGateway.Usage(10, 8), model, "stop"))
    }

  private lazy val routes =
    new AiAgentRoutes(
      fakeAuth,
      xa,
      gateway = Some(new Scripted),
      defaultModel = "test-model"
    )
  private lazy val app = routes.http.orNotFound

  private def request(
      method: Method,
      path: String,
      body: Option[Json] = None
  ): IO[(Status, Json)] =
    val base = Uri.unsafeFromString(path)
    val baseReq = Request[IO](method, base).withHeaders(
      Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer token-A")
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
  private def jsonInt(b: Json, f: String): Int =
    b.hcursor.get[Int](f).toOption.getOrElse(-1)

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  private var campaignId: UUID = UUID.randomUUID()

  test("setup: org, ws, owner, campaign with a replied enrollment") {
    for _ <-
        (sql"delete from public.workspace where id = $ws".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'AAG Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'AAG WS', ${"aag-" + suffix})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"aag-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'aag') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run *>
          sql"""insert into public.campaign (workspace_id, name, status) values ($ws, 'AAG campaign', 'active') returning id"""
            .query[UUID]
            .unique
            .flatMap { cid =>
              campaignId = cid
              sql"""insert into public.contact (workspace_id, email, first_name)
                  values ($ws, ${"aag-lead-" + suffix + "@t.test"}, 'Ada') returning id"""
                .query[UUID]
                .unique
                .flatMap { contact =>
                  sql"""insert into public.campaign_enrollment (workspace_id, campaign_id, contact_id, status)
                    values ($ws, $cid, $contact, 'replied')""".update.run
                }
            }).void.transact(xa)
    yield ()
  }

  test(
    "agent run executes 7 constrained steps, stores sandbox contacts, drafts and a pending approval"
  ) {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/agent/run",
        body = Some(
          Json.obj(
            "objective" -> Json.fromString("book demos with EU SaaS teams"),
            "offer" -> Json.fromString("cold outreach platform"),
            "persona" -> Json.fromString("Head of Growth"),
            "variations" -> Json.fromInt(1)
          )
        )
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(jsonInt(pair._2, "contactsFound"), 1)
      _ = assertEquals(
        pair._2.hcursor.get[Boolean]("readiness").toOption.getOrElse(false),
        true
      )
      runId = jsonField(pair._2, "runId")
      steps <-
        sql"""select count(*) from public.ai_run_steps where run_id = $runId::uuid and status = 'ok'"""
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(steps, 7)
      tools <-
        sql"""select count(*) from public.ai_tool_invocations where run_id = $runId::uuid and status = 'completed'"""
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(tools, 2)
      approvals <-
        sql"""select count(*) from public.ai_approvals where workspace_id = $ws and action = 'launch_campaign' and status = 'pending'"""
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(approvals, 1)
      contacts <-
        sql"""select count(*) from public.discovered_contacts dc
              join public.search_requests sr on sr.id = dc.search_request_id
              where sr.workspace_id = $ws and dc.permitted_use = 'sandbox_only'"""
          .query[Int]
          .unique
          .transact(xa)
      _ = assert(contacts >= 1)
      runs <-
        sql"""select status from public.ai_runs where id = $runId::uuid"""
          .query[String]
          .unique
          .transact(xa)
      _ = assertEquals(runs, "awaiting_approval")
    yield ()
  }

  test("recommendations run over real campaign stats") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/agent/recommendations",
        body =
          Some(Json.obj("campaignId" -> Json.fromString(campaignId.toString)))
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(jsonField(pair._2, "campaign"), "AAG campaign")
      stats = pair._2.hcursor
        .downField("stats")
        .get[Int]("replies")
        .toOption
        .getOrElse(-1)
      _ = assertEquals(stats, 1)
    yield ()
  }

  test("meeting qualification lists positive repliers") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/agent/meetings",
        body =
          Some(Json.obj("campaignId" -> Json.fromString(campaignId.toString)))
      )
      _ = assertEquals(pair._1, Status.Ok)
      meetings = pair._2.asArray.getOrElse(Vector.empty)
      _ = assertEquals(meetings.size, 1)
    yield ()
  }

  test("unknown campaign is 404") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/agent/recommendations",
        body = Some(
          Json.obj("campaignId" -> Json.fromString(UUID.randomUUID().toString))
        )
      )
      _ = assertEquals(pair._1, Status.NotFound)
    yield ()
  }
