package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.core.email.InboxMessage
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json

import java.util.UUID

/** Deterministic reply intelligence (Phase 6.2). Classification is ordered rule
  * matching on subject + body; bounce detection routes to suppression;
  * unsubscribe suppresses + unsubscribes; valid replies mark the enrollment
  * replied and CANCEL pending follow-ups in the database — stopping scheduled
  * emails never depends on frontend state.
  */
object ReplyClassifier:

  val Values: List[String] = List(
    "unknown",
    "interested",
    "not_interested",
    "question",
    "meeting_requested",
    "unsubscribe",
    "out_of_office",
    "automated",
    "bounce"
  )

  private def containsAny(text: String, needles: String*): Boolean =
    val t = text.toLowerCase
    needles.exists(t.contains)

  /** Ordered classification: bounces and unsubscribe win over sentiment;
    * automated/ooo before sentiment; question before interested.
    */
  def classify(subject: String, body: String, fromAddress: String): String =
    val text = s"$subject\n$body"
    val from = fromAddress.toLowerCase
    if from.contains("mailer-daemon") || from.contains("postmaster") ||
      containsAny(
        subject,
        "undeliverable",
        "delivery status notification",
        "delivery failure"
      )
    then "bounce"
    else if containsAny(
        subject,
        "unsubscribe",
        "remove me",
        "opt out",
        "opt-out"
      ) ||
      containsAny(
        body,
        "unsubscribe me",
        "remove me from",
        "take me off your list",
        "opt out"
      )
    then "unsubscribe"
    else if containsAny(
        subject,
        "out of office",
        "automatic reply",
        "auto-reply",
        "away from the office"
      ) ||
      containsAny(
        body,
        "out of office",
        "automatic reply",
        "on vacation",
        "annual leave"
      )
    then "out_of_office"
    else if containsAny(
        subject,
        "delivery status",
        "returned mail",
        "auto-generated"
      ) ||
      containsAny(body, "this is an automated message", "do not reply")
    then "automated"
    else if containsAny(
        text,
        "not interested",
        "pass on this",
        "no thank you",
        "no thanks"
      )
    then "not_interested"
    else if containsAny(
        text,
        "meeting",
        "calendar",
        "schedule a call",
        "book a time",
        "zoom",
        "teams link"
      )
    then "meeting_requested"
    else if body.contains("?") || containsAny(
        text,
        "question",
        "how much",
        "pricing",
        "curious about"
      )
    then "question"
    else if containsAny(
        text,
        "interested",
        "sounds good",
        "let's talk",
        "happy to chat"
      )
    then "interested"
    else "unknown"

  private val nothing: ConnectionIO[Unit] = ().pure[ConnectionIO]

  /** Applies side effects of an inbound message to its thread's linked
    * enrollment/contact. Idempotent: called only from the refs-gated sync path.
    */
  def applyInboundEffects(
      xa: Transactor[IO],
      threadId: UUID,
      m: InboxMessage
  ): IO[Unit] =
    applyInboundEffectsT(threadId, m).transact(xa)

  /** Transaction-side variant: runs inside the sync transaction. */
  def applyInboundEffectsT(
      threadId: UUID,
      m: InboxMessage
  ): ConnectionIO[Unit] =
    val classification =
      classify(m.subject.getOrElse(""), m.textBody.getOrElse(""), m.fromAddress)

    val setClass: ConnectionIO[Unit] =
      sql"update public.email_threads set classification = $classification where id = $threadId".update.run.void

    val linkQuery: ConnectionIO[Option[(Option[UUID], Option[UUID])]] =
      sql"select contact_id, enrollment_id from public.email_threads where id = $threadId"
        .query[(Option[UUID], Option[UUID])]
        .option

    val enrollmentEffects: Option[UUID] => ConnectionIO[Unit] = {
      case Some(enr) =>
        sql"update public.campaign_enrollment set status = 'replied' where id = $enr and status = 'active'".update.run.void *>
          (if classification == "bounce" || classification == "unsubscribe" then
             sql"update public.campaign_enrollment set finished_at = now() where id = $enr".update.run.void
           else nothing)
      case None => nothing
    }

    val contactEffects: Option[UUID] => ConnectionIO[Unit] = {
      case Some(cid) if classification == "unsubscribe" =>
        sql"update public.contact set state = 'unsubscribed' where id = $cid and state = 'active'".update.run.void *>
          sql"""
            insert into public.suppression (workspace_id, email, contact_id, reason, source)
            select workspace_id, email, id, 'unsubscribe', 'automatic' from public.contact where id = $cid
            on conflict do nothing
          """.update.run.void
      case Some(cid) if classification == "bounce" =>
        sql"update public.contact set state = 'bounced' where id = $cid and state = 'active'".update.run.void *>
          sql"""
            insert into public.suppression (workspace_id, email, contact_id, reason, source)
            select workspace_id, email, id, 'bounce', 'automatic' from public.contact where id = $cid
            on conflict do nothing
          """.update.run.void
      case _ => nothing
    }

    val cancelPending: Option[UUID] => ConnectionIO[Unit] = {
      case Some(enr)
          if classification != "out_of_office" && classification != "automated" =>
        sql"""
          update public.scheduled_action
          set status = 'cancelled', last_error = ${s"cancelled: reply classified $classification"}
          where enrollment_id = $enr and status = 'pending'
        """.update.run.void
      case _ => nothing
    }

    val effects: ConnectionIO[Unit] = linkQuery.flatMap {
      case Some((contactId, enrollmentId)) =>
        setClass *> enrollmentEffects(enrollmentId) *> contactEffects(
          contactId
        ) *> cancelPending(enrollmentId)
      case None => nothing
    }

    effects
