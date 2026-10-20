package com.arcadence.worker

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.core.jobs.JobQueue
import fs2.Stream
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.concurrent.duration.*

/** The sending worker (Phase 5.1): a lease-based loop over `scheduled_action`
  * that resumes processing after restart.
  *
  * Every tick: recover expired leases, claim a batch (FOR UPDATE SKIP LOCKED),
  * execute each claimed job through the [[Handler]], and record the outcome
  * (done / retry with backoff / dead-letter). Handlers are pure functions of
  * the claimed job — the send handler itself lands in 5.3; a generic handler
  * stands in for now.
  */
object SendWorker:

  type Handler = com.arcadence.core.jobs.JobQueue.Handler

  /** Placeholder until the send handler arrives in 5.3. */
  object GenericHandler extends Handler:
    def handles(actionType: String): Boolean = true
    def run(job: JobQueue.Claimed): IO[JobQueue.Outcome] =
      IO.pure(JobQueue.Done)

  final case class Config(
      workerId: String,
      pollInterval: FiniteDuration,
      batchSize: Int,
      leaseTimeout: FiniteDuration
  )

  object Config:
    def fromEnv(env: Map[String, String]): Config =
      Config(
        workerId = env.getOrElse(
          "WORKER_ID",
          s"worker-${UUID.randomUUID().toString.take(8)}"
        ),
        pollInterval = env
          .get("WORKER_POLL_SECONDS")
          .flatMap(v => scala.util.Try(v.toLong).toOption)
          .getOrElse(5L)
          .seconds,
        batchSize = env
          .get("WORKER_BATCH_SIZE")
          .flatMap(v => scala.util.Try(v.toInt).toOption)
          .getOrElse(10),
        leaseTimeout = env
          .get("WORKER_LEASE_SECONDS")
          .flatMap(v => scala.util.Try(v.toLong).toOption)
          .getOrElse(300L)
          .seconds
      )

  def stream(repo: JobQueue.Repo, handlers: List[Handler], cfg: Config)(implicit
      log: Logger[IO]
  ): Stream[IO, Unit] =
    val dispatch: JobQueue.Claimed => IO[Unit] = job =>
      handlers.find(_.handles(job.actionType)) match
        case None =>
          repo
            .failOrRetry(
              job.id,
              job.attempts,
              job.maxAttempts,
              job.lockedBy,
              s"no handler for ${job.actionType}",
              None
            )
            .void
        case Some(handler) =>
          handler
            .run(job)
            .onError(e =>
              log.warn(e)("handler threw — treated as retryable failure") *>
                repo
                  .failOrRetry(
                    job.id,
                    job.attempts,
                    job.maxAttempts,
                    job.lockedBy,
                    s"exception: ${e.getMessage}",
                    None
                  )
                  .void
            )
            .flatMap {
              case JobQueue.Done =>
                repo.complete(job.id, job.attempts, job.lockedBy)
              case JobQueue.Fail(reason) =>
                repo
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
                repo
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
            .onError(e =>
              log.error(e)(
                "outcome recording failed — lease will expire and retry"
              )
            )

    Stream.eval(
      log.info(
        s"SendWorker ${cfg.workerId} starting (batch ${cfg.batchSize}, lease ${cfg.leaseTimeout})"
      )
    ) *>
      (Stream.eval(
        repo
          .requeueExpiredLeases(cfg.leaseTimeout)
          .flatMap(n =>
            IO.whenA(n > 0)(log.info(s"requeued $n lease-expired job(s)"))
          )
      ) ++ Stream.awakeEvery[IO](cfg.pollInterval)).evalMap { _ =>
        repo
          .claimBatch(cfg.workerId, cfg.batchSize)
          .flatMap { jobs =>
            if jobs.isEmpty then IO.unit
            else
              log.info(s"claimed ${jobs.size} job(s)") *> jobs.parTraverse_(
                dispatch
              )
          }
          .handleErrorWith(e =>
            log.error(e)("tick failed — will retry next tick")
          )
      }
