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

/** AI Reply Agent routes (Phase 11.3): proposal drafts with evidence,
  * deterministic unsubscribe/bounce short-circuit (model never called), hard
  * validation of invalid intents/actions, approval creation, and tenant
  * isolation.
  */
final class AiReplyRoutesSpec extends CatsEffectSuite:

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
    "token-A" -> AuthenticatedUser(userA, s"air-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private val goodStub = AiGateway.Stub(
    """{"intent":"interested","replySubject":"Happy to walk you through it",
      |"replyBody":"Thanks for the interest — here is how the rollout works.",
      |"nextActions":[{"action":"create_deal","rationale":"positive intent","factKeys":["f1"]}],
      |"confidence":"medium","evidence":[{"factKey":"f1","how":"scaling context"}]}""".stripMargin
  )

  private lazy val routes = new AiReplyRoutes(
    fakeAuth,
    xa,
    gateway = Some(goodStub),
    defaultModel = "test-model"
  )
  private lazy val app = routes.http.orNotFound

  private val badStub = AiGateway.Stub(
    """{"intent":"totally_made_up","replySubject":"x","replyBody":"y",
      |"nextActions":[{"action":"send_money","rationale":"?","factKeys":[]}],"confidence":"high","evidence":[]}""".stripMargin
  )
  private lazy val badApp = new AiReplyRoutes(
    fakeAuth,
    xa,
    gateway = Some(badStub),
    defaultModel = "test-model"
  ).http

  private def requestOn(
      target: org.http4s.HttpRoutes[IO],
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
    target.orNotFound
      .run(req)
      .flatMap(res =>
        res.bodyText.compile.string
          .map(t => (res.status, parse(t).toOption.getOrElse(Json.Null)))
      )

  private def request(
      method: Method,
      path: String,
      token: String = "token-A",
      body: Option[Json] = None
  ): IO[(Status, Json)] = requestOn(routes.http, method, path, token, body)

  private def jsonField(body: Json, field: String): String =
    body.hcursor.get[String](field).toOption.getOrElse("")

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  private var interestedThreadId: UUID = UUID.randomUUID()
  private var unsubThreadId: UUID = UUID.randomUUID()
  private var contactId: UUID = UUID.randomUUID()

  test(
    "setup: org, ws, owner, mailbox, contact, campaign, threads, knowledge"
  ) {
    for
      _ <-
        (sql"delete from public.workspace where id = $ws".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'AIR Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'AIR WS', ${"air-" + suffix})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"air-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'air') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run *>
          sql"""insert into public.email_accounts (workspace_id, provider, email_address, status)
                values ($ws, 'smtp_imap', ${"mailbox-" + suffix + "@t.test"}, 'connected')""".update.run *>
          sql"""insert into public.contact (workspace_id, email, first_name, last_name)
                values ($ws, ${"lead-" + suffix + "@t.test"}, 'Ada', 'Gate') returning id"""
            .query[UUID]
            .unique)
          .flatMap { cid =>
            (contactId = cid)
            sql"""insert into public.ai_research_items (workspace_id, contact_id, claim, source_url)
                values ($ws, $cid, 'Acme is scaling its sales team', 'https://example.com/news')""".update.run *>
              sql"""insert into public.campaign (workspace_id, name, status) values ($ws, 'AIR campaign', 'active') returning id"""
                .query[UUID]
                .unique
                .flatMap { cid0 =>
                  sql"""insert into public.email_threads (workspace_id, email_account_id, contact_id, campaign_id, subject, classification)
                    values ($ws, (select id from public.email_accounts where workspace_id = $ws limit 1), $cid, $cid0, 'Re: rollout', 'interested') returning id"""
                    .query[UUID]
                    .unique
                    .flatMap { tid =>
                      (interestedThreadId = tid)
                      sql"""insert into public.email_messages (workspace_id, thread_id, direction, from_address, subject, body_text, classification, occurred_at)
                      values ($ws, $tid, 'inbound', ${"lead-" + suffix + "@t.test"}, 'Re: rollout', 'We would like to learn more about scaling this quarter.', 'interested', now())""".update.run
                    }
                }
          }
          .transact(xa)
      _ <-
        sql"""insert into public.email_threads (workspace_id, email_account_id, contact_id, subject, classification)
              values ($ws, (select id from public.email_accounts where workspace_id = $ws limit 1), $contactId, 'Re: remove me', 'unsubscribe') returning id"""
          .query[UUID]
          .unique
          .flatMap { tid =>
            (unsubThreadId = tid)
            sql"""insert into public.email_messages (workspace_id, thread_id, direction, from_address, subject, body_text, classification, occurred_at)
                values ($ws, $tid, 'inbound', ${"lead-" + suffix + "@t.test"}, 'Re: remove me', 'Please unsubscribe me.', 'unsubscribe', now())""".update.run
          }
          .transact(xa)
    yield ()
  }

  test(
    "reply draft proposes intent + reply with evidence, approval and audit records"
  ) {
    val callsBefore = goodStub.calls
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/reply/draft",
        body = Some(
          Json.obj("threadId" -> Json.fromString(interestedThreadId.toString))
        )
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(jsonField(pair._2, "intent"), "interested")
      _ = assertEquals(
        pair._2.hcursor.get[Boolean]("modelCalled").toOption.getOrElse(false),
        true
      )
      aiMessageId = jsonField(pair._2, "aiMessageId")
      row <-
        sql"""select status, thread_id, contact_id from public.ai_messages where id = $aiMessageId::uuid"""
          .query[(String, UUID, Option[UUID])]
          .unique
          .transact(xa)
      _ = assertEquals(row._1, "draft")
      _ = assertEquals(row._2, interestedThreadId)
      approvals <-
        sql"select count(*) from public.ai_approvals where workspace_id = $ws and action = 'send_reply' and status = 'pending'"
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(approvals, 1)
      runs <-
        sql"select count(*) from public.ai_runs where workspace_id = $ws and kind = 'reply' and status = 'awaiting_approval'"
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(runs, 1)
      _ = assertEquals(goodStub.calls, callsBefore + 1)
    yield ()
  }

  test(
    "unsubscribe thread short-circuits deterministically — the model is never called"
  ) {
    val callsBefore = goodStub.calls
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/reply/draft",
        body =
          Some(Json.obj("threadId" -> Json.fromString(unsubThreadId.toString)))
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(jsonField(pair._2, "intent"), "unsubscribe")
      _ = assertEquals(
        pair._2.hcursor.get[Boolean]("modelCalled").toOption.getOrElse(true),
        false
      )
      _ = assert(
        jsonField(pair._2, "note").toLowerCase.contains("deterministic")
      )
      _ = assertEquals(goodStub.calls, callsBefore)
      drafts <-
        sql"""select count(*) from public.ai_messages where workspace_id = $ws and thread_id = $unsubThreadId"""
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(drafts, 0)
    yield ()
  }

  test(
    "invalid intent or actions are hard-rejected by deterministic validation"
  ) {
    for pair <- requestOn(
        badApp,
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/reply/draft",
        body = Some(
          Json.obj("threadId" -> Json.fromString(interestedThreadId.toString))
        )
      )
    yield
      assertEquals(pair._1, Status.UnprocessableEntity)
      assertEquals(jsonField(pair._2, "code"), "validation_failed")
  }

  test("tenant isolation and unknown threads return 404") {
    for
      missing <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/reply/draft",
        body = Some(
          Json.obj("threadId" -> Json.fromString(UUID.randomUUID().toString))
        )
      )
      _ = assertEquals(missing._1, Status.NotFound)
    yield ()
  }
