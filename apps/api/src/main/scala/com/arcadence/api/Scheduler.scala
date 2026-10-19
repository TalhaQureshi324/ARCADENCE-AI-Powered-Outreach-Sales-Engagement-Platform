package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.core.jobs.JobQueue
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.{LocalDate, ZoneId, ZonedDateTime}
import java.util.UUID

/** Campaign scheduler (Phase 5.2): finds eligible enrollments whose next step
  * is due and enqueues one send action each — atomically reserving mailbox
  * quota so no two workers can both claim the same remaining capacity.
  *
  * Eligibility (all enforced in the claiming SQL): campaign active, enrollment
  * active, recipient not suppressed and in `active` contact state (opt-outs,
  * bounces and complaints are contact states), mailbox connected, under daily
  * quota (account cap and workspace campaign cap), and inside the campaign's
  * send window in its timezone (weekend rules via `business_days_only`).
  * Outside the window, the due time is deferred to the next window opening
  * instead of sending.
  *
  * Fairness: each workspace contributes at most `perWorkspace` due actions per
  * tick (row_number partition), so one large customer cannot starve the rest.
  * Conversation consistency: the enrollment's last account is preferred when it
  * still has capacity; allocation otherwise picks the least-used healthy
  * mailbox.
  */
object Scheduler:

  final case class DueEnrollment(
      enrollmentId: UUID,
      workspaceId: UUID,
      campaignId: UUID,
      contactEmail: String,
      nextStepId: UUID,
      lastAccountId: Option[UUID],
      timezone: String,
      windowStart: java.time.LocalTime,
      windowEnd: java.time.LocalTime,
      businessDaysOnly: Boolean
  )

  /** Enrolls due actions. Returns the number enqueued. */
  def enqueueDue(
      repos: CampaignRepos,
      jobQueue: JobQueue.Repo,
      leadsRepos: LeadsRepos,
      perWorkspace: Int,
      batch: Int
  ): IO[Int] =
    dueEnrollments(repos.transactor, perWorkspace, batch).flatMap {
      case Nil  => IO.pure(0)
      case dues =>
        dues
          .traverse { due =>
            val zone = scala.util
              .Try(java.time.ZoneId.of(due.timezone))
              .getOrElse(java.time.ZoneId.of("Etc/UTC"))
            val nowZ = java.time.ZonedDateTime.now(zone)
            val inWindow = Schedule.nextSendMoment(
              nowZ,
              due.windowStart,
              due.windowEnd,
              due.businessDaysOnly
            ) == nowZ
            if !inWindow then
              // Eligible but outside the campaign's sending window: defer to the
              // next window opening instead of queueing a premature send.
              val nextOpen = Schedule.nextSendMoment(
                nowZ,
                due.windowStart,
                due.windowEnd,
                due.businessDaysOnly
              )
              deferTo(repos.transactor, due.enrollmentId, nextOpen).as(0)
            else
              for
                accountIdOpt <- allocate(
                  repos.transactor,
                  due.workspaceId,
                  due.campaignId,
                  due.lastAccountId
                )
                enqueued <- accountIdOpt match
                  case None            => IO.pure(false)
                  case Some(accountId) =>
                    val today = LocalDate.now()
                    jobQueue
                      .enqueue(
                        JobQueue.EnqueueInput(
                          workspaceId = due.workspaceId,
                          campaignId = Some(due.campaignId),
                          enrollmentId = Some(due.enrollmentId),
                          contactId = None,
                          emailAccountId = Some(accountId),
                          actionType = "send_email",
                          idempotencyKey =
                            s"send:${due.enrollmentId}:${due.nextStepId}:$today",
                          payload = JsonPayloads
                            .send(due.enrollmentId, due.nextStepId, accountId),
                          runAfter = OffsetDateTimeNow(),
                          maxAttempts = 6
                        )
                      )
                      .flatTap(enq =>
                        IO.whenA(enq)(
                          markScheduled(
                            repos.transactor,
                            due.enrollmentId,
                            accountId
                          )
                        )
                      )
              yield if enqueued then 1 else 0
          }
          .map(_.sum)
    }

  private def OffsetDateTimeNow(): java.time.OffsetDateTime =
    java.time.OffsetDateTime.now()

  /** Due enrollments with window/timezone awareness and per-workspace fairness.
    */
  private def dueEnrollments(
      xa: Transactor[IO],
      perWorkspace: Int,
      batch: Int
  ): IO[List[DueEnrollment]] =
    sql"""
      select id, workspace_id, campaign_id, email, next_step_id, last_account_id,
             timezone, window_start, window_end, business_days_only from (
        select e.id, e.workspace_id, e.campaign_id, c.email, e.next_step_id, e.last_account_id,
               cm.timezone, cm.send_window_start window_start, cm.send_window_end window_end,
               cm.business_days_only,
               row_number() over (partition by e.workspace_id order by e.next_action_at, e.id) rn
        from public.campaign_enrollment e
        join public.campaign cm on cm.id = e.campaign_id and cm.status = 'active'
        join public.contact c on c.id = e.contact_id and c.state = 'active'
        left join public.suppression sp on sp.workspace_id = e.workspace_id and sp.email = c.email
        where e.status = 'active' and e.next_action_at <= now() and e.next_step_id is not null
          and sp.id is null
      ) t
      where rn <= $perWorkspace
      order by rn
      limit $batch
    """.query[DueEnrollment].to[List].transact(xa)

  /** Healthy-mailbox allocation with an atomic quota reservation. Returns None
    * when no assigned mailbox is connected with remaining capacity.
    */
  def allocate(
      xa: Transactor[IO],
      workspaceId: UUID,
      campaignId: UUID,
      prefer: Option[UUID]
  ): IO[Option[UUID]] =
    candidates(xa, campaignId).flatMap { accounts =>
      val accountIds = accounts.map(_._1)
      val ordered =
        (prefer.toList.filter(accountIds.contains) ++ accountIds.filterNot(id =>
          prefer.contains(id)
        )).distinct
      ordered.foldLeft(IO.pure(Option.empty[UUID])) { (acc, id) =>
        acc.flatMap {
          case Some(found) => IO.pure(Some(found))
          case None        =>
            reserve(xa, workspaceId, id).map(b => if b then Some(id) else None)
        }
      }
    }

  private def candidates(
      xa: Transactor[IO],
      campaignId: UUID
  ): IO[List[(UUID, String)]] =
    sql"""
      select ea.id, ea.status
      from public.campaign_mailbox m
      join public.email_accounts ea on ea.id = m.email_account_id
      where m.campaign_id = $campaignId and ea.status = 'connected'
      order by (select coalesce(sum(reserved), 0) from public.quota_reservation q
                where q.email_account_id = ea.id and q.day = current_date)
    """.query[(UUID, String)].to[List].transact(xa)

  /** Atomic reservation: the conditional UPDATE row-locks the counter, so two
    * concurrent workers can never both consume the same remaining capacity.
    */
  def reserve(
      xa: Transactor[IO],
      workspaceId: UUID,
      accountId: UUID
  ): IO[Boolean] =
    sql"""
      insert into public.quota_reservation (workspace_id, email_account_id, day, reserved)
      values ($workspaceId, $accountId, current_date, 1)
      on conflict (email_account_id, day) do update
        set reserved = quota_reservation.reserved + 1,
            updated_at = now()
      where quota_reservation.reserved < coalesce((
        select daily_send_cap from public.email_accounts where id = $accountId
      ), 500)
      returning reserved
    """.query[Int].option.map(_.isDefined).transact(xa)

  private def deferTo(
      xa: Transactor[IO],
      enrollmentId: UUID,
      at: ZonedDateTime
  ): IO[Unit] =
    sql"""update public.campaign_enrollment set next_action_at = ${at.toOffsetDateTime} where id = $enrollmentId""".update.run.void
      .transact(xa)

  private def markScheduled(
      xa: Transactor[IO],
      enrollmentId: UUID,
      accountId: UUID
  ): IO[Unit] =
    sql"""
      update public.campaign_enrollment
      set next_action_at = now() + interval '1 minute', last_account_id = $accountId
      where id = $enrollmentId
    """.update.run.void.transact(xa)

object JsonPayloads:
  def send(enrollmentId: UUID, stepId: UUID, accountId: UUID): io.circe.Json =
    io.circe.Json.obj(
      "enrollmentId" -> io.circe.Json.fromString(enrollmentId.toString),
      "stepId" -> io.circe.Json.fromString(stepId.toString),
      "emailAccountId" -> io.circe.Json.fromString(accountId.toString)
    )
