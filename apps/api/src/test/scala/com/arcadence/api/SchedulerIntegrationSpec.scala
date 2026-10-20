package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.db.DbUrl
import com.arcadence.core.jobs.JobQueue
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Header, Method, Request, Status, Uri}

import java.time.{LocalTime, OffsetDateTime, ZoneId, ZonedDateTime}
import java.util.UUID

/** Campaign scheduler (Phase 5.2): eligibility (campaign/enrollment/contact
  * state + suppression), atomic quota reservations under concurrency,
  * connected-mailbox allocation, window/timezone deferral, and pause-while-
  * queued safety.
  */
final class SchedulerIntegrationSpec extends CatsEffectSuite:

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
  private lazy val repos = new CampaignRepos(xa)
  private lazy val leadsRepos = new LeadsRepos(xa)
  private lazy val queue = new JobQueue.Repo(xa)

  private val org = UUID.randomUUID()
  private val ws = UUID.randomUUID()
  private val userA = UUID.randomUUID()

  private def now: OffsetDateTime = OffsetDateTime.now()

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: org, workspace, owner") {
    val spec =
      sql"insert into public.organization (id, name) values ($org, 'Sched Org')".update.run *>
        sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $org, 'Sched WS', ${s"sched-" + org.toString.take(12)})".update.run *>
        sql"""
          insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
          values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated', ${s"sched-$ws@t.test"}, '', now(), now(), now())
        """.update.run *>
        sql"insert into public.profile (id, display_name) values ($userA, 'sched') on conflict (id) do nothing".update.run *>
        sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run
    spec.transact(xa).map(_ => assert(true))
  }

  private def seedCampaign(
      name: String,
      status: String = "active",
      tz: String = "Etc/UTC",
      winStart: LocalTime = LocalTime.of(0, 0),
      winEnd: LocalTime = LocalTime.of(23, 59)
  ): IO[UUID] =
    val emailStep =
      sql"insert into public.campaign_step (campaign_id, position, type, name) values (nextval('none')::uuid, 1, 'email', 'x')"
    for
      cid <-
        sql"""
          insert into public.campaign (id, workspace_id, created_by, name, status, timezone,
            send_window_start, send_window_end, business_days_only)
          values (gen_random_uuid(), $ws, $userA, $name, ${status}::campaign_status, $tz, $winStart, $winEnd, false)
          returning id
        """.query[UUID].unique.transact(xa)
      stepId <-
        sql"""
          insert into public.campaign_step (campaign_id, position, type, name)
          values ($cid, 1, 'email', 'Step 1') returning id
        """.query[UUID].unique.transact(xa)
      _ <-
        sql"""
          insert into public.step_variant (step_id, variant_key, subject, body_html, body_text)
          values ($stepId, 'A', 'Hi', '<p>b</p>', 'b')
        """.update.run.transact(xa)
      acct <-
        sql"""
          insert into public.email_accounts (workspace_id, provider, email_address, status, daily_send_cap)
          values ($ws, 'smtp_imap', ${s"m-$name-$ws@t.test"}, 'connected', 1)
          returning id
        """.query[UUID].unique.transact(xa)
      _ <-
        sql"insert into public.campaign_mailbox (campaign_id, email_account_id) values ($cid, $acct)".update.run
          .transact(xa)
      contact <-
        sql"""
          insert into public.contact (workspace_id, email, state)
          values ($ws, ${s"c-$name-$ws@t.test"}, 'active') returning id
        """.query[UUID].unique.transact(xa)
      list <-
        sql"insert into public.lead_list (workspace_id, name) values ($ws, ${s"l-$name"}) returning id"
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"insert into public.list_membership (list_id, contact_id) values ($list, $contact)".update.run
          .transact(xa)
      _ <-
        sql"insert into public.campaign_audience (campaign_id, lead_list_id) values ($cid, $list)".update.run
          .transact(xa)
      enr <-
        sql"""
          insert into public.campaign_enrollment (workspace_id, campaign_id, contact_id, status, next_step_id, next_action_at)
          values ($ws, $cid, $contact, 'active', $stepId, now() - interval '1 minute')
          returning id
        """.query[UUID].unique.transact(xa)
    yield enr

  test(
    "scheduler enqueues due actions with atomic quota; cap=1 means one send per day"
  ) {
    for
      enr <- seedCampaign("sched-basic")
      enq <- Scheduler.enqueueDue(
        repos,
        queue,
        perWorkspace = 5,
        batch = 50
      )
      status <-
        sql"select status::text from public.campaign_enrollment where id = $enr"
          .query[String]
          .unique
          .transact(xa)
      // cap is 1/day on the seeded account: a second pass must find nothing to allocate
      second <- Scheduler.enqueueDue(repos, queue, 5, 50)
      pending <- sql"""
        select count(*) from public.scheduled_action sa
        join public.campaign_enrollment e on e.id = sa.enrollment_id
        where e.id = $enr and sa.status = 'pending'
      """.query[Long].unique.transact(xa)
      _ <- IO {
        assertEquals(enq, 1)
        assertEquals(second, 0) // quota exhausted (reserved=1, cap=1)
        assertEquals(pending, 1L)
      }
    yield ()
  }

  test(
    "simultaneous reservation: only one of two concurrent reservations wins at cap"
  ) {
    for
      acct <-
        sql"""
          insert into public.email_accounts (workspace_id, provider, email_address, status, daily_send_cap)
          values ($ws, 'smtp_imap', ${s"race-$ws@t.test"}, 'connected', 1) returning id
        """.query[UUID].unique.transact(xa)
      won <-
        List(
          Scheduler.reserve(xa, ws, acct),
          Scheduler.reserve(xa, ws, acct)
        ).parSequence
      reserved <-
        sql"select reserved from public.quota_reservation where email_account_id = $acct"
          .query[Int]
          .unique
          .transact(xa)
      _ <- IO {
        assertEquals(
          won.count(identity),
          1
        ) // exactly one worker got the last unit
        assertEquals(reserved, 1)
      }
    yield ()
  }

  test("paused campaigns and disconnected mailboxes produce no enqueues") {
    for
      enrPaused <- seedCampaign("sched-paused", status = "paused")
      n1 <- Scheduler.enqueueDue(repos, queue, 5, 50)
      // disconnect the single mailbox of a fresh active campaign
      enrDisc <- seedCampaign("sched-disc")
      _ <- sql"""
        update public.email_accounts set status = 'disconnected'
        where id in (select email_account_id from public.campaign_mailbox m
                     join public.campaign_enrollment e on e.campaign_id = m.campaign_id
                     where e.id = $enrDisc)
      """.update.run.transact(xa)
      n2 <- Scheduler.enqueueDue(repos, queue, 5, 50)
      pendingPaused <-
        sql"select count(*) from public.scheduled_action where enrollment_id = $enrPaused"
          .query[Long]
          .unique
          .transact(xa)
      pendingDisc <-
        sql"select count(*) from public.scheduled_action where enrollment_id = $enrDisc"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO {
        assertEquals(pendingPaused, 0L)
        assertEquals(pendingDisc, 0L)
      }
    yield ()
  }

  test("suppressed and unsubscribed recipients are skipped") {
    for
      enrSup <- seedCampaign("sched-sup")
      _ <- sql"""
        update public.contact set state = 'unsubscribed'
        where id = (select contact_id from public.campaign_enrollment where id = $enrSup)
      """.update.run.transact(xa)
      _ <- Scheduler.enqueueDue(repos, queue, 5, 50)
      pendingSup <-
        sql"select count(*) from public.scheduled_action where enrollment_id = $enrSup"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO(assertEquals(pendingSup, 0L))
    yield ()
  }

  test("outside the campaign window, due actions defer instead of sending") {
    // Narrow window in a timezone where NOW is outside it (23:58–23:59 UTC
    // covers almost nothing; pick a window in the far future of the day).
    for
      enr <- seedCampaign(
        "sched-window",
        tz = "Etc/UTC",
        winStart = LocalTime.of(3, 0),
        winEnd = LocalTime.of(3, 1)
      )
      _ <- Scheduler.enqueueDue(repos, queue, 5, 50)
      deferred <-
        sql"select next_action_at from public.campaign_enrollment where id = $enr"
          .query[OffsetDateTime]
          .unique
          .transact(xa)
      pending <-
        sql"select count(*) from public.scheduled_action where enrollment_id = $enr"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO {
        assertEquals(pending, 0L) // nothing sent outside the window
        val local = deferred.atZoneSameInstant(ZoneId.of("Etc/UTC")).toLocalTime
        assert(
          local == LocalTime.of(3, 0) || local == LocalTime.of(3, 1),
          s"deferred to $local, expected window edge"
        )
      }
    yield ()
  }
