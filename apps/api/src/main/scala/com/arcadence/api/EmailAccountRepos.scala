package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.free.FC
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json

import java.time.OffsetDateTime
import java.util.UUID

/** Persistence for email accounts, account groups and health events (Phase
  * 2.1). Account credentials are intentionally absent — they are written only
  * by provider connect flows (2.2+) through the encryption layer, never by this
  * CRUD surface.
  */
final class EmailAccountRepos(transactor: Transactor[IO]):

  // -- reads ----------------------------------------------------------------

  final case class AccountView(
      id: UUID,
      workspaceId: UUID,
      accountGroupId: Option[UUID],
      accountGroupName: Option[String],
      provider: String,
      emailAddress: String,
      displayName: Option[String],
      status: String,
      statusMessage: Option[String],
      dailySendCap: Option[Int],
      signature: Option[String],
      isDefault: Boolean,
      lastSyncedAt: Option[OffsetDateTime],
      syncStatus: Option[String],
      syncError: Option[String],
      createdAt: OffsetDateTime
  )

  private val accountSelect = fr"""
      select a.id, a.workspace_id, a.account_group_id, g.name,
             a.provider, a.email_address, a.display_name, a.status,
             a.status_message, a.daily_send_cap, a.signature, a.is_default,
             a.last_synced_at, ss.sync_status, ss.last_error, a.created_at
      from public.email_accounts a
      left join public.account_group g on g.id = a.account_group_id
      left join public.provider_sync_state ss
        on ss.email_account_id = a.id and ss.direction = 'inbox'
    """

  implicit val accountRead: Read[AccountView] = Read[
    (
        UUID,
        UUID,
        Option[UUID],
        Option[String],
        String,
        String,
        Option[String],
        String,
        Option[String],
        Option[Int],
        Option[String],
        Boolean,
        Option[OffsetDateTime],
        Option[String],
        Option[String],
        OffsetDateTime
    )
  ].map {
    case (
          id,
          wsId,
          groupId,
          groupName,
          provider,
          email,
          display,
          status,
          statusMsg,
          cap,
          sig,
          isDefault,
          lastSync,
          syncStatus,
          syncError,
          createdAt
        ) =>
      AccountView(
        id,
        wsId,
        groupId,
        groupName,
        provider,
        email,
        display,
        status,
        statusMsg,
        cap,
        sig,
        isDefault,
        lastSync,
        syncStatus,
        syncError,
        createdAt
      )
  }

  final case class GroupView(
      id: UUID,
      name: String,
      accountCount: Int,
      createdAt: OffsetDateTime
  )

  def listAccounts(workspaceId: UUID): IO[List[AccountView]] =
    (accountSelect ++ fr"where a.workspace_id = $workspaceId order by a.created_at")
      .query[AccountView]
      .to[List]
      .transact(transactor)

  def findAccount(workspaceId: UUID, accountId: UUID): IO[Option[AccountView]] =
    (accountSelect ++ fr"where a.workspace_id = $workspaceId and a.id = $accountId")
      .query[AccountView]
      .option
      .transact(transactor)

  def listGroups(workspaceId: UUID): IO[List[GroupView]] =
    sql"""
      select g.id, g.name, count(a.id)::int, g.created_at
      from public.account_group g
      left join public.email_accounts a on a.account_group_id = g.id
      where g.workspace_id = $workspaceId
      group by g.id
      order by g.created_at
    """.query[GroupView].to[List].transact(transactor)

  final case class HealthEventView(
      id: UUID,
      eventType: String,
      message: Option[String],
      occurredAt: OffsetDateTime
  )

  def listHealthEvents(
      workspaceId: UUID,
      accountId: UUID,
      limit: Int = 20
  ): IO[List[HealthEventView]] =
    sql"""
      select id, event_type, message, occurred_at
      from public.account_health_events
      where workspace_id = $workspaceId and email_account_id = $accountId
      order by occurred_at desc
      limit $limit
    """.query[HealthEventView].to[List].transact(transactor)

  // -- group writes -----------------------------------------------------------

  def createGroup(workspaceId: UUID, name: String): IO[Option[GroupView]] =
    (for
      id <- sql"""
        insert into public.account_group (workspace_id, name)
        values ($workspaceId, $name)
        returning id
      """.query[UUID].unique
      row <- sql"""
        select id, name, 0, created_at from public.account_group where id = $id
      """.query[GroupView].option
    yield row).transact(transactor)

  def renameGroup(workspaceId: UUID, groupId: UUID, name: String): IO[Int] =
    sql"""
      update public.account_group set name = $name
      where workspace_id = $workspaceId and id = $groupId
    """.update.run.transact(transactor)

  def deleteGroup(workspaceId: UUID, groupId: UUID): IO[Int] =
    sql"""
      delete from public.account_group where workspace_id = $workspaceId and id = $groupId
    """.update.run.transact(transactor)

  // -- account writes ---------------------------------------------------------

  final case class AccountPatch(
      displayName: Option[Option[String]] = None,
      accountGroupId: Option[Option[UUID]] = None,
      dailySendCap: Option[Option[Int]] = None,
      signature: Option[Option[String]] = None
  )

  def updateAccount(
      workspaceId: UUID,
      accountId: UUID,
      patch: AccountPatch
  ): IO[Int] =
    val sets = List.newBuilder[Fragment]
    patch.displayName.foreach(v => sets += fr"display_name = $v")
    patch.accountGroupId.foreach(v => sets += fr"account_group_id = $v")
    patch.dailySendCap.foreach(v => sets += fr"daily_send_cap = $v")
    patch.signature.foreach(v => sets += fr"signature = $v")
    val assignments = sets.result()
    if assignments.isEmpty then IO.pure(0)
    else
      val setClause = assignments.reduce((a, b) => a ++ fr", " ++ b)
      (fr"update public.email_accounts set " ++ setClause ++
        fr"where workspace_id = $workspaceId and id = $accountId").update.run
        .transact(transactor)

  /** Applies a status transition and appends a health event atomically. The
    * WHERE guard makes concurrent transitions race-safe: the update only lands
    * when the row is still in the expected state.
    */
  def transitionStatus(
      workspaceId: UUID,
      accountId: UUID,
      fromStatus: String,
      toStatus: String,
      statusMessage: Option[String],
      eventType: String,
      eventMessage: Option[String],
      details: Json = Json.obj()
  ): IO[Boolean] =
    (for
      updated <- sql"""
        update public.email_accounts
        set status = $toStatus::public.connection_status,
            status_message = $statusMessage
        where workspace_id = $workspaceId and id = $accountId and status = $fromStatus::public.connection_status
      """.update.run
      _ <- updated match
        case 0 => FC.unit
        case _ =>
          sql"""
            insert into public.account_health_events
              (workspace_id, email_account_id, event_type, message, details)
            values ($workspaceId, $accountId, $eventType::public.account_health_event_type,
                    $eventMessage, ${details.noSpaces}::jsonb)
          """.update.run.void
    yield updated).map(_ > 0).transact(transactor)

  /** Fully removes the account (credentials, health events and sync state
    * cascade). Returns whether a row was deleted.
    */
  def deleteAccount(workspaceId: UUID, accountId: UUID): IO[Boolean] =
    sql"delete from public.email_accounts where workspace_id = $workspaceId and id = $accountId".update.run
      .map(_ > 0)
      .transact(transactor)

  /** Connect-flow upsert: create the account or mark an existing one
    * reconnected. Returns (accountId, created).
    */
  def upsertConnectedAccount(
      workspaceId: UUID,
      provider: String,
      emailAddress: String,
      displayName: Option[String]
  ): IO[(UUID, Boolean)] =
    (for
      existing <- sql"""
        select id from public.email_accounts
        where workspace_id = $workspaceId and lower(email_address) = lower($emailAddress)
      """.query[UUID].option
      id <- existing match
        case Some(found) =>
          sql"""
            update public.email_accounts
            set status = 'connected'::public.connection_status,
                status_message = null,
                display_name = coalesce(${displayName}, display_name)
            where id = $found
          """.update.run.as(found)
        case None =>
          sql"""
            insert into public.email_accounts
              (workspace_id, provider, email_address, display_name, status)
            values ($workspaceId, ${provider}::public.email_provider, $emailAddress,
                    ${displayName}, 'connected'::public.connection_status)
            returning id
          """.query[UUID].unique
    yield (id, existing.isEmpty)).transact(transactor)

  /** Creates a manually-connected (SMTP/IMAP) account; returns the new id. */
  def createSmtpImapAccount(
      workspaceId: UUID,
      emailAddress: String,
      displayName: Option[String]
  ): IO[UUID] =
    sql"""
      insert into public.email_accounts
        (workspace_id, provider, email_address, display_name, status)
      values ($workspaceId, 'smtp_imap', $emailAddress, ${displayName},
              'connected'::public.connection_status)
      returning id
    """.query[UUID].unique.transact(transactor)

  /** Upserts the encrypted credential row for an account. */
  def storeCredentials(
      accountId: UUID,
      kind: String,
      encryptedBlob: Array[Byte],
      keyVersion: Int,
      expiresAt: Option[OffsetDateTime]
  ): IO[Unit] =
    sql"""
      insert into public.account_credentials
        (email_account_id, kind, encrypted_blob, encryption_key_version, expires_at)
      values ($accountId, $kind, $encryptedBlob, $keyVersion, $expiresAt)
      on conflict (email_account_id) do update
        set kind = excluded.kind,
            encrypted_blob = excluded.encrypted_blob,
            encryption_key_version = excluded.encryption_key_version,
            expires_at = excluded.expires_at
    """.update.run.void.transact(transactor)

  /** Fetches the encrypted credential blob, workspace-scoped via a join. */
  final case class CredentialRow(
      kind: String,
      encryptedBlob: Array[Byte],
      keyVersion: Int
  )

  def loadCredentials(
      workspaceId: UUID,
      accountId: UUID
  ): IO[Option[CredentialRow]] =
    sql"""
      select c.kind, c.encrypted_blob, c.encryption_key_version
      from public.account_credentials c
      join public.email_accounts a on a.id = c.email_account_id
      where a.workspace_id = $workspaceId and a.id = $accountId
    """.query[CredentialRow].option.transact(transactor)

  /** Seeds the per-direction sync cursors for a freshly connected account. */
  def ensureSyncState(accountId: UUID): IO[Unit] =
    sql"""
      insert into public.provider_sync_state (email_account_id, direction)
      values ($accountId, 'inbox'), ($accountId, 'sending')
      on conflict (email_account_id, direction) do nothing
    """.update.run.void.transact(transactor)

  def recordHealthEvent(
      workspaceId: UUID,
      accountId: UUID,
      eventType: String,
      message: Option[String],
      details: Json = Json.obj()
  ): IO[Unit] =
    sql"""
      insert into public.account_health_events
        (workspace_id, email_account_id, event_type, message, details)
      values ($workspaceId, $accountId, ${eventType}::public.account_health_event_type,
              $message, ${details.noSpaces}::jsonb)
    """.update.run.void.transact(transactor)

  /** Drops stored credentials for a workspace-scoped account (disconnect
    * hygiene — a disconnected account must not retain decryptable secrets).
    */
  def purgeCredentials(workspaceId: UUID, accountId: UUID): IO[Boolean] =
    sql"""
      delete from public.account_credentials c
      using public.email_accounts a
      where c.email_account_id = a.id
        and a.workspace_id = $workspaceId and a.id = $accountId
    """.update.run.map(_ > 0).transact(transactor)

  def audit(
      workspaceId: UUID,
      actor: UUID,
      action: String,
      targetType: String,
      targetId: String,
      metadata: Json = Json.obj()
  ): IO[Unit] =
    sql"""
      insert into public.audit_log (workspace_id, actor, action, target_type, target_id, metadata)
      values ($workspaceId, $actor, $action, $targetType, $targetId, ${metadata.noSpaces}::jsonb)
    """.update.run.void.transact(transactor)
