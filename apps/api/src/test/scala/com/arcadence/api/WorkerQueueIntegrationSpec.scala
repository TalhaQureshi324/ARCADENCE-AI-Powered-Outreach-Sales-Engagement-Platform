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

import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.duration.DurationInt

/** Durable job infrastructure (Phase 5.1): atomic claiming under concurrent
  * workers, replay-safe enqueueing, lease-expiry crash recovery, retry with
  * backoff to dead-letter, and recovery after a database interruption.
  */
final class WorkerQueueIntegrationSpec extends CatsEffectSuite:

  private val dbUrl = sys.env.getOrElse("TEST_DATABASE_URL", "")
  private lazy val jdbc =
    DbUrl.parse(dbUrl).fold(_ => sys.error("bad TEST_DATABASE_URL"), identity)

  private def makeTransactor: Transactor[IO] =
    Transactor.fromDriverManager[IO](
      "org.postgresql.Driver",
      jdbc.jdbcUrl,
      jdbc.user,
      jdbc.password,
      logHandler = None
    )

  private lazy val xa = makeTransactor
  private lazy val repo = new JobQueue.Repo(xa)

  private val ws = UUID.randomUUID()

  private def enqueue(
      key: String,
      runAfter: OffsetDateTime = OffsetDateTime.now().minusSeconds(1),
      maxAttempts: Int = 3
  ): IO[Boolean] =
    repo.enqueue(
      JobQueue.EnqueueInput(
        workspaceId = ws,
        campaignId = None,
        enrollmentId = None,
        contactId = None,
        emailAccountId = None,
        actionType = "generic_job",
        idempotencyKey = key,
        payload = Json.obj("k" -> Json.fromString(key)),
        runAfter = runAfter,
        maxAttempts = maxAttempts
      )
    )

  private def count(status: String): IO[Long] =
    sql"select count(*) from public.scheduled_action where workspace_id = $ws and status = ${status}::action_status"
      .query[Long]
      .unique
      .transact(xa)

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  override def afterAll(): Unit =
    if dbUrl.nonEmpty then
      sql"delete from public.scheduled_action where workspace_id = $ws".update.run.void
        .transact(xa)
        .unsafeRunSync()
      sql"delete from public.workspace where id = $ws".update.run.void
        .transact(xa)
        .unsafeRunSync()
      super.afterAll()

  test("setup: workspace row exists for the queue test") {
    val spec = sql"""
      insert into public.organization (name)
      values (${"Worker Org"})
      returning id
    """.query[UUID].unique.flatMap { orgId =>
      sql"""
        insert into public.workspace (id, organization_id, name, slug, timezone)
        values ($ws, $orgId, ${"Worker WS"}, ${s"worker-${ws.toString.take(8)}"}, 'Etc/UTC')
        returning id
      """.query[UUID].unique
    }
    spec.transact(xa).map(id => assertEquals(id, ws))
  }

  test(
    "replay-safe enqueue: duplicate idempotency keys produce exactly one job"
  ) {
    for
      first <- enqueue("dup-1")
      second <- enqueue("dup-1")
      status <- repo.findStatus("dup-1")
    yield
      assertEquals(first, true)
      assertEquals(second, false)
      assertEquals(status, Some("pending"))
  }

  test(
    "concurrent workers never double-claim: each job is claimed exactly once"
  ) {
    val jobCount = 40
    val workerCount = 8
    for
      _ <- (1 to jobCount).toList.traverse_(i => enqueue(s"conc-$i"))
      claimed <-
        (1 to workerCount).toList.parTraverse { w =>
          repo.claimBatch(s"worker-$w", 10)
        }
      all = claimed.flatten.filter(j =>
        j.payload.hcursor
          .get[String]("k")
          .toOption
          .getOrElse("")
          .startsWith("conc-")
      )
      ids = all.map(_.id)
      _ <- IO(assertEquals(ids.size, jobCount))
      _ <- IO(assertEquals(ids.distinct.size, jobCount)) // no double-claim
      workers = all.map(_.lockedBy).distinct
      _ <- IO(
        assert(
          workers.size >= 2,
          s"expected multiple workers to participate, got $workers"
        )
      )
    yield ()
  }

  test(
    "worker termination: expired leases return jobs to pending and they can be re-claimed"
  ) {
    val key = "lease-1"
    for
      _ <- enqueue(key, maxAttempts = 5)
      _ <- repo.claimBatch("crashed-worker", 1)
      statusAfterClaim <- repo.findStatus(key)
      // simulate time passing beyond the lease by backdating locked_at
      _ <- sql"""
        update public.scheduled_action
        set locked_at = now() - interval '1 hour'
        where idempotency_key = $key
      """.update.run.void.transact(xa)
      requeued <- repo.requeueExpiredLeases(5.minutes)
      statusAfterRequeue <- repo.findStatus(key)
      reclaimed <- repo.claimBatch("fresh-worker", 5)
      _ <- IO {
        assertEquals(statusAfterClaim, Some("claimed"))
        assert(requeued >= 1)
        assertEquals(statusAfterRequeue, Some("pending"))
        assert(reclaimed.exists(_.lockedBy == "fresh-worker"))
      }
    yield ()
  }

  test(
    "retry and dead-letter: failures back off until max_attempts, then fail"
  ) {
    val key = "retry-1"
    for
      _ <- enqueue(key, maxAttempts = 2)
      c1 <- repo.claimBatch("w1", 1)
      _ = assertEquals(c1.map(_.attempts), List(1))
      o1 <- repo.failOrRetry(
        c1.head.id,
        1,
        2,
        "w1",
        "transient error",
        Some(0.seconds)
      )
      status1 <- repo.findStatus(key)
      c2 <- repo.claimBatch("w1", 1)
      _ = assertEquals(c2.map(_.attempts), List(2))
      o2 <- repo.failOrRetry(
        c2.head.id,
        2,
        2,
        "w1",
        "transient again",
        Some(0.seconds)
      )
      status2 <- repo.findStatus(key)
      attempts <-
        sql"select count(*) from public.job_attempt ja join public.scheduled_action sa on sa.id = ja.action_id where sa.idempotency_key = $key"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO {
        assert(o1.isInstanceOf[JobQueue.Retry])
        assertEquals(status1, Some("pending"))
        assert(o2.isInstanceOf[JobQueue.Fail])
        assertEquals(status2, Some("failed")) // dead-lettered
        assertEquals(attempts, 2L)
      }
    yield ()
  }

  test(
    "database reconnection: operations resume on a fresh transactor after an interruption"
  ) {
    val key = "reconnect-1"
    for
      _ <- enqueue(key)
      // "interruption": drop this transactor's role entirely and operate via a new one
      freshRepo = new JobQueue.Repo(makeTransactor)
      claimed <- freshRepo.claimBatch("post-restart", 5)
      done <- freshRepo.complete(
        claimed
          .filter(_.id.toString.nonEmpty)
          .find(_.lockedBy == "post-restart")
          .get
          .id,
        1,
        "post-restart"
      )
      status <- freshRepo.findStatus(key)
      _ <- IO {
        assertEquals(done, ())
        assertEquals(status, Some("done"))
      }
    yield ()
  }
