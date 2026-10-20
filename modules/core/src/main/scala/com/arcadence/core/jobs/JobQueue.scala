package com.arcadence.core.jobs

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json

import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.duration.*

/** Durable sending-job queue over `public.scheduled_action` (Phase 5.1).
  *
  * Concurrency model: workers claim due jobs with `FOR UPDATE SKIP LOCKED` + a
  * lease (`locked_at`/`locked_by`, `attempts` incremented at claim time). A
  * worker that dies leaves its lease to expire; `requeueExpiredLeases` returns
  * such rows to `pending`. Failures retry with capped exponential backoff
  * (+jitter) until `max_attempts`, then dead-letter as `failed`.
  *
  * Idempotency: enqueueing with a stable business `idempotencyKey` (e.g.
  * `send:{enrollment}:{step}:{dueDate}`) is replay-safe — duplicates are
  * dropped by a unique index, so at-least-once enqueueing never produces
  * duplicate sends.
  */
object JobQueue:

  /** Worker-side execution contract for a claimed job. */
  trait Handler:
    def handles(actionType: String): Boolean
    def run(job: Claimed): IO[Outcome]

  sealed trait Outcome
  case object Done extends Outcome
  final case class Retry(reason: String, after: FiniteDuration) extends Outcome
  final case class Fail(reason: String) extends Outcome

  final case class Claimed(
      id: UUID,
      workspaceId: UUID,
      actionType: String,
      enrollmentId: Option[UUID],
      contactId: Option[UUID],
      emailAccountId: Option[UUID],
      attempts: Int,
      maxAttempts: Int,
      payload: Json,
      lockedBy: String
  )

  final case class EnqueueInput(
      workspaceId: UUID,
      campaignId: Option[UUID],
      enrollmentId: Option[UUID],
      contactId: Option[UUID],
      emailAccountId: Option[UUID],
      actionType: String,
      idempotencyKey: String,
      payload: Json,
      runAfter: OffsetDateTime,
      maxAttempts: Int = 8
  )

  final class Repo(val transactor: Transactor[IO]):

    /** Replay-safe enqueue; returns false when the idempotency key exists. */
    def enqueue(in: EnqueueInput): IO[Boolean] =
      sql"""
        insert into public.scheduled_action
          (workspace_id, campaign_id, enrollment_id, contact_id, email_account_id,
           type, idempotency_key, payload, run_after, max_attempts)
        values (${in.workspaceId}, ${in.campaignId}, ${in.enrollmentId}, ${in.contactId},
                ${in.emailAccountId}, ${in.actionType}::action_type, ${in.idempotencyKey},
                ${in.payload.noSpaces}::jsonb, ${in.runAfter}, ${in.maxAttempts})
        on conflict (idempotency_key) do nothing
        returning id
      """.query[UUID].option.map(_.isDefined).transact(transactor)

    /** Claims one specific action (test/ops helper) under the same lease rules.
      */
    def claimBatchFor(actionId: UUID, workerId: String): IO[Option[Claimed]] =
      sql"""
        update public.scheduled_action sa
        set status = 'claimed', locked_at = now(), locked_by = $workerId,
            attempts = sa.attempts + 1
        where sa.id = $actionId and sa.status in ('pending', 'failed')
        returning sa.id, sa.workspace_id, sa.type::text, sa.enrollment_id,
                  sa.contact_id, sa.email_account_id, sa.attempts, sa.max_attempts,
                  sa.payload::text, sa.locked_by
      """
        .query[
          (
              UUID,
              UUID,
              String,
              Option[UUID],
              Option[UUID],
              Option[UUID],
              Int,
              Int,
              String,
              String
          )
        ]
        .map { t =>
          Claimed(
            t._1,
            t._2,
            t._3,
            t._4,
            t._5,
            t._6,
            t._7,
            t._8,
            io.circe.parser.parse(t._9).getOrElse(Json.Null),
            t._10
          )
        }
        .option
        .transact(transactor)

    /** Atomically claims up to `limit` due jobs for `workerId`. */
    def claimBatch(workerId: String, limit: Int): IO[List[Claimed]] =
      sql"""
        update public.scheduled_action sa
        set status = 'claimed', locked_at = now(), locked_by = $workerId,
            attempts = sa.attempts + 1
        where sa.id in (
          select id from public.scheduled_action
          where status = 'pending' and run_after <= now()
          order by run_after
          limit $limit
          for update skip locked
        )
        returning sa.id, sa.workspace_id, sa.type::text, sa.enrollment_id,
                  sa.contact_id, sa.email_account_id, sa.attempts, sa.max_attempts,
                  sa.payload::text, sa.locked_by
      """
        .query[
          (
              UUID,
              UUID,
              String,
              Option[UUID],
              Option[UUID],
              Option[UUID],
              Int,
              Int,
              String,
              String
          )
        ]
        .map { t =>
          Claimed(
            t._1,
            t._2,
            t._3,
            t._4,
            t._5,
            t._6,
            t._7,
            t._8,
            io.circe.parser.parse(t._9).getOrElse(Json.Null),
            t._10
          )
        }
        .to[List]
        .transact(transactor)

    def recordAttempt(
        actionId: UUID,
        attempt: Int,
        workerId: String,
        outcome: String,
        detail: Json
    ): ConnectionIO[Unit] =
      sql"""
        insert into public.job_attempt (action_id, attempt, worker_id, outcome, detail, finished_at)
        values ($actionId, $attempt, $workerId, $outcome, ${detail.noSpaces}::jsonb, now())
      """.update.run.void

    def complete(actionId: UUID, attempt: Int, workerId: String): IO[Unit] =
      sql"""
        update public.scheduled_action
        set status = 'done', locked_at = null, last_error = null
        where id = $actionId and status = 'claimed' and locked_by = $workerId
      """.update.run
        .flatMap(_ =>
          recordAttempt(actionId, attempt, workerId, "done", Json.obj())
        )
        .void
        .transact(transactor)

    /** Marks the outcome: retries with capped exponential backoff (+jitter), or
      * dead-letters as `failed` once `maxAttempts` is exhausted.
      */
    def failOrRetry(
        actionId: UUID,
        attempt: Int,
        maxAttempts: Int,
        workerId: String,
        reason: String,
        retryAfter: Option[FiniteDuration]
    ): IO[Outcome] =
      val detail = Json.obj("reason" -> Json.fromString(reason))
      if attempt >= maxAttempts then
        sql"""
          update public.scheduled_action
          set status = 'failed', locked_at = null, last_error = $reason
          where id = $actionId and status = 'claimed' and locked_by = $workerId
        """.update.run
          .flatMap(_ =>
            recordAttempt(actionId, attempt, workerId, "failed", detail)
          )
          .as(Fail(reason): Outcome)
          .transact(transactor)
      else
        val delaySeconds = retryAfter
          .map(_.toSeconds)
          .getOrElse {
            val base =
              math.min(3600L, math.pow(2, attempt.toDouble).toLong * 60L)
            val jitter = scala.util.Random.nextLong(base / 4 + 1)
            base + jitter
          }
        sql"""
          update public.scheduled_action
          set status = 'pending', locked_at = null, last_error = $reason,
              run_after = now() + ${delaySeconds * 1000L} * interval '1 millisecond'
          where id = $actionId and status = 'claimed' and locked_by = $workerId
        """.update.run
          .flatMap(_ =>
            recordAttempt(actionId, attempt, workerId, "retry", detail)
          )
          .as(Retry(reason, delaySeconds.seconds): Outcome)
          .transact(transactor)

    /** Crash recovery: returns lease-expired jobs to the pending state. */
    def requeueExpiredLeases(leaseTimeout: FiniteDuration): IO[Int] =
      sql"""
        update public.scheduled_action
        set status = 'pending', locked_at = null
        where status = 'claimed' and locked_at < now() - ${leaseTimeout.toMillis} * interval '1 millisecond'
      """.update.run.transact(transactor)

    def findStatus(idempotencyKey: String): IO[Option[String]] =
      sql"select status::text from public.scheduled_action where idempotency_key = $idempotencyKey"
        .query[String]
        .option
        .transact(transactor)
