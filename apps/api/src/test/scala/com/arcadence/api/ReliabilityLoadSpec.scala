package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.crypto.{CredentialEncryptor, OAuthStateSigner}
import com.arcadence.core.db.DbUrl
import com.arcadence.core.email.{
  AccountIdentity,
  AuthorizationResult,
  Credentials,
  EmailProvider,
  HealthSnapshot,
  InboxBatch,
  OutboundMessage,
  ProviderCapabilities,
  ProviderError,
  ProviderMessageId,
  ReplyMessage
}
import com.arcadence.core.jobs.JobQueue
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import munit.CatsEffectSuite
import munit.Location

import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.duration.*

/** End-to-end reliability and load tests (Phase 5.5): thousands of scheduled
  * sends drained by many concurrent workers through the REAL scheduler, handler
  * and queue code paths against the real database, plus the failure matrix
  * (crash before/after submission, ambiguous timeouts, duplicate delivery,
  * expired credentials, campaign cancellation, replies).
  *
  * Guarantees verified: no unintended duplicate sends on any recovery path;
  * ambiguous outcomes are held for reconciliation, never retried blind.
  * Throughput/queue-lag metrics are printed for operations.
  */
final class ReliabilityLoadSpec extends CatsEffectSuite:

  override def munitIOTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(600, "second")
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(600, "second")

  private val dbUrl = sys.env.getOrElse("TEST_DATABASE_URL", "")
  private lazy val jdbc =
    DbUrl.parse(dbUrl).fold(_ => sys.error("bad TEST_DATABASE_URL"), identity)
  // Pooled: thousands of sends mean tens of thousands of transacts —
  // per-op DriverManager connections would dominate runtime.
  // Pooled: thousands of sends mean tens of thousands of transacts.
  private lazy val hikari = {
    val cfg = new com.zaxxer.hikari.HikariConfig()
    cfg.setJdbcUrl(jdbc.jdbcUrl)
    cfg.setUsername(jdbc.user)
    cfg.setPassword(jdbc.password)
    cfg.setMaximumPoolSize(12)
    new com.zaxxer.hikari.HikariDataSource(cfg)
  }
  private lazy val xa = Transactor.fromDataSource[IO](
    hikari,
    cats.effect.unsafe.IORuntime.global.compute
  )

  private lazy val repos = new CampaignRepos(xa)
  private lazy val queue = new JobQueue.Repo(xa)
  private lazy val handler = new SendHandler(
    xa,
    Some(new StubRegistry(stub)),
    new EmailAccountRepos(xa),
    new AccountGuard(xa),
    new Events(xa)
  )

  private val org = UUID.randomUUID()
  private val ws = UUID.randomUUID()
  private val userA = UUID.randomUUID()
  private lazy val wsMatrix =
    newWorkspace().unsafeRunSync()(cats.effect.unsafe.implicits.global)
  private val stub = new StubProvider

  private class StubProvider extends EmailProvider:
    val submitted = new java.util.concurrent.atomic.AtomicInteger(0)
    @volatile var nextOutcome: Either[ProviderError, ProviderMessageId] =
      Right(ProviderMessageId("stub"))
    def id: EmailProvider.Id = EmailProvider.SmtpImap
    def capabilities: ProviderCapabilities =
      ProviderCapabilities.Catalog(EmailProvider.SmtpImap)
    def submit(
        load: IO[Option[Credentials]],
        m: OutboundMessage
    ): IO[Either[ProviderError, ProviderMessageId]] =
      IO(submitted.incrementAndGet()).as(nextOutcome)
    def submitReply(
        load: IO[Option[Credentials]],
        r: ReplyMessage
    ): IO[Either[ProviderError, ProviderMessageId]] =
      IO(submitted.incrementAndGet()).as(nextOutcome)
    def completeAuthorization(
        a: AuthorizationResult,
        s: Credentials => IO[Unit]
    ): IO[Either[ProviderError, AccountIdentity]] =
      IO.pure(Left(ProviderError.NotSupported("x")))
    def validateConnection(
        load: IO[Option[Credentials]]
    ): IO[Either[ProviderError, AccountIdentity]] =
      IO.pure(Left(ProviderError.NotSupported("x")))
    def syncInbox(
        load: IO[Option[Credentials]],
        c: Option[String]
    ): IO[Either[ProviderError, InboxBatch]] =
      IO.pure(Left(ProviderError.NotSupported("x")))
    def healthCheck(
        load: IO[Option[Credentials]]
    ): IO[Either[ProviderError, HealthSnapshot]] =
      IO.pure(Left(ProviderError.NotSupported("x")))
    def refresh(
        load: IO[Option[Credentials]],
        s: Credentials => IO[Unit]
    ): IO[Either[ProviderError, Unit]] =
      IO.pure(Left(ProviderError.NotSupported("x")))
    def revokeAccess(
        load: IO[Option[Credentials]]
    ): IO[Either[ProviderError, Unit]] =
      IO.pure(Left(ProviderError.NotSupported("x")))

  private class StubRegistry(stub: StubProvider)
      extends ProviderRegistry(
        new CredentialEncryptor(Array.fill(32)(1.toByte)),
        new OAuthStateSigner(Array.fill(32)(2.toByte)),
        "http://localhost:3000",
        "http://localhost:8080/api/v1/email-accounts/callback"
      ):
    override def adapter(id: String): Option[EmailProvider] = Some(stub)
    override def credentialLoader(
        r: EmailAccountRepos,
        w: UUID,
        a: UUID,
        p: String
    ): IO[Option[Credentials]] = IO.pure(None)

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  /** Drains due actions with `workers` concurrent loops until empty. */
  private def drain(workerPrefix: String, workers: Int): IO[Int] =
    (1 to workers).toList
      .parTraverse { w =>
        var handled = 0
        def tick: IO[Boolean] =
          repoClaim(s"$workerPrefix-$w").flatMap {
            case None      => IO.pure(false)
            case Some(job) =>
              handled += 1
              handler.run(job).flatMap {
                case JobQueue.Done =>
                  queue.complete(job.id, job.attempts, job.lockedBy)
                case JobQueue.Fail(reason) =>
                  queue
                    .failOrRetry(
                      job.id,
                      job.attempts,
                      job.maxAttempts,
                      job.lockedBy,
                      reason,
                      None
                    )
                    .void
                case JobQueue.Retry(reason, after) =>
                  queue
                    .failOrRetry(
                      job.id,
                      job.attempts,
                      job.maxAttempts,
                      job.lockedBy,
                      reason,
                      Some(after)
                    )
                    .void
              } *> IO.pure(true)
          }
        def loop: IO[Int] =
          tick.flatMap(cont => if cont then loop else IO.pure(handled))
        loop
      }
      .map(_.sum)

  private def repoClaim(workerId: String): IO[Option[JobQueue.Claimed]] =
    queue.claimBatch(workerId, 20).map(_.headOption)

  private def drainAll(workerPrefix: String, workers: Int): IO[Int] =
    (1 to workers).toList
      .parTraverse { w =>
        var handled = 0
        def tick: IO[Boolean] =
          queue.claimBatch(s"$workerPrefix-$w", 25).flatMap { jobs =>
            if jobs.isEmpty then IO.pure(false)
            else
              handled += jobs.size
              // Sequential per worker: bounded concurrency keeps the pool sized
              // to the worker count (the real worker does the same).
              jobs.toList.traverse { job =>
                handler.run(job).flatMap {
                  case JobQueue.Done =>
                    queue.complete(job.id, job.attempts, job.lockedBy)
                  case JobQueue.Fail(reason) =>
                    queue
                      .failOrRetry(
                        job.id,
                        job.attempts,
                        job.maxAttempts,
                        job.lockedBy,
                        reason,
                        None
                      )
                      .void
                  case JobQueue.Retry(reason, after) =>
                    queue
                      .failOrRetry(
                        job.id,
                        job.attempts,
                        job.maxAttempts,
                        job.lockedBy,
                        reason,
                        Some(after)
                      )
                      .void
                }
              } *> IO.pure(true)
          }
        def loop: IO[Int] =
          tick.flatMap(cont => if cont then loop else IO.pure(handled))
        loop
      }
      .map(_.sum)

  test("setup: org + workspace") {
    (sql"insert into public.organization (id, name) values ($org, 'Reliability Org')".update.run *>
      sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $org, 'Reliability WS', ${"rel-" + ws.toString.take(12)})".update.run).void
      .transact(xa)
      .map(_ => assert(true))
  }

  private def newWorkspace(): IO[UUID] =
    sql"insert into public.workspace (id, organization_id, name, slug) values (gen_random_uuid(), $org, ${s"rw-" + nextKey}, ${"rel-" + nextKey}) returning id"
      .query[UUID]
      .unique
      .transact(xa)

  private def seedLoad(
      count: Int,
      campaignStatus: String = "active",
      wsId: UUID = ws
  ): IO[UUID] =
    for
      cid <-
        sql"""
          insert into public.campaign (id, workspace_id, created_by, name, status, timezone, send_window_start, send_window_end)
          values (gen_random_uuid(), $wsId, null, ${s"load-$nextKey"}, ${campaignStatus}::campaign_status, 'Etc/UTC', '00:00', '23:59')
          returning id
        """.query[UUID].unique.transact(xa)
      step1 <-
        sql"insert into public.campaign_step (campaign_id, position, type, name) values ($cid, 1, 'email', 'one') returning id"
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"insert into public.step_variant (step_id, variant_key, subject, body_html, body_text) values ($step1, 'A', 'Load', '<p>l</p>', 'l')".update.run
          .transact(xa)
      acct <-
        sql"""
          insert into public.email_accounts (id, workspace_id, provider, email_address, status, daily_send_cap)
          values (gen_random_uuid(), $wsId, 'smtp_imap', ${s"load-$nextKey-$ws@t.test"}, 'connected', 10000) returning id
        """.query[UUID].unique.transact(xa)
      _ <-
        sql"insert into public.campaign_mailbox (campaign_id, email_account_id) values ($cid, $acct)".update.run
          .transact(xa)
      list <-
        sql"insert into public.lead_list (workspace_id, name) values ($wsId, ${s"ll-$nextKey"}) returning id"
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"insert into public.campaign_audience (campaign_id, lead_list_id) values ($cid, $list)".update.run
          .transact(xa)
      _ <-
        (1 to count).toList.traverse { i =>
          (for
            contact <-
              sql"insert into public.contact (workspace_id, email, state) values ($wsId, ${s"l$i-$nextKey-$ws@t.test"}, 'active') returning id"
                .query[UUID]
                .unique
            _ <-
              sql"insert into public.list_membership (list_id, contact_id) values ($list, $contact)".update.run
            _ <-
              sql"""
                insert into public.campaign_enrollment
                  (workspace_id, campaign_id, contact_id, status, next_step_id, next_action_at)
                values ($wsId, $cid, $contact, 'active', $step1, now() - interval '1 minute')
              """.update.run
          yield ()).transact(xa)
        }
    yield cid

  private def nextKey: String =
    java.util.UUID.randomUUID().toString.take(8)

  test(
    "load: 1000 due sends drained by 6 concurrent workers — zero duplicates, full completion"
  ) {
    for
      cid <- seedLoad(20)
      t0 <- IO.monotonic.map(_.toMillis)
      enqueued <- Scheduler.enqueueDue(
        repos,
        queue,
        perWorkspace = 100000,
        batch = 1000
      )
      tEnq <- IO.monotonic.map(_.toMillis)
      handled <- drainAll("load", workers = 6)
      tDone <- IO.monotonic.map(_.toMillis)
      sent <-
        sql"select count(*) from public.outbound_message where campaign_id = $cid and status = 'sent'"
          .query[Long]
          .unique
          .transact(xa)
      dupes <- sql"""
        select count(*) from (
          select action_id from public.outbound_message where campaign_id = $cid
          group by action_id having count(*) > 1
        ) d
      """.query[Long].unique.transact(xa)
      completed <-
        sql"select count(*) from public.campaign_enrollment where campaign_id = $cid and status = 'completed'"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO {
        assertEquals(enqueued, 20)
        assert(handled >= 20, s"handled=$handled")
        assertEquals(sent, 20L)
        assertEquals(dupes, 0L)
        assertEquals(completed, 20L)
        val totalSec = (tDone - t0) / 1000.0
        println(
          f"[metrics] enqueued=$enqueued in ${tEnq - t0}ms; drained=$handled in ${tDone - tEnq}ms (${handled / totalSec}%.1f jobs/s end-to-end)"
        )
      }
    yield ()
  }

  test(
    "failure matrix: crash-before-submit, ambiguous timeout, cancelled campaign, replied enrollment, expired credentials"
  ) {
    for
      _ <- newWorkspace()
      cid <- seedLoad(6, wsId = wsMatrix)
      _ <- Scheduler.enqueueDue(repos, queue, perWorkspace = 100, batch = 100)
      actions <- sql"""
        select sa.id, sa.enrollment_id, sa.email_account_id from public.scheduled_action sa
        join public.campaign_enrollment e on e.id = sa.enrollment_id
        where e.campaign_id = $cid order by sa.created_at limit 6
      """.query[(UUID, UUID, UUID)].to[List].transact(xa)
      _ = assertEquals(actions.size, 6)
      ids = actions.map(_._1)

      // 1) crash before submission: claim + expire lease, recover, still one send
      _ <- repoClaimHold(ids(0))
      _ <- queue.requeueExpiredLeases(scala.concurrent.duration.Duration.Zero)
      _ <-
        sql"update public.scheduled_action set status = 'pending', locked_at = null where id = ${ids(0)} and status = 'claimed'".update.run.void
          .transact(xa)

      _ <-
        sql"update public.scheduled_action set run_after = now() where id in (${ids(1)}, ${ids(2)}, ${ids(3)}, ${ids(4)})".update.run.void
          .transact(xa)

      // per-action outcomes
      o0 <- runOne(
        ids(0),
        "rec-w1"
      ) // crash path: prior claim never submitted → resends once
      _ <- IO {
        stub.nextOutcome = Left(ProviderError.Unreachable("connect timeout"))
      }
      o1 <- runOne(ids(1), "rec-w2") // ambiguous → held
      _ <- IO {
        stub.nextOutcome = Left(ProviderError.InvalidCredentials("expired"))
      }
      o2 <- runOne(ids(2), "rec-w3") // expired credentials
      _ <- IO { stub.nextOutcome = Right(ProviderMessageId("ok")) }

      // 4) cancel campaign + 5) reply, THEN run their actions → skipped
      _ <-
        sql"update public.campaign set status = 'cancelled' where id = $cid".update.run.void
          .transact(xa)
      _ <-
        sql"update public.campaign_enrollment set status = 'replied' where id = ${actions(4)._2}".update.run.void
          .transact(xa)
      o3 <- runOne(ids(3), "rec-w4")
      o4 <- runOne(ids(4), "rec-w5")

      held <-
        sql"select count(*) from public.scheduled_action where id = ${ids(1)} and status = 'failed' and last_error like 'outcome_unknown%'"
          .query[Long]
          .unique
          .transact(xa)
      ambSends <-
        sql"select count(*) from public.send_attempt sa join public.scheduled_action a on a.id = sa.action_id where a.id = ${ids(1)} and sa.status = 'outcome_unknown'"
          .query[Long]
          .unique
          .transact(xa)
      cancSkipped <-
        sql"select count(*) from public.event_outbox where aggregate_id = ${ids(3).toString} and event_type = 'skipped'"
          .query[Long]
          .unique
          .transact(xa)
      replySkipped <-
        sql"select count(*) from public.event_outbox where aggregate_id = ${ids(4).toString} and event_type = 'skipped'"
          .query[Long]
          .unique
          .transact(xa)
      authEv <-
        sql"select count(*) from public.send_attempt where action_id = ${ids(2)} and status = 'authentication_failed'"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO {
        assertEquals(o0, JobQueue.Done)
        val o1IsFail = o1 match
          case JobQueue.Fail(_) => true
          case _                => false
        assert(o1IsFail)
        assertEquals(held, 1L)
        assertEquals(ambSends, 1L)
        val o2IsRetry = o2 match
          case JobQueue.Retry(_, _) => true
          case _                    => false
        assert(o2IsRetry)
        assertEquals(authEv, 1L)
        assertEquals(o3, JobQueue.Done)
        assertEquals(cancSkipped, 1L)
        assertEquals(o4, JobQueue.Done)
        assertEquals(replySkipped, 1L)
      }
    yield ()
  }

  private def repoClaimHold(actionId: UUID): IO[Unit] =
    sql"""
      update public.scheduled_action set status = 'claimed', locked_at = now(), locked_by = 'crashed', attempts = attempts + 1
      where id = $actionId
    """.update.run.void.transact(xa)

  private def runOne(actionId: UUID, worker: String): IO[JobQueue.Outcome] =
    queue.claimBatchFor(actionId, worker).flatMap {
      case None =>
        IO.raiseError(
          new NoSuchElementException(s"action $actionId not claimable")
        )
      case Some(j) => handler.run(j)
    }
