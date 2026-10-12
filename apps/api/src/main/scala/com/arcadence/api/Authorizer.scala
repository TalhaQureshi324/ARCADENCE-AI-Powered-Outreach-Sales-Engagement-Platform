package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor

import java.util.UUID

/** Resolves real workspace membership from the database and enforces the
  * permission model. Tenants the caller is not a member of are reported as not
  * found — existence is never leaked across tenants.
  */
final class Authorizer(transactor: Transactor[IO]):

  def membershipOf(userId: UUID, workspaceId: UUID): IO[Option[Membership]] =
    sql"""
      select m.workspace_id, m.user_id, m.role
      from public.workspace_member m
      join public.workspace w on w.id = m.workspace_id
      where m.workspace_id = $workspaceId and m.user_id = $userId and w.deleted_at is null
    """.query[Membership].option.transact(transactor)

  /** Requires the caller's membership to carry the given permission. Yields
    * Left(WorkspaceNotFound) for non-members (404 semantics) and
    * Left(InsufficientRole) for members lacking the permission (403).
    */
  def requirePermission(
      user: AuthenticatedUser,
      workspaceId: UUID,
      permission: Permissions.Permission
  ): IO[Either[ApiError, Membership]] =
    membershipOf(user.id, workspaceId).map {
      case None             => Left(ApiError.WorkspaceNotFound)
      case Some(membership) =>
        val role = WorkspaceRole.fromLabel(membership.role)
        role match
          case Left(unknown) =>
            Left(
              ApiError.Invalid(
                s"Workspace membership has an invalid role: $unknown"
              )
            )
          case Right(r) =>
            if Permissions.forRole(r).contains(permission) then
              Right(membership)
            else Left(ApiError.InsufficientRole(permission.label))
    }
