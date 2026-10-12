package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json

import java.time.OffsetDateTime
import java.util.UUID

final case class Workspace(
    id: UUID,
    organizationId: UUID,
    name: String,
    slug: String,
    timezone: String,
    dailySendCap: Int,
    createdAt: OffsetDateTime
)

final case class Membership(workspaceId: UUID, userId: UUID, role: String)

final case class MemberView(
    userId: UUID,
    role: String,
    displayName: Option[String],
    email: Option[String],
    joinedAt: OffsetDateTime
)

final case class Invitation(
    id: UUID,
    workspaceId: UUID,
    email: String,
    role: String,
    token: UUID,
    invitedBy: Option[UUID],
    expiresAt: OffsetDateTime,
    acceptedAt: Option[OffsetDateTime]
)

/** Tenant-scoped data access. Every query carries an explicit workspace
  * predicate; Scala-side authorization (Authorizer) is the gate, RLS the
  * backstop (docs/ARCHITECTURE.md §3).
  */
final class WorkspaceRepos(transactor: Transactor[IO]):
  import WorkspaceRepos.*

  // -- Workspaces -----------------------------------------------------------

  def createWorkspaceWithOwner(
      ownerUserId: UUID,
      organizationName: String,
      workspaceName: String,
      slug: String
  ): IO[Workspace] =
    val queries = for
      orgId <-
        sql"insert into public.organization (name) values ($organizationName) returning id"
          .query[UUID]
          .unique
      workspace <- sql"""
        insert into public.workspace (organization_id, name, slug)
        values ($orgId, $workspaceName, $slug)
        returning id, organization_id, name, slug, timezone, daily_send_cap, created_at
      """.query[WorkspaceRow].unique
      _ <- sql"""
        insert into public.workspace_member (workspace_id, user_id, role)
        values (${workspace.id}, $ownerUserId, 'owner')
      """.update.run
      _ <- sql"""
        insert into public.audit_log (workspace_id, actor, action, target_type, target_id)
        values (${workspace.id}, $ownerUserId, 'workspace.created', 'workspace', ${workspace.id}::text)
      """.update.run
    yield workspace.toDomain
    queries.transact(transactor)

  def listForUser(userId: UUID): IO[List[Workspace]] =
    sql"""
      select w.id, w.organization_id, w.name, w.slug, w.timezone, w.daily_send_cap, w.created_at
      from public.workspace w
      join public.workspace_member m on m.workspace_id = w.id
      where m.user_id = $userId and w.deleted_at is null
      order by w.created_at
    """.query[WorkspaceRow].to[List].map(_.map(_.toDomain)).transact(transactor)

  /** Workspaces the user belongs to, with their membership role. */
  def listForUserWithRole(userId: UUID): IO[List[(Workspace, String)]] =
    sql"""
      select w.id, w.organization_id, w.name, w.slug, w.timezone, w.daily_send_cap, w.created_at, m.role
      from public.workspace w
      join public.workspace_member m on m.workspace_id = w.id
      where m.user_id = $userId and w.deleted_at is null
      order by w.created_at
    """
      .query[(WorkspaceRow, String)]
      .to[List]
      .map(_.map { case (row, role) => (row.toDomain, role) })
      .transact(transactor)

  def findById(id: UUID): IO[Option[Workspace]] =
    sql"""
      select id, organization_id, name, slug, timezone, daily_send_cap, created_at
      from public.workspace
      where id = $id and deleted_at is null
    """.query[WorkspaceRow].option.map(_.map(_.toDomain)).transact(transactor)

  def update(
      id: UUID,
      name: Option[String],
      timezone: Option[String],
      dailySendCap: Option[Int]
  ): IO[Option[Workspace]] =
    sql"""
      update public.workspace
      set name    = coalesce($name, name),
          timezone = coalesce($timezone, timezone),
          daily_send_cap = coalesce($dailySendCap, daily_send_cap)
      where id = $id and deleted_at is null
      returning id, organization_id, name, slug, timezone, daily_send_cap, created_at
    """.query[WorkspaceRow].option.map(_.map(_.toDomain)).transact(transactor)

  def membershipOf(userId: UUID, workspaceId: UUID): IO[Option[Membership]] =
    sql"""
      select workspace_id, user_id, role
      from public.workspace_member
      where workspace_id = $workspaceId and user_id = $userId
    """.query[Membership].option.transact(transactor)

  // -- Members --------------------------------------------------------------

  def listMembers(workspaceId: UUID): IO[List[MemberView]] =
    sql"""
      select m.user_id, m.role, p.display_name, u.email, m.created_at
      from public.workspace_member m
      left join public.profile p on p.id = m.user_id
      left join auth.users u on u.id = m.user_id
      where m.workspace_id = $workspaceId
      order by m.created_at
    """.query[MemberView].to[List].transact(transactor)

  def findMember(workspaceId: UUID, userId: UUID): IO[Option[MemberView]] =
    sql"""
      select m.user_id, m.role, p.display_name, u.email, m.created_at
      from public.workspace_member m
      left join public.profile p on p.id = m.user_id
      left join auth.users u on u.id = m.user_id
      where m.workspace_id = $workspaceId and m.user_id = $userId
    """.query[MemberView].option.transact(transactor)

  def updateRole(
      workspaceId: UUID,
      userId: UUID,
      role: String
  ): IO[Option[Membership]] =
    sql"""
      update public.workspace_member
      set role = ${role}::public.workspace_role
      where workspace_id = $workspaceId and user_id = $userId and role <> 'owner'
      returning workspace_id, user_id, role
    """.query[Membership].option.transact(transactor)

  def removeMember(workspaceId: UUID, userId: UUID): IO[Option[Membership]] =
    sql"""
      delete from public.workspace_member
      where workspace_id = $workspaceId and user_id = $userId and role <> 'owner'
      returning workspace_id, user_id, role
    """.query[Membership].option.transact(transactor)

  // -- Invitations ----------------------------------------------------------

  def createInvitation(
      workspaceId: UUID,
      email: String,
      role: String,
      invitedBy: UUID
  ): IO[Invitation] =
    sql"""
      insert into public.invitation (workspace_id, email, role, invited_by)
      values ($workspaceId, ${email.toLowerCase}, ${role}::public.workspace_role, $invitedBy)
      returning id, workspace_id, email, role, token, invited_by, expires_at, accepted_at
    """.query[InvitationRow].map(_.toDomain).unique.transact(transactor)

  def findOpenInvitation(
      workspaceId: UUID,
      invitationId: UUID
  ): IO[Option[Invitation]] =
    sql"""
      select id, workspace_id, email, role, token, invited_by, expires_at, accepted_at
      from public.invitation
      where id = $invitationId and workspace_id = $workspaceId and accepted_at is null
    """.query[InvitationRow].option.map(_.map(_.toDomain)).transact(transactor)

  def listOpenInvitations(workspaceId: UUID): IO[List[Invitation]] =
    sql"""
      select id, workspace_id, email, role, token, invited_by, expires_at, accepted_at
      from public.invitation
      where workspace_id = $workspaceId and accepted_at is null
      order by created_at desc
    """
      .query[InvitationRow]
      .to[List]
      .map(_.map(_.toDomain))
      .transact(transactor)

  def findByToken(token: UUID): IO[Option[Invitation]] =
    sql"""
      select id, workspace_id, email, role, token, invited_by, expires_at, accepted_at
      from public.invitation
      where token = $token and accepted_at is null and expires_at > now()
    """.query[InvitationRow].option.map(_.map(_.toDomain)).transact(transactor)

  def findOpenInvitationByEmail(
      workspaceId: UUID,
      email: String
  ): IO[Option[Invitation]] =
    sql"""
      select id, workspace_id, email, role, token, invited_by, expires_at, accepted_at
      from public.invitation
      where workspace_id = $workspaceId and lower(email) = ${email.toLowerCase} and accepted_at is null
    """.query[InvitationRow].option.map(_.map(_.toDomain)).transact(transactor)

  /** Accepts an open, unexpired invitation and creates the membership in one
    * transaction; joining twice refreshes the role instead of failing.
    */
  def acceptInvitation(token: UUID, userId: UUID): IO[Option[Membership]] =
    val queries = for
      invitation <- sql"""
        update public.invitation
        set accepted_at = now()
        where token = $token and accepted_at is null and expires_at > now()
        returning workspace_id, role
      """.query[(UUID, String)].option
      membership <- invitation match
        case Some((workspaceId, role)) =>
          sql"""
            insert into public.workspace_member (workspace_id, user_id, role)
            values ($workspaceId, $userId, ${role}::public.workspace_role)
            on conflict (workspace_id, user_id) do update set role = excluded.role
            returning workspace_id, user_id, role
          """.query[Membership].option
        case None => none[Membership].pure[doobie.free.ConnectionIO]
      _ <- invitation match
        case Some((workspaceId, _)) =>
          sql"""
            insert into public.audit_log (workspace_id, actor, action, target_type)
            values ($workspaceId, $userId, 'invitation.accepted', 'invitation')
          """.update.run.void
        case None => ().pure[doobie.free.ConnectionIO]
    yield membership
    queries.transact(transactor)

  def revokeInvitation(
      workspaceId: UUID,
      invitationId: UUID
  ): IO[Option[Invitation]] =
    sql"""
      delete from public.invitation
      where id = $invitationId and workspace_id = $workspaceId and accepted_at is null
      returning id, workspace_id, email, role, token, invited_by, expires_at, accepted_at
    """.query[InvitationRow].option.map(_.map(_.toDomain)).transact(transactor)

  // -- Audit ----------------------------------------------------------------

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

object WorkspaceRepos:
  // Postgres enum labels map to String via Doobie's implicit (needs explicit
  // casting in SQL, done above); timestamps arrive as OffsetDateTime.
  final case class WorkspaceRow(
      id: UUID,
      organizationId: UUID,
      name: String,
      slug: String,
      timezone: String,
      dailySendCap: Int,
      createdAt: OffsetDateTime
  ):
    def toDomain: Workspace = Workspace(
      id,
      organizationId,
      name,
      slug,
      timezone,
      dailySendCap,
      createdAt
    )

  final case class InvitationRow(
      id: UUID,
      workspaceId: UUID,
      email: String,
      role: String,
      token: UUID,
      invitedBy: Option[UUID],
      expiresAt: OffsetDateTime,
      acceptedAt: Option[OffsetDateTime]
  ):
    def toDomain: Invitation =
      Invitation(
        id,
        workspaceId,
        email,
        role,
        token,
        invitedBy,
        expiresAt,
        acceptedAt
      )
