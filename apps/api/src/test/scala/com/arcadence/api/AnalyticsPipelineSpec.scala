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

import java.time.LocalDate
import java.util.UUID

/** Metrics pipeline (Phase 7.1): trigger-collected domain events, derived daily
  * aggregate rebuild, and reconciliation — aggregate totals must equal raw
  * event counts.
  */
final class AnalyticsPipelineSpec extends CatsEffectSuite:

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

  private val org = UUID.randomUUID()
  private val ws = UUID.randomUUID()

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: workspace + active campaign") {
    for
      _ <-
        (sql"insert into public.organization (id, name) values ($org, 'Analytics Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $org, 'Analytics WS', ${"an-" + ws.toString.take(12)})".update.run).void
          .transact(xa)
      _ <-
        sql"""insert into public.campaign (id, workspace_id, created_by, name, status)
              values (gen_random_uuid(), $ws, null, 'Analytics campaign', 'draft')""".update.run
          .transact(xa)
      cid <-
        sql"select id from public.campaign where workspace_id = $ws limit 1"
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"""update public.campaign set status = 'active' where id = $cid""".update.run
          .transact(xa)
    yield ()
  }

  test("triggers collect genuine domain events from real engine mutations") {
    val cid =
      sql"select id from public.campaign where workspace_id = $ws limit 1"
        .query[UUID]
        .unique
        .transact(xa)
    for
      cidV <- cid
      contact <-
        sql"insert into public.contact (workspace_id, email, state) values ($ws, ${"a-" + ws.toString.take(6) + "@t.test"}, 'active') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      acct <-
        sql"""insert into public.email_accounts (workspace_id, provider, email_address, status)
              values ($ws, 'smtp_imap', ${"an-$ws@t.test"}, 'connected') returning id"""
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"""insert into public.scheduled_action (workspace_id, campaign_id, contact_id, email_account_id,
              type, idempotency_key, payload, run_after)
              values ($ws, $cidV, $contact, $acct, 'send_email', ${"an-" + UUID
            .randomUUID()}, '{}', now())""".update.run.void.transact(xa)
      _ <-
        sql"""insert into public.send_attempt (workspace_id, action_id, email_account_id, provider, status, detail)
              select $ws, sa.id, $acct, 'smtp_imap', 'accepted', '{}'::jsonb
              from public.scheduled_action sa where sa.workspace_id = $ws order by sa.created_at desc limit 1""".update.run
          .transact(xa)
      // refresh aggregates for today
      _ <-
        sql"select public.refresh_campaign_daily_stats(current_date, current_date) is null"
          .query[Boolean]
          .unique
          .transact(xa)
          .void
      stats <-
        sql"""select send_scheduled, accepted, unique_leads_contacted from public.campaign_daily_stats
              where workspace_id = $ws and day = current_date"""
          .query[(Int, Int, Int)]
          .option
          .transact(xa)
      activation <- sql"""
        select count(*) from public.campaign_event
        where workspace_id = $ws and event_type = 'campaign_activated'"""
        .query[Long]
        .unique
        .transact(xa)
      _ <- IO {
        assertEquals(stats, Some((1, 1, 1)))
        assertEquals(activation, 1L)
      }
    yield ()
  }

  test("reconciliation: aggregate totals equal raw event counts") {
    for
      raw <-
        sql"""select event_type, count(*) from public.campaign_event where workspace_id = $ws group by event_type"""
          .query[(String, Long)]
          .to[List]
          .transact(xa)
      agg <-
        sql"""select send_scheduled, accepted, unique_leads_contacted from public.campaign_daily_stats where workspace_id = $ws"""
          .query[(Int, Int, Int)]
          .to[List]
          .transact(xa)
      rawSched = raw.filter(_._1 == "send_scheduled").map(_._2).sum
      rawAcc = raw.filter(_._1 == "accepted").map(_._2).sum
      _ <- IO {
        assertEquals(agg.map(_._1.toLong).sum, rawSched)
        assertEquals(agg.map(_._2.toLong).sum, rawAcc)
      }
    yield ()
  }
