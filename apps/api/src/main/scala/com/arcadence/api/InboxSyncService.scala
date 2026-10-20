package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.core.email.InboxMessage
import com.arcadence.api.ProviderRegistry
import doobie.*
import doobie.free.FC
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.util.UUID

/** Inbound synchronization (Phase 6.1): pulls each connected mailbox through
  * its provider adapter's incremental sync (Gmail history, Graph delta, IMAP
  * UID cursor), persisting messages idempotently.
  *
  * Idempotency & ordering: `provider_message_refs` has a unique (account,
  * provider_message_id) key — duplicated, delayed or replayed batches insert
  * exactly once (message + ref claim share one transaction; a losing claim
  * rolls the insert back). Out-of-order arrivals attach to the thread by ids.
  * Expired/invalid cursors are recorded on `mailbox_sync_cursors`; clearing the
  * cursor forces a full resync that dedupes through the refs table — never
  * duplicating conversations.
  */
final class InboxSyncService(
    xa: Transactor[IO],
    registry: Option[ProviderRegistry],
    emailAccounts: EmailAccountRepos
):

  def syncWorkspace(workspaceId: UUID): IO[Int] =
    connectedAccounts(workspaceId).flatMap { accounts =>
      accounts.toList
        .traverse { case (aid, _) => syncAccount(workspaceId, aid) }
        .map(_.sum)
    }

  def connectedAccounts(workspaceId: UUID): IO[List[(UUID, String)]] =
    sql"select id, provider::text from public.email_accounts where workspace_id = $workspaceId and status = 'connected'"
      .query[(UUID, String)]
      .to[List]
      .transact(xa)

  /** Full resync: clears the cursor so the provider replays; refs dedupe. */
  def resyncAccount(workspaceId: UUID, accountId: UUID): IO[Int] =
    sql"delete from public.mailbox_sync_cursors where email_account_id = $accountId".update.run
      .transact(xa) *>
      syncAccount(workspaceId, accountId)

  def syncAccount(workspaceId: UUID, accountId: UUID): IO[Int] =
    emailAccounts.findAccount(workspaceId, accountId).flatMap {
      case None          => IO.pure(0)
      case Some(account) =>
        registry.flatMap(_.adapter(account.provider)) match
          case None          => IO.pure(0)
          case Some(adapter) =>
            val load = registry.get.credentialLoader(
              emailAccounts,
              workspaceId,
              accountId,
              account.provider
            )
            cursorFor(accountId).flatMap { cursor =>
              adapter.syncInbox(load, cursor).flatMap {
                case Left(err) =>
                  sql"""update public.mailbox_sync_cursors set status = 'error', last_error = ${err.detail}, last_synced_at = now()
                        where email_account_id = $accountId""".update.run.void
                    .transact(xa) *>
                    IO.pure(0)
                case Right(batch) =>
                  sql"""
                    insert into public.mailbox_sync_cursors (email_account_id, cursor, last_synced_at, status)
                    values ($accountId, ${batch.nextCursor}, now(), 'idle')
                    on conflict (email_account_id) do update
                      set cursor = excluded.cursor, last_synced_at = now(), status = 'idle', last_error = null
                  """.update.run.transact(xa) *>
                    batch.messages
                      .traverse(m => process(workspaceId, accountId, m))
                      .map(_.count(identity))
              }
            }
    }

  private def cursorFor(accountId: UUID): IO[Option[String]] =
    sql"select cursor from public.mailbox_sync_cursors where email_account_id = $accountId"
      .query[Option[String]]
      .option
      .transact(xa)
      .map(_.flatten)

  private val nothing: ConnectionIO[Unit] = ().pure[ConnectionIO]

  /** Idempotent per (account, provider_message_id). Returns true if new.
    * Atomic: message insert + ref claim in one transaction; a losing ref claim
    * (duplicate delivery) rolls the whole insert back.
    */
  def process(
      workspaceId: UUID,
      accountId: UUID,
      m: InboxMessage
  ): IO[Boolean] =
    val tx: ConnectionIO[Boolean] =
      for
        threadId <- resolveThreadId(workspaceId, accountId, m)
        msgId <-
          sql"""
            insert into public.email_messages
              (workspace_id, thread_id, direction, from_address, to_addresses, subject,
               body_text, body_html, internet_message_id, provider_message_id, provider_thread_id, occurred_at)
            values ($workspaceId, $threadId, 'inbound', ${m.fromAddress}, '[]'::jsonb,
                    ${m.subject.getOrElse("")}, ${m.textBody.getOrElse(
              ""
            )}, ${m.htmlBody.getOrElse("")},
                    ${m.internetMessageId}, ${m.providerMessageId}, ${m.providerThreadId}, ${m.receivedAt})
            returning id
          """.query[UUID].unique
        refClaimed <-
          sql"""
            insert into public.provider_message_refs (workspace_id, email_account_id, provider_message_id, message_id)
            values ($workspaceId, $accountId, ${m.providerMessageId}, $msgId)
            on conflict (email_account_id, provider_message_id) do nothing
            returning id
          """.query[UUID].option
        _ <-
          if refClaimed.isEmpty then FC.raiseError(new DuplicateDelivery)
          else nothing
        _ <-
          sql"""
            insert into public.inbound_events (workspace_id, email_account_id, provider_message_id, event_type, processed_at)
            values ($workspaceId, $accountId, ${m.providerMessageId}, 'received', now())
            on conflict (email_account_id, provider_message_id, event_type) do nothing
          """.update.run.void
        _ <-
          sql"""
            update public.email_threads set unread = true, last_message_at = greatest(last_message_at, ${m.receivedAt})
            where id = $threadId
          """.update.run.void
        _ <- ReplyClassifier.applyInboundEffectsT(threadId, m)
      yield true
    tx.transact(xa).handleError {
      case _: DuplicateDelivery => false
      case other                => throw other
    }

  private class DuplicateDelivery
      extends RuntimeException("duplicate provider message")

  /** Thread resolution (Phase 6.2 linkage, conservative): provider thread id
    * match, then In-Reply-To -> stored internet message id (outbound or
    * inbound), then the most recent thread for the same contact + account, else
    * create a new thread linked to the contact's active enrollment.
    */
  private def resolveThreadId(
      workspaceId: UUID,
      accountId: UUID,
      m: InboxMessage
  ): ConnectionIO[UUID] =
    val byProviderThread: ConnectionIO[Option[UUID]] = m.providerThreadId match
      case Some(tid) =>
        sql"select id from public.email_threads where email_account_id = $accountId and provider_thread_id = $tid limit 1"
          .query[UUID]
          .option
      case None => nothing.as(None)
    byProviderThread.flatMap {
      case Some(t) => FC.pure(t)
      case None    =>
        val byReplyTo: ConnectionIO[Option[UUID]] = m.internetMessageId match
          case Some(inr) if inr != m.providerMessageId =>
            sql"select om.thread_id from public.outbound_message om where om.internet_message_id = $inr and om.thread_id is not null limit 1"
              .query[UUID]
              .option
              .flatMap {
                case Some(t) => FC.pure(Some(t))
                case None    =>
                  sql"select m.thread_id from public.email_messages m where m.internet_message_id = $inr and m.thread_id is not null limit 1"
                    .query[UUID]
                    .option
              }
          case _ => nothing.as(None)
        byReplyTo.flatMap {
          case Some(t) => FC.pure(t)
          case None    =>
            val byContact: ConnectionIO[Option[UUID]] =
              sql"""
                select t.id from public.email_threads t
                join public.contact c on c.id = t.contact_id
                where t.email_account_id = $accountId and lower(c.email) = lower(${m.fromAddress})
                order by t.last_message_at desc limit 1
              """.query[UUID].option
            byContact.flatMap {
              case Some(t) => FC.pure(t)
              case None    =>
                val createLinked: ConnectionIO[Option[UUID]] =
                  sql"""
                    insert into public.email_threads
                      (workspace_id, email_account_id, contact_id, campaign_id, enrollment_id,
                       provider_thread_id, subject)
                    select $workspaceId, $accountId, c.id, e.campaign_id, e.id, ${m.providerThreadId}, ${m.subject
                      .getOrElse("")}
                    from public.contact c
                    left join lateral (
                      select id, campaign_id from public.campaign_enrollment
                      where contact_id = c.id and status = 'active'
                      order by started_at desc limit 1
                    ) e on true
                    where lower(c.email) = lower(${m.fromAddress})
                    limit 1
                    returning id
                  """.query[UUID].option
                createLinked.flatMap {
                  case Some(t) => FC.pure(t)
                  case None    =>
                    sql"""
                      insert into public.email_threads
                        (workspace_id, email_account_id, provider_thread_id, subject)
                      values ($workspaceId, $accountId, ${m.providerThreadId}, ${m.subject
                        .getOrElse("")})
                      returning id
                    """.query[UUID].unique.map(t => t)
                }
            }
        }
    }
