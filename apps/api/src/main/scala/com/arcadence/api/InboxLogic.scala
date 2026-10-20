package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.ProviderRegistry
import com.arcadence.core.email.{EmailProvider, OutboundMessage, ReplyMessage}
import com.arcadence.api.Permissions.Permission
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import sttp.model.StatusCode as SttpStatus

import java.time.OffsetDateTime
import java.util.UUID

/** Logic bodies for InboxRoutes (kept here to keep the endpoint file
  * declarative). All queries scope by workspace_id — cross-workspace access
  * resolves to NotFound like every other tenant resource.
  */
object InboxLogic:

  private case class ThreadRow(
      id: UUID,
      emailAccountId: UUID,
      contactEmail: Option[String],
      contactName: Option[String],
      campaignName: Option[String],
      subject: String,
      status: String,
      classification: String,
      unread: Boolean,
      assignedTo: Option[UUID],
      assignedToName: Option[String],
      lastMessageAt: OffsetDateTime
  )

  private val selectBase = fr"""
    select t.id, t.email_account_id, c.email, c.first_name, cm.name,
           t.subject, t.status, t.classification, t.unread,
           t.assigned_to, u.display_name, t.last_message_at
    from public.email_threads t
    left join public.contact c on c.id = t.contact_id
    left join public.campaign cm on cm.id = t.campaign_id
    left join public.profile u on u.id = t.assigned_to
  """

  private def toDto(r: ThreadRow): InboxDtos.ThreadDto =
    InboxDtos.ThreadDto(
      r.id,
      r.emailAccountId,
      r.contactEmail,
      r.contactName,
      r.campaignName,
      r.subject,
      r.status,
      r.classification,
      r.unread,
      r.assignedTo,
      r.assignedToName,
      r.lastMessageAt
    )

  def list(
      xa: Transactor[IO],
      workspaceId: UUID,
      classification: Option[String],
      status: Option[String],
      unread: Option[Boolean],
      assignedTo: Option[UUID],
      q: Option[String]
  ): IO[InboxDtos.ThreadPage] =
    val frags = List.newBuilder[Fragment]
    frags += fr"t.workspace_id = $workspaceId"
    classification.foreach(v => frags += fr"t.classification = ${v}::text")
    status.foreach(v => frags += fr"t.status = ${v}::text")
    unread.foreach(v => frags += fr"t.unread = $v")
    assignedTo.foreach(v => frags += fr"t.assigned_to = $v")
    q.foreach { v =>
      val like = s"%${v.toLowerCase}%"
      frags += fr"(lower(t.subject) like $like or lower(c.email) like $like)"
    }
    val where = frags.result().reduce((a, b) => a ++ fr" and " ++ b)
    (selectBase ++ fr"where" ++ where ++ fr"order by t.last_message_at desc limit 50")
      .query[ThreadRow]
      .to[List]
      .transact(xa)
      .map(rows => InboxDtos.ThreadPage(rows.map(toDto), None))

  def detail(
      xa: Transactor[IO],
      workspaceId: UUID,
      threadId: UUID
  ): IO[Option[InboxDtos.ThreadDetail]] =
    (selectBase ++ fr"where t.workspace_id = $workspaceId and t.id = $threadId")
      .query[ThreadRow]
      .option
      .transact(xa)
      .flatMap {
        case None    => IO.pure(None)
        case Some(r) =>
          val messages: IO[List[InboxDtos.MessageDto]] =
            (fr"""select direction, from_address, subject, body_text, body_html, occurred_at from (
                    select 'inbound' direction, m.from_address, m.subject, m.body_text, m.body_html, m.occurred_at
                    from public.email_messages m where m.thread_id = $threadId
                  union all
                    select 'outbound' direction, ${r.contactEmail.getOrElse(
                ""
              )}, om.subject, om.body_text, om.body_html, om.created_at
                    from public.outbound_message om where om.thread_id = $threadId
                  ) x order by occurred_at""")
              .query[(String, String, String, String, String, OffsetDateTime)]
              .to[List]
              .transact(xa)
              .map(
                _.map(t =>
                  InboxDtos.MessageDto(
                    UUID.randomUUID(),
                    t._1,
                    t._2,
                    t._3,
                    t._4,
                    t._5,
                    t._6
                  )
                )
              )
          val notes: IO[List[InboxDtos.NoteDto]] =
            sql"""select n.id, p.display_name, n.body, n.created_at
                  from public.conversation_notes n
                  left join public.profile p on p.id = n.author_id
                  where n.thread_id = $threadId order by n.created_at"""
              .query[(UUID, Option[String], String, OffsetDateTime)]
              .to[List]
              .transact(xa)
              .map(_.map(t => InboxDtos.NoteDto(t._1, t._2, t._3, t._4)))
          messages.flatMap(ms =>
            notes.map(ns => Some(InboxDtos.ThreadDetail(toDto(r), ms, ns)))
          )
      }

  private final case class Loaded(
      threadId: UUID,
      campaignId: Option[UUID],
      enrollmentId: Option[UUID],
      contactId: Option[UUID],
      subject: String,
      providerThreadId: Option[String],
      toEmail: String,
      lastMsg: Option[(Option[String], Option[String])],
      accountId: UUID,
      accountEmail: String,
      accountProvider: String
  )

  /** Reply through the thread's own sending account, preserving threading:
    * In-Reply-To/References point at the last known Message-ID; provider
    * submitReply is used when a provider thread id exists.
    */
  def reply(
      xa: Transactor[IO],
      workspaceId: UUID,
      threadId: UUID,
      userId: UUID,
      bodyText: String,
      registry: Option[ProviderRegistry],
      emailAccounts: EmailAccountRepos
  ): IO[Either[Envelope, InboxDtos.MessageDto]] =
    val load: IO[Either[Envelope, Loaded]] =
      sql"""select id, email_account_id, contact_id, campaign_id, enrollment_id, subject, provider_thread_id
            from public.email_threads where workspace_id = $workspaceId and id = $threadId"""
        .query[
          (
              UUID,
              UUID,
              Option[UUID],
              Option[UUID],
              Option[UUID],
              String,
              Option[String]
          )
        ]
        .option
        .transact(xa)
        .flatMap {
          case None =>
            IO.pure(Left(Envelope("not_found", "Conversation not found.")))
          case Some(t) =>
            val acctIdOpt: Option[UUID] = Some(t._2)
            sql"""select c.email, c.state::text from public.email_threads th
                  join public.contact c on c.id = th.contact_id where th.id = $threadId"""
              .query[(String, String)]
              .option
              .transact(xa)
              .flatMap {
                case None =>
                  IO.pure(
                    Left(Envelope("not_found", "Conversation not found."))
                  )
                case Some((email, state)) if state != "active" =>
                  IO.pure(
                    Left(
                      Envelope(
                        "validation_failed",
                        s"Recipient is $state — replies are disabled."
                      )
                    )
                  )
                case Some((email, _)) =>
                  sql"""select internet_message_id, provider_thread_id from public.outbound_message
                        where enrollment_id = ${t._5} and internet_message_id is not null
                        order by created_at desc limit 1"""
                    .query[(Option[String], Option[String])]
                    .option
                    .transact(xa)
                    .flatMap { lastMsg =>
                      acctIdOpt
                        .traverse(aid =>
                          emailAccounts.findAccount(workspaceId, aid)
                        )
                        .flatMap {
                          case None =>
                            IO.pure(
                              Left(
                                Envelope("not_found", "Conversation not found.")
                              )
                            )
                          case Some(None) =>
                            IO.pure(
                              Left(
                                Envelope("not_found", "Conversation not found.")
                              )
                            )
                          case Some(Some(a)) if a.status != "connected" =>
                            IO.pure(
                              Left(
                                Envelope(
                                  "validation_failed",
                                  "Mailbox is not connected — reconnect it first."
                                )
                              )
                            )
                          case Some(Some(a)) =>
                            registry.flatMap(_.adapter(a.provider)) match
                              case None =>
                                IO.pure(
                                  Left(
                                    Envelope(
                                      "validation_failed",
                                      "Provider adapter unavailable for this mailbox."
                                    )
                                  )
                                )
                              case Some(_) =>
                                IO.pure(
                                  Right(
                                    Loaded(
                                      threadId,
                                      t._4,
                                      t._5,
                                      t._3,
                                      t._6,
                                      t._7,
                                      email,
                                      lastMsg,
                                      a.id,
                                      a.emailAddress,
                                      a.provider
                                    )
                                  )
                                )
                        }
                    }
              }
        }
    load.flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(ld) =>
        val acct = emailAccounts // adapter resolved below
        registry.flatMap(_.adapter(ld.accountProvider)) match
          case None =>
            IO.pure(
              Left(
                Envelope(
                  "validation_failed",
                  "Provider adapter unavailable for this mailbox."
                )
              )
            )
          case Some(adapter) =>
            val load0 = registry.get.credentialLoader(
              emailAccounts,
              workspaceId,
              ld.accountId,
              ld.accountProvider
            )
            val msgIdHeader =
              s"<${UUID.randomUUID()}@${ld.accountEmail.split('@').lift(1).getOrElse("arcadence.local")}>"
            val headers = Map("Message-ID" -> msgIdHeader) ++
              ld.lastMsg.flatMap(_._1).map(m => "In-Reply-To" -> m) ++
              ld.lastMsg.flatMap(_._1).map(m => "References" -> m)
            val submission =
              (ld.lastMsg.flatMap(_._2), ld.lastMsg.flatMap(_._1)) match
                case (Some(tid), Some(inr)) =>
                  adapter.submitReply(
                    load0,
                    ReplyMessage(
                      tid,
                      inr,
                      ld.accountEmail,
                      List(ld.toEmail),
                      "Re: " + ld.subject,
                      bodyText,
                      None
                    )
                  )
                case _ =>
                  adapter.submit(
                    load0,
                    OutboundMessage(
                      ld.accountEmail,
                      List(ld.toEmail),
                      "Re: " + ld.subject,
                      bodyText,
                      None,
                      headers
                    )
                  )
            submission.flatMap {
              case Left(err) =>
                IO.pure(
                  Left(
                    Envelope("provider_error", s"Reply failed: ${err.detail}")
                  )
                )
              case Right(providerId) =>
                val persist = (for
                  _ <-
                    sql"""insert into public.outbound_message
                          (workspace_id, campaign_id, enrollment_id, contact_id, thread_id,
                           email_account_id, subject, body_text, internet_message_id, provider_message_id,
                           provider_thread_id, status)
                  values ($workspaceId, ${ld.campaignId}, ${ld.enrollmentId}, ${ld.contactId}, $threadId,
                          ${ld.accountId}, ${"Re: " + ld.subject}, $bodyText, $msgIdHeader,
                          ${providerId.value}, ${ld.providerThreadId.orNull}, 'sent')""".update.run
                  _ <-
                    sql"""insert into public.email_messages
                          (workspace_id, thread_id, direction, from_address, to_addresses, subject,
                           body_text, internet_message_id, provider_message_id, provider_thread_id, occurred_at)
                  values ($workspaceId, $threadId, 'outbound', ${ld.accountEmail},
                          ${Json
                        .arr(Json.fromString(ld.toEmail))
                        .noSpaces}::jsonb,
                          ${"Re: " + ld.subject}, $bodyText, $msgIdHeader, ${providerId.value},
                          ${ld.providerThreadId.orNull}, now())""".update.run
                  _ <-
                    sql"update public.email_threads set last_message_at = now(), unread = false where id = $threadId".update.run.void
                yield ())
                persist
                  .transact(xa)
                  .as(
                    Right(
                      InboxDtos.MessageDto(
                        UUID.randomUUID(),
                        "outbound",
                        ld.accountEmail,
                        "Re: " + ld.subject,
                        bodyText,
                        "",
                        OffsetDateTime.now()
                      )
                    )
                  )
            }
    }

  private class NotFoundSignal extends RuntimeException("not found")
  private class InvalidSignal(message: String) extends RuntimeException(message)

  def patch(
      xa: Transactor[IO],
      workspaceId: UUID,
      threadId: UUID,
      assignedTo: Option[UUID],
      userId: UUID
  ): IO[Boolean] =
    sql"""update public.email_threads set assigned_to = $assignedTo
          where workspace_id = $workspaceId and id = $threadId""".update.run
      .map(_ > 0)
      .transact(xa)

  def addNote(
      xa: Transactor[IO],
      workspaceId: UUID,
      threadId: UUID,
      authorId: UUID,
      body: String
  ): IO[Boolean] =
    sql"""insert into public.conversation_notes (workspace_id, thread_id, author_id, body)
          values ($workspaceId, $threadId, $authorId, $body)""".update.run
      .map(_ > 0)
      .transact(xa)
