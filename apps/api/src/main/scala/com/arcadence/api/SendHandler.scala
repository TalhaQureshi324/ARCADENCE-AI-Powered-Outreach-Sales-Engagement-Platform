package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ProviderRegistry
import com.arcadence.core.email.{OutboundMessage, ProviderError, ReplyMessage}
import com.arcadence.core.jobs.JobQueue
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json

import java.time.ZoneId
import java.time.ZonedDateTime
import scala.concurrent.duration.*
import java.util.UUID

/** The send handler (Phase 5.3): executes one claimed `send_email` action —
  * final eligibility checks, personalization rendering, MIME submission through
  * the Phase 2 provider adapters (thread-aware when thread mode is on),
  * evidence persistence, and outcome classification.
  *
  * Delivery honesty: an ambiguous submission (network timeout after the
  * provider may have accepted) is recorded as `outcome_unknown` and the action
  * is HELD for reconciliation — never retried blind. A previously accepted send
  * for the same action (crash after acceptance) completes the action without
  * resending.
  *
  * Enrollment advancement: after an accepted send the enrollment moves to the
  * graph successor; wait steps set the next due time via the campaign's
  * schedule; a terminal step completes the enrollment. Replies/unsubscribes/
  * bounces are re-checked immediately before submission.
  */
final class SendHandler(
    xa: Transactor[IO],
    registry: Option[ProviderRegistry],
    emailAccounts: EmailAccountRepos,
    guard: AccountGuard,
    events: Events
) extends com.arcadence.core.jobs.JobQueue.Handler:

  def handles(actionType: String): Boolean = actionType == "send_email"

  def run(job: JobQueue.Claimed): IO[JobQueue.Outcome] =
    val payload = job.payload
    val enrollmentId = payload.hcursor.get[UUID]("enrollmentId").toOption
    val stepId = payload.hcursor.get[UUID]("stepId").toOption
    val accountId = payload.hcursor.get[UUID]("emailAccountId").toOption
    (enrollmentId, stepId, accountId) match
      case (Some(enrId), Some(stepId), Some(acctId)) =>
        context(enrId, stepId, acctId).flatMap {
          case None =>
            skip(job, "skipped", "enrollment, step or mailbox no longer exists")
              .as(JobQueue.Done)
          case Some(ctx)
              if !guard.allows(job.emailAccountId.getOrElse(ctx.actionId)) =>
            // Circuit open for this account (5.4): defer the job.
            IO(JobQueue.Retry("circuit open for sending account", 5.minutes))
          case Some(ctx) =>
            preSendGuards(ctx).flatMap {
              case Left(reason) =>
                skip(job, reason._1, reason._2).as(JobQueue.Done)
              case Right(()) =>
                priorEvidence(ctx.actionId).flatMap {
                  case Some(_) =>
                    // Crash AFTER acceptance: do not resend; complete.
                    events.record(
                      job.workspaceId,
                      "send",
                      ctx.actionId.toString,
                      "reconciled",
                      Json.obj(
                        "reason" -> Json.fromString(
                          "prior acceptance found — not resent"
                        )
                      )
                    ) *>
                      IO.pure(JobQueue.Done)
                  case None =>
                    ctx.threadContext match
                      case Some((threadId, inReplyTo)) if ctx.threadMode =>
                        submit(
                          ctx,
                          job,
                          reply = Some(
                            ReplyMessage(
                              threadId,
                              inReplyTo,
                              "",
                              List.empty,
                              "",
                              "",
                              None
                            )
                          )
                        )
                      case _ =>
                        submit(ctx, job, reply = None)
                }
            }
        }
      case _ =>
        IO.pure(JobQueue.Fail("send_email payload missing ids"))

  private def skip(
      job: JobQueue.Claimed,
      code: String,
      reason: String
  ): IO[Unit] =
    events.record(
      job.workspaceId,
      "send",
      job.id.toString,
      code,
      Json.obj("reason" -> Json.fromString(reason))
    )

  // -- context ----------------------------------------------------------------

  private final case class Ctx(
      actionId: UUID,
      workspaceId: UUID,
      campaignId: UUID,
      campaignStatus: String,
      threadMode: Boolean,
      enrollmentId: UUID,
      enrollmentStatus: String,
      contactId: UUID,
      contactEmail: String,
      contactState: String,
      suppressed: Boolean,
      accountEmail: String,
      accountProvider: String,
      signature: String,
      subject: String,
      preheader: String,
      bodyHtml: String,
      bodyText: String,
      variantKey: String,
      stepId: UUID,
      threadContext: Option[(String, String)]
  )

  private def context(
      enrId: UUID,
      stepId: UUID,
      acctId: UUID
  ): IO[Option[Ctx]] =
    sql"""
      select sa.id, sa.workspace_id, cm.id, cm.status::text, cm.thread_mode,
             e.status::text, ct.id, ct.email, ct.state::text,
             ea.email_address, ea.provider::text,
             v.subject, v.preheader, v.body_html, v.body_text, v.signature, v.variant_key,
             (select p.provider_thread_id from public.outbound_message p
              where p.enrollment_id = $enrId and p.provider_thread_id is not null
              order by p.created_at desc limit 1),
             (select p.internet_message_id from public.outbound_message p
              where p.enrollment_id = $enrId and p.internet_message_id is not null
              order by p.created_at desc limit 1),
             (select count(*) from public.suppression sp
              where sp.workspace_id = sa.workspace_id and sp.email = ct.email)
      from public.scheduled_action sa
      join public.campaign_enrollment e on e.id = $enrId
      join public.campaign cm on cm.id = e.campaign_id
      join public.contact ct on ct.id = e.contact_id
      join public.email_accounts ea on ea.id = $acctId
      join public.campaign_step s on s.id = $stepId
      join public.step_variant v on v.step_id = s.id and v.variant_key =
        coalesce((select v2.variant_key from public.step_variant v2 where v2.step_id = s.id order by v2.variant_key limit 1), 'A')
      where sa.enrollment_id = $enrId and sa.id = (
        select id from public.scheduled_action where enrollment_id = $enrId
          and payload->>'stepId' = ${stepId.toString} order by created_at desc limit 1
      )
    """
      .query[
        (
            UUID,
            UUID,
            UUID,
            String,
            Boolean,
            String,
            UUID,
            String,
            String,
            String,
            String,
            String,
            String,
            String,
            String,
            String,
            String,
            Option[String],
            Option[String],
            Long
        )
      ]
      .option
      .map(_.map { t =>
        Ctx(
          actionId = t._1,
          workspaceId = t._2,
          campaignId = t._3,
          campaignStatus = t._4,
          threadMode = t._5,
          enrollmentStatus = t._6,
          contactId = t._7,
          contactEmail = t._8,
          contactState = t._9,
          accountEmail = t._10,
          accountProvider = t._11,
          subject = t._12,
          preheader = t._13,
          bodyHtml = t._14,
          bodyText = t._15,
          signature = t._16,
          variantKey = t._17,
          suppressed = t._20 > 0,
          threadContext = t._18.flatMap(th => t._19.map(inr => (th, inr))),
          enrollmentId = enrId,
          stepId = stepId
        )
      })
      .transact(xa)

  private def priorEvidence(actionId: UUID): IO[Option[UUID]] =
    sql"""
      select id from public.outbound_message
      where action_id = $actionId and status in ('sent', 'delivered')
      limit 1
    """.query[UUID].option.transact(xa)

  // -- guards -------------------------------------------------------------------

  private def preSendGuards(ctx: Ctx): IO[Either[(String, String), Unit]] =
    if ctx.campaignStatus != "active" then
      IO.pure(Left("skipped" -> s"campaign is ${ctx.campaignStatus}"))
    else if ctx.enrollmentStatus != "active" then
      IO.pure(Left("skipped" -> s"enrollment is ${ctx.enrollmentStatus}"))
    else if ctx.contactState != "active" then
      IO.pure(
        Left(
          "skipped" -> s"recipient is ${ctx.contactState} (opt-out/bounce/complaint)"
        )
      )
    else if ctx.suppressed then
      IO.pure(Left("skipped" -> "recipient is suppressed"))
    else IO.pure(Right(()))

  // -- submission ----------------------------------------------------------------

  private def submit(
      ctx: Ctx,
      job: JobQueue.Claimed,
      reply: Option[ReplyMessage]
  ): IO[JobQueue.Outcome] =
    val resolver = Personalization.resolverFor(
      email = ctx.contactEmail,
      firstName = None,
      lastName = None,
      company = None,
      jobTitle = None,
      region = None,
      timezone = None,
      website = None,
      phone = None,
      customFields = Json.obj()
    )
    val subjectR = Personalization.render(ctx.subject, resolver)
    val htmlR = Personalization.render(ctx.bodyHtml, resolver)
    val sanitized = Personalization.sanitizeHtml(htmlR.text)
    val html = if ctx.signature.trim.isEmpty then sanitized
    else
      sanitized + Personalization.sanitizeHtml("<br/>--<br/>" + ctx.signature)
    val text = if ctx.bodyText.trim.nonEmpty then
      Personalization.render(ctx.bodyText, resolver).text
    else Personalization.htmlToText(htmlR.text)
    val messageIdHeader =
      s"<${UUID.randomUUID()}@${ctx.accountEmail.split('@').lift(1).getOrElse("arcadence.local")}>"
    val baseHeaders = Map(
      "Message-ID" -> messageIdHeader
    ) ++ (reply.map(r => "In-Reply-To" -> r.inReplyToMessageId) ++ reply.map(
      r => "References" -> r.inReplyToMessageId
    ))

    val insertMsg =
      sql"""
        insert into public.outbound_message
          (workspace_id, action_id, enrollment_id, contact_id, campaign_id, campaign_step_id,
           variant_key, email_account_id, subject, body_text, body_html, headers, status)
        values (${ctx.workspaceId}, ${ctx.actionId}, ${ctx.enrollmentId}, ${ctx.contactId},
                ${ctx.campaignId}, ${ctx.stepId}, ${ctx.variantKey}, null,
                ${subjectR.text}, $text, $html, ${io.circe.Json
          .obj(
            "Message-ID" -> Json.fromString(messageIdHeader)
          )
          .noSpaces}::jsonb, 'sending')
        returning id
      """.query[UUID].unique.transact(xa)

    insertMsg
      .map(msgId => Right(msgId): Either[Unit, UUID])
      .handleError {
        case e: java.sql.SQLException
            if e.getSQLState != null && e.getSQLState.startsWith("23") =>
          // Unique action_id violated: a prior attempt already recorded this
          // send (crash after acceptance). Reconcile — never resend.
          Left(())
        case other => throw other
      }
      .flatMap {
        case Left(_) =>
          events
            .record(
              ctx.workspaceId,
              "send",
              ctx.actionId.toString,
              "reconciled",
              Json.obj(
                "reason" -> Json
                  .fromString("prior acceptance found — not resent")
              )
            )
            .as(JobQueue.Done)
        case Right(msgId) =>
          sql"""
            update public.outbound_message set email_account_id =
              (select email_account_id from public.scheduled_action where id = ${ctx.actionId})
            where id = $msgId
          """.update.run.void.transact(xa) *>
            emailAccounts
              .findAccount(
                ctx.workspaceId,
                job.emailAccountId.getOrElse(UUID.randomUUID())
              )
              .flatMap { accountRow =>
                (
                  accountRow,
                  registry.flatMap(_.adapter(ctx.accountProvider))
                ) match
                  case (Some(account), Some(adapter)) =>
                    val load = registry.get.credentialLoader(
                      emailAccounts,
                      ctx.workspaceId,
                      account.id,
                      account.provider
                    )
                    val submission = reply match
                      case Some(r) =>
                        adapter.submitReply(
                          load,
                          r.copy(
                            from = ctx.accountEmail,
                            to = List(ctx.contactEmail),
                            subject = subjectR.text,
                            textBody = text,
                            htmlBody = Some(html)
                          )
                        )
                      case None =>
                        adapter.submit(
                          load,
                          OutboundMessage(
                            from = ctx.accountEmail,
                            to = List(ctx.contactEmail),
                            subject = subjectR.text,
                            textBody = text,
                            htmlBody = Some(html),
                            headers = baseHeaders
                          )
                        )
                    submission.flatMap {
                      case Right(providerId) =>
                        accepted(
                          ctx,
                          msgId,
                          providerId.value,
                          messageIdHeader,
                          account.id
                        )
                      case Left(err) =>
                        val (status, _, retryAfter) = classify(err)
                        recordSend(
                          ctx.workspaceId,
                          ctx.actionId,
                          Some(account.id),
                          ctx.accountProvider,
                          status,
                          None,
                          err.detail
                        ) *>
                          events.record(
                            ctx.workspaceId,
                            "send",
                            ctx.actionId.toString,
                            status,
                            Json.obj("reason" -> Json.fromString(err.detail))
                          ) *>
                          (status match
                            case "outcome_unknown" =>
                              // Network ambiguity: hold for reconciliation — never retry blind.
                              IO(
                                JobQueue.Fail(
                                  "outcome_unknown — held for reconciliation"
                                )
                              )
                            case "authentication_failed" =>
                              guard.recordAuthFailure(
                                ctx.workspaceId,
                                account.id
                              ) *>
                                IO(
                                  JobQueue.Retry(
                                    err.detail,
                                    retryAfter.getOrElse(10.minutes)
                                  )
                                )
                            case "rate_limited" =>
                              IO(
                                JobQueue.Retry(
                                  err.detail,
                                  retryAfter.getOrElse(2.minutes)
                                )
                              )
                            case "rejected" | "permanently_failed" =>
                              IO(JobQueue.Fail(err.detail))
                            case _ =>
                              IO(
                                JobQueue.Retry(
                                  err.detail,
                                  retryAfter.getOrElse(5.minutes)
                                )
                              ))
                    }
                  case _ =>
                    recordSend(
                      ctx.workspaceId,
                      ctx.actionId,
                      None,
                      ctx.accountProvider,
                      "rejected",
                      None,
                      "provider adapter unavailable"
                    ) *>
                      IO.pure(JobQueue.Fail("provider adapter unavailable"))
              }
      }
  private def accepted(
      ctx: Ctx,
      outboxId: UUID,
      providerMessageId: String,
      internetMessageId: String,
      accountId: UUID
  ): IO[JobQueue.Outcome] =
    val persist =
      (for
        _ <-
          sql"""
          update public.outbound_message
          set status = 'sent', provider = ${ctx.accountProvider},
              provider_message_id = $providerMessageId, internet_message_id = $internetMessageId,
              email_account_id = $accountId
          where id = $outboxId
        """.update.run
        _ <-
          sql"""
          update public.campaign_enrollment
          set last_account_id = $accountId, current_step = current_step + 1
          where id = ${ctx.enrollmentId}
        """.update.run
      yield ()).transact(xa)
    persist *> nextStep(ctx.stepId, ctx.campaignId).flatMap { next =>
      val advance = next match
        case Some(n) =>
          sql"""
          update public.campaign_enrollment
          set next_step_id = ${n._1}, next_action_at = ${n._2.toOffsetDateTime}
          where id = ${ctx.enrollmentId}
        """.update.run.void.transact(xa)
        case None =>
          sql"""
          update public.campaign_enrollment
          set status = 'completed', finished_at = now(), next_step_id = null
          where id = ${ctx.enrollmentId}
        """.update.run.void.transact(xa)
      advance
    } *> recordSend(
      ctx.workspaceId,
      ctx.actionId,
      Some(accountId),
      ctx.accountProvider,
      "accepted",
      Some(providerMessageId),
      ""
    ) *>
      events.record(
        ctx.workspaceId,
        "send",
        ctx.actionId.toString,
        "accepted",
        Json.obj("providerMessageId" -> Json.fromString(providerMessageId))
      ) *>
      IO.pure(JobQueue.Done)

  /** Successor step with its send time: wait steps add days (business-day
    * aware); everything else is due at the next window opening.
    */
  private def nextStep(
      stepId: UUID,
      campaignId: UUID
  ): IO[Option[(UUID, ZonedDateTime)]] =
    (for
      camp <-
        sql"select timezone, send_window_start, send_window_end, business_days_only from public.campaign where id = $campaignId"
          .query[(String, java.time.LocalTime, java.time.LocalTime, Boolean)]
          .unique
      step <-
        sql"select client_key, position from public.campaign_step where id = $stepId"
          .query[(Option[String], Int)]
          .option
      edges <-
        sql"""select e.to_step_id, e.branch_key, s.type::text, s.wait_days, s.business_days
              from public.campaign_edge e
              join public.campaign_step s on s.id = e.to_step_id
              where e.from_step_id = $stepId"""
          .query[(UUID, Option[String], String, Option[Int], Boolean)]
          .to[List]
      keys <-
        sql"select id, client_key from public.campaign_step where campaign_id = $campaignId"
          .query[(UUID, Option[String])]
          .to[List]
    yield (camp, step, edges, keys)).transact(xa).flatMap {
      case (camp, stepOpt, edges, keys) =>
        val keyById = keys.collect { case (id, Some(k)) => id -> k }.toMap
        val stepKey = stepOpt.flatMap(_._1)
        val succ0 = stepKey.flatMap(k =>
          edges
            .find(e => keyById.get(e._1).contains(k) && e._2.isEmpty)
            .orElse(edges.find(e => keyById.get(e._1).contains(k)))
        )
        succ0 match
          case Some((nextId, _, nextType, waitDays, businessDays)) =>
            val zone =
              scala.util.Try(ZoneId.of(camp._1)).getOrElse(ZoneId.of("Etc/UTC"))
            val now = java.time.ZonedDateTime.now(zone)
            val afterWait = nextType match
              case "wait" =>
                Schedule.addWait(
                  now,
                  waitDays.getOrElse(0),
                  businessDays || camp._4
                )
              case _ => now
            val due =
              Schedule.nextSendMoment(afterWait, camp._2, camp._3, camp._4)
            IO.pure(Some((nextId, due)))
          case None =>
            // Linear fallback (no edges / no client keys): next step by position.
            sql"""
              select id from public.campaign_step
              where campaign_id = $campaignId and position >
                (select position from public.campaign_step where id = $stepId)
              order by position limit 1
            """.query[UUID].option.transact(xa).flatMap {
              case None         => IO.pure(None)
              case Some(nextId) =>
                sql"select type::text, wait_days, business_days from public.campaign_step where id = $nextId"
                  .query[(String, Option[Int], Boolean)]
                  .unique
                  .transact(xa)
                  .map { case (nextType, waitDays, businessDays) =>
                    val zone = scala.util
                      .Try(ZoneId.of(camp._1))
                      .getOrElse(ZoneId.of("Etc/UTC"))
                    val now = java.time.ZonedDateTime.now(zone)
                    val afterWait = nextType match
                      case "wait" =>
                        Schedule.addWait(
                          now,
                          waitDays.getOrElse(0),
                          businessDays || camp._4
                        )
                      case _ => now
                    val due = Schedule.nextSendMoment(
                      afterWait,
                      camp._2,
                      camp._3,
                      camp._4
                    )
                    Some((nextId, due))
                  }
            }
    }

  private def recordSend(
      workspaceId: UUID,
      actionId: UUID,
      accountId: Option[UUID],
      provider: String,
      status: String,
      providerMessageId: Option[String],
      detail: String
  ): IO[Unit] =
    sql"""
      insert into public.send_attempt
        (workspace_id, action_id, email_account_id, provider, provider_message_id,
         status, provider_status_code, detail)
      values ($workspaceId, $actionId, $accountId, $provider, $providerMessageId,
              $status, null, ${Json
        .obj("reason" -> Json.fromString(detail))
        .noSpaces}::jsonb)
    """.update.run.void.transact(xa)

  /** Outcome classification per the API contract: provider errors map to
    * accepted/rejected/rate_limited/authentication_failed/permanently_failed/
    * outcome_unknown. Unreachable/timeout errors are outcome_unknown because
    * the submission may already have been delivered.
    */
  private def classify(
      err: ProviderError
  ): (String, Boolean, Option[scala.concurrent.duration.FiniteDuration]) =
    err match
      case ProviderError.RateLimited(retryAfterSeconds) =>
        (
          "rate_limited",
          true,
          retryAfterSeconds.map(s =>
            scala.concurrent.duration.Duration(s.toLong, "second")
          )
        )
      case ProviderError.Throttled(detail) =>
        ("rate_limited", true, None)
      case ProviderError.InvalidCredentials(_) |
          ProviderError.PermissionDenied(_) =>
        ("authentication_failed", true, None)
      case ProviderError.Unreachable(_) =>
        ("outcome_unknown", false, None)
      case ProviderError.Unexpected(detail) =>
        if detail.toLowerCase.contains("timeout") then
          ("outcome_unknown", false, None)
        else ("permanently_failed", false, None)
      case ProviderError.NotSupported(_) =>
        ("rejected", false, None)
