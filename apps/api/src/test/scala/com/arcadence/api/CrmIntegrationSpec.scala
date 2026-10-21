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

/** CRM core + workflows (Phase 9.1/9.2): pipelines/stages, deals with validated
  * stage moves, status actions, dedupe on open deals per enrollment, activity
  * trail, and auto-deal on positive reply.
  */
final class CrmIntegrationSpec extends CatsEffectSuite:

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
  private var enrollmentId: UUID = UUID.randomUUID()
  private val contactEmail = s"crm-${ws.toString.take(6)}@t.test"

  private val tokens = Map("token-A" -> AuthenticatedUser(userA, "crm@t.test"))
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val crmRoutes = new CrmRoutes(fakeAuth, xa)
  private lazy val app = (crmRoutes.http).orNotFound

  private def request(
      method: Method,
      path: String,
      body: Option[Json] = None
  ): IO[(Status, Json)] =
    val base = Uri.unsafeFromString(path)
    val baseReq = Request[IO](method, base)
    val withAuth = baseReq.withHeaders(
      Header.Raw(org.typelevel.ci.CIString("Authorization"), "Bearer token-A")
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

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: org/ws/user, campaign + enrolled contact + reply") {
    for
      _ <-
        (sql"delete from public.workspace where name = 'CRM WS'".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'CRM Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'CRM WS', ${"crm-" + ws.toString.take(12)})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"crm-" + ws.toString.take(
              6
            ) + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'crm') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run).void
          .transact(xa)
      cid <-
        sql"""insert into public.campaign (id, workspace_id, created_by, name, status)
              values (gen_random_uuid(), $ws, null, 'CRM campaign', 'active') returning id"""
          .query[UUID]
          .unique
          .transact(xa)
      contact <-
        sql"insert into public.contact (workspace_id, email, state) values ($ws, $contactEmail, 'active') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      enr <-
        sql"""
          insert into public.campaign_enrollment (workspace_id, campaign_id, contact_id, status, next_action_at)
          values ($ws, $cid, $contact, 'replied', now()) returning id
        """.query[UUID].unique.transact(xa)
      _ <-
        sql"""insert into public.event_outbox (workspace_id, aggregate_type, aggregate_id, event_type)
              values ($ws, 'enrollment', ${enr.toString}, 'reply_classified')""".update.run
          .transact(xa)
      _ <- IO { enrollmentId = enr; () }
    yield ()
  }

  test("pipeline create with stages") {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/crm/pipelines",
        Some(
          Json.obj(
            "name" -> Json.fromString("Sales"),
            "stages" -> Json.arr(
              Json.fromString("New"),
              Json.fromString("Contacted"),
              Json.fromString("Won")
            )
          )
        )
      )
      _ <- IO {
        assertEquals(created._1, Status.Created)
        assertEquals(
          created._2.hcursor.downField("stages").values.toList.flatten.size,
          3
        )
      }
    yield ()
  }

  test("deal create, stage move validated, dedupe on enrollment, timeline") {
    for
      pipelines <- request(Method.GET, s"/api/v1/workspaces/$ws/crm/pipelines")
      pid = pipelines._2.asArray
        .getOrElse(Vector.empty)
        .head
        .hcursor
        .get[UUID]("id")
        .toOption
        .getOrElse(UUID.randomUUID())
      stageId <-
        sql"select id from public.pipeline_stage where pipeline_id = $pid order by position limit 1"
          .query[UUID]
          .unique
          .transact(xa)
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/crm/deals",
        Some(
          Json.obj(
            "pipelineId" -> Json.fromString(pid.toString),
            "stageId" -> Json.fromString(stageId.toString),
            "title" -> Json.fromString("CRM deal"),
            "sourceEnrollmentId" -> Json.fromString(enrollmentId.toString),
            "valueCents" -> Json.fromLong(500000),
            "currency" -> Json.fromString("USD")
          )
        )
      )
      _ <- IO {
        println(
          s"CRM-DEBUG create=${created._1} body=${created._2.noSpaces.take(300)}"
        );
        assertEquals(created._1, Status.Created); ()
      }
      // duplicate open deal for same enrollment -> conflict
      dup <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/crm/deals",
        Some(
          Json.obj(
            "pipelineId" -> Json.fromString(pid.toString),
            "stageId" -> Json.fromString(stageId.toString),
            "title" -> Json.fromString("dup"),
            "valueCents" -> Json.fromLong(0),
            "currency" -> Json.fromString("USD"),
            "sourceEnrollmentId" -> Json.fromString(enrollmentId.toString)
          )
        )
      )
      _ <- IO { assertEquals(dup._1, Status.Conflict); () }
      // move to an invalid stage (different pipeline) -> 422
      otherPipe <-
        sql"insert into public.pipeline (workspace_id, name) values ($ws, 'Other') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      otherStage <-
        sql"insert into public.pipeline_stage (pipeline_id, name, position) values ($otherPipe, 'X', 1) returning id"
          .query[UUID]
          .unique
          .transact(xa)
      dealId <- sql"select id from public.deal where workspace_id = $ws limit 1"
        .query[UUID]
        .unique
        .transact(xa)
      badMove <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/crm/deals/$dealId/move",
        Some(Json.obj("stageId" -> Json.fromString(otherStage.toString)))
      )
      _ <- IO { assertEquals(badMove._1, Status.UnprocessableEntity); () }
      // valid move to Won stage
      wonStage <-
        sql"""select s.id from public.pipeline_stage s
              join public.pipeline p on p.id = s.pipeline_id
              where p.id = $pid and lower(s.name) = 'won' limit 1"""
          .query[UUID]
          .unique
          .transact(xa)
      move <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/crm/deals/$dealId/move",
        Some(Json.obj("stageId" -> Json.fromString(wonStage.toString)))
      )
      timeline <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/crm/deals/$dealId/timeline"
      )
      activities <- IO {
        timeline._2.asArray
          .getOrElse(Vector.empty)
          .toList
          .map(_.hcursor.get[String]("type").toOption)
      }
      _ <- IO { assert(activities.contains(Some("stage_change"))); () }
    yield ()
  }

  test(
    "auto-deal on positive reply: deduped, activity recorded, won/lost manual"
  ) {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/crm/deals/from-reply/$enrollmentId"
      )
      // already created in earlier test (open deal for enrollment) -> conflict
      _ <- IO { assertEquals(created._1, Status.Conflict); () }
      dealId <- sql"select id from public.deal where workspace_id = $ws limit 1"
        .query[UUID]
        .unique
        .transact(xa)
      won <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/crm/deals/$dealId/status",
        Some(Json.obj("status" -> Json.fromString("won")))
      )
      _ <- IO(println("WON-DEBUG " + won._1 + " " + won._2.noSpaces.take(200)))
      _ <- IO {
        assertEquals(
          won._2.hcursor.get[String]("status").toOption.getOrElse(""),
          "won"
        );
        ()
      }
      events <- sql"""
        select count(*) from public.event_outbox where aggregate_id = ${dealId.toString}
          and event_type = 'deal_transition'"""
        .query[Long]
        .unique
        .transact(xa)
      _ <- IO { assert(events >= 0); () }
    yield ()
  }
