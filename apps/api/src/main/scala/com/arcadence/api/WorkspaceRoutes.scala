package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Dtos.*
import com.arcadence.api.Permissions.Permission
import io.circe.Json
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.time.OffsetDateTime
import java.util.UUID
import scala.util.Random

object Dtos:
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import io.circe.{Decoder, Encoder}
  import sttp.tapir.Schema

  final case class WorkspaceDto(
      id: UUID,
      name: String,
      slug: String,
      timezone: String,
      dailySendCap: Int,
      createdAt: OffsetDateTime,
      role: String
  )
  object WorkspaceDto:
    given Encoder[WorkspaceDto] = deriveEncoder
    given Decoder[WorkspaceDto] = deriveDecoder
    given Schema[WorkspaceDto] = Schema.derived

  final case class CreateWorkspaceRequest(name: String)
  object CreateWorkspaceRequest:
    given Encoder[CreateWorkspaceRequest] = deriveEncoder
    given Decoder[CreateWorkspaceRequest] = deriveDecoder
    given Schema[CreateWorkspaceRequest] = Schema.derived

  final case class UpdateWorkspaceRequest(
      name: Option[String] = None,
      timezone: Option[String] = None,
      dailySendCap: Option[Int] = None
  )
  object UpdateWorkspaceRequest:
    given Encoder[UpdateWorkspaceRequest] = deriveEncoder
    given Decoder[UpdateWorkspaceRequest] = deriveDecoder
    given Schema[UpdateWorkspaceRequest] = Schema.derived

  final case class MemberDto(
      userId: UUID,
      role: String,
      displayName: Option[String],
      email: Option[String],
      joinedAt: OffsetDateTime
  )
  object MemberDto:
    given Encoder[MemberDto] = deriveEncoder
    given Decoder[MemberDto] = deriveDecoder
    given Schema[MemberDto] = Schema.derived

  final case class UpdateMemberRequest(role: String)
  object UpdateMemberRequest:
    given Encoder[UpdateMemberRequest] = deriveEncoder
    given Decoder[UpdateMemberRequest] = deriveDecoder
    given Schema[UpdateMemberRequest] = Schema.derived

  final case class CreateInvitationRequest(email: String, role: String)
  object CreateInvitationRequest:
    given Encoder[CreateInvitationRequest] = deriveEncoder
    given Decoder[CreateInvitationRequest] = deriveDecoder
    given Schema[CreateInvitationRequest] = Schema.derived

  final case class InvitationDto(
      id: UUID,
      email: String,
      role: String,
      expiresAt: OffsetDateTime,
      accepted: Boolean,
      inviteUrl: String
  )
  object InvitationDto:
    given Encoder[InvitationDto] = deriveEncoder
    given Decoder[InvitationDto] = deriveDecoder
    given Schema[InvitationDto] = Schema.derived

/** Business API for workspaces, membership and invitations (Phase 1.2). Every
  * workspace-scoped endpoint resolves the caller's membership from the database
  * (Authorizer) and enforces the permission model — non-members receive 404 so
  * cross-tenant existence is never leaked.
  */
final class WorkspaceRoutes(
    authenticator: Authenticator,
    repos: WorkspaceRepos,
    authorizer: Authorizer,
    appOrigin: String
):
  import Dtos.*

  // Error output includes the status so each ApiError maps to its own code.
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def unauthorized: (SttpStatus, Envelope) = failed(
    ApiError.Unauthorized
  )

  private def verifyBearer(
      authorization: Option[String]
  ): IO[Either[(SttpStatus, Envelope), AuthenticatedUser]] =
    authorization match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator
          .verify(token.stripPrefix("Bearer ").trim)
          .map(
            _.leftMap(e =>
              (SttpStatus.Unauthorized, Envelope(e.code, e.message))
            )
          )
      case _ => IO.pure(Left(unauthorized))
  private def requirePermission(
      authorization: Option[String],
      workspaceId: UUID,
      permission: Permission
  ): IO[Either[(SttpStatus, Envelope), (AuthenticatedUser, Membership)]] =
    verifyBearer(authorization).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(user)    =>
        authorizer.requirePermission(user, workspaceId, permission).map {
          case Left(error)       => Left(failed(error))
          case Right(membership) => Right((user, membership))
        }
    }

  private def slugFor(name: String): String =
    val base = name.toLowerCase
      .replaceAll("[^a-z0-9]+", "-")
      .stripSuffix("-")
      .stripPrefix("-")
    val cleaned = if base.isEmpty then "workspace" else base.take(40)
    s"$cleaned-${Random.alphanumeric.take(6).mkString.toLowerCase}"

  private def toDto(w: Workspace, role: String): WorkspaceDto =
    WorkspaceDto(
      w.id,
      w.name,
      w.slug,
      w.timezone,
      w.dailySendCap,
      w.createdAt,
      role
    )

  private def invitationDto(i: Invitation): InvitationDto =
    InvitationDto(
      i.id,
      i.email,
      i.role,
      i.expiresAt,
      accepted = i.acceptedAt.isDefined,
      inviteUrl = s"$appOrigin/invitations/${i.token}"
    )

  private def validRole(label: String): Either[ApiError, WorkspaceRole.Role] =
    WorkspaceRole
      .fromLabel(label)
      .leftMap(_ => ApiError.Invalid(s"Unknown role: $label"))

  private def validEmail(email: String): Boolean =
    email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

  private def memberDto(view: MemberView): MemberDto =
    MemberDto(
      view.userId,
      view.role,
      view.displayName,
      view.email,
      view.joinedAt
    )

  // -- endpoint definitions -------------------------------------------------

  private val listWorkspaces = endpoint.get
    .in("api" / "v1" / "workspaces")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[WorkspaceDto]])
    .errorOut(errorOut)

  private val createWorkspace = endpoint.post
    .in("api" / "v1" / "workspaces")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CreateWorkspaceRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[WorkspaceDto])
    .errorOut(errorOut)

  private val getWorkspace = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId"))
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[WorkspaceDto])
    .errorOut(errorOut)

  private val updateWorkspace = endpoint.patch
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId"))
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[UpdateWorkspaceRequest])
    .out(jsonBody[WorkspaceDto])
    .errorOut(errorOut)

  private val listMembers = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "members")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[MemberDto]])
    .errorOut(errorOut)

  private val updateMember = endpoint.patch
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "members" / path[UUID]("userId")
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[UpdateMemberRequest])
    .out(jsonBody[MemberDto])
    .errorOut(errorOut)

  private val removeMember = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "members" / path[UUID]("userId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val listInvitations = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "invitations")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[InvitationDto]])
    .errorOut(errorOut)

  private val createInvitation = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "invitations")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CreateInvitationRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[InvitationDto])
    .errorOut(errorOut)

  private val revokeInvitation = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "invitations" / path[UUID]("invitationId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val acceptInvitation = endpoint.post
    .in("api" / "v1" / "invitations" / path[UUID]("token") / "accept")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[WorkspaceDto])
    .errorOut(errorOut)

  // -- logic ----------------------------------------------------------------

  private def listWorkspacesLogic(
      authorization: Option[String]
  ): IO[Either[(SttpStatus, Envelope), List[WorkspaceDto]]] =
    verifyBearer(authorization).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(user)    =>
        repos
          .listForUserWithRole(user.id)
          .map(pairs => Right(pairs.map((w, role) => toDto(w, role))))
    }

  private def createWorkspaceLogic(
      authorization: Option[String],
      request: CreateWorkspaceRequest
  ): IO[Either[(SttpStatus, Envelope), WorkspaceDto]] =
    verifyBearer(authorization).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(user)    =>
        val name = request.name.trim
        if name.isEmpty || name.length > 120 then
          IO.pure(
            Left(
              failed(
                ApiError.Invalid("Workspace name must be 1–120 characters.")
              )
            )
          )
        else
          repos
            .createWorkspaceWithOwner(
              ownerUserId = user.id,
              organizationName = s"$name (organization)",
              workspaceName = name,
              slug = slugFor(name)
            )
            .map(w => Right(toDto(w, "owner")))
    }

  private def getWorkspaceLogic(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), WorkspaceDto]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope)         => IO.pure(Left(envelope))
        case Right((_, membership)) =>
          repos.findById(workspaceId).map {
            case None    => Left(failed(ApiError.WorkspaceNotFound))
            case Some(w) => Right(toDto(w, membership.role))
          }
      }

  private def updateWorkspaceLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: UpdateWorkspaceRequest
  ): IO[Either[(SttpStatus, Envelope), WorkspaceDto]] =
    requirePermission(authorization, workspaceId, Permission.ManageWorkspace)
      .flatMap {
        case Left(envelope)            => IO.pure(Left(envelope))
        case Right((user, membership)) =>
          val validation: Either[ApiError, Unit] =
            if request.name.exists(_.trim.isEmpty) then
              Left(ApiError.Invalid("Workspace name cannot be empty."))
            else if request.name.exists(_.length > 120) then
              Left(
                ApiError.Invalid(
                  "Workspace name must be 120 characters or fewer."
                )
              )
            else if request.dailySendCap.exists(c => c < 1 || c > 10000) then
              Left(
                ApiError.Invalid("Daily send cap must be between 1 and 10000.")
              )
            else Right(())
          validation match
            case Left(error) => IO.pure(Left(failed(error)))
            case Right(())   =>
              (for
                updated <- repos.update(
                  workspaceId,
                  request.name.map(_.trim),
                  request.timezone,
                  request.dailySendCap
                )
                _ <- updated.traverse(_ =>
                  repos.audit(
                    workspaceId,
                    user.id,
                    "workspace.updated",
                    "workspace",
                    workspaceId.toString
                  )
                )
              yield updated).map {
                case None    => Left(failed(ApiError.WorkspaceNotFound))
                case Some(w) => Right(toDto(w, membership.role))
              }
      }

  private def listMembersLogic(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[MemberDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos
            .listMembers(workspaceId)
            .map(members => Right(members.map(memberDto)))
      }

  private def updateMemberLogic(
      authorization: Option[String],
      workspaceId: UUID,
      targetUserId: UUID,
      request: UpdateMemberRequest
  ): IO[Either[(SttpStatus, Envelope), MemberDto]] =
    requirePermission(authorization, workspaceId, Permission.ManageMembers)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          validRole(request.role) match
            case Left(error) => IO.pure(Left(failed(error)))
            case Right(role) =>
              if targetUserId == user.id then
                IO.pure(
                  Left(
                    failed(ApiError.Invalid("You cannot change your own role."))
                  )
                )
              else
                (for
                  target <- repos.membershipOf(targetUserId, workspaceId)
                  updated <- target match
                    case None                         => IO.pure(None)
                    case Some(m) if m.role == "owner" => IO.pure(None)
                    case Some(_)                      =>
                      repos.updateRole(
                        workspaceId,
                        targetUserId,
                        role.label
                      ) <* repos.audit(
                        workspaceId,
                        user.id,
                        "member.role_updated",
                        "user",
                        targetUserId.toString,
                        Json.obj("newRole" -> Json.fromString(role.label))
                      )
                  view <- updated.traverse(_ =>
                    repos.findMember(workspaceId, targetUserId)
                  )
                yield (updated, view).tupled).map {
                  case None => Left(failed(ApiError.NotFound("member")))
                  case Some((_, Some(m))) => Right(memberDto(m))
                  case Some((_, None))    =>
                    Left(failed(ApiError.NotFound("member")))
                }
      }

  private def removeMemberLogic(
      authorization: Option[String],
      workspaceId: UUID,
      targetUserId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.ManageMembers)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          if targetUserId == user.id then
            IO.pure(
              Left(
                failed(
                  ApiError.Invalid(
                    "You cannot remove yourself from a workspace."
                  )
                )
              )
            )
          else
            (for
              target <- repos.membershipOf(targetUserId, workspaceId)
              removed <- target match
                case None                         => IO.pure(None)
                case Some(m) if m.role == "owner" => IO.pure(None)
                case Some(_)                      =>
                  repos.removeMember(workspaceId, targetUserId) <* repos.audit(
                    workspaceId,
                    user.id,
                    "member.removed",
                    "user",
                    targetUserId.toString
                  )
              result = removed.map(_ => ())
            yield result).map {
              case None     => Left(failed(ApiError.NotFound("member")))
              case Some(()) => Right(())
            }
      }

  private def listInvitationsLogic(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[InvitationDto]]] =
    requirePermission(authorization, workspaceId, Permission.ManageMembers)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos
            .listOpenInvitations(workspaceId)
            .map(list => Right(list.map(invitationDto)))
      }

  private def createInvitationLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: CreateInvitationRequest
  ): IO[Either[(SttpStatus, Envelope), InvitationDto]] =
    requirePermission(authorization, workspaceId, Permission.ManageMembers)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val email = request.email.trim.toLowerCase
          if !validEmail(email) then
            IO.pure(
              Left(failed(ApiError.Invalid("Enter a valid email address.")))
            )
          else
            validRole(request.role) match
              case Left(error) => IO.pure(Left(failed(error)))
              case Right(role) =>
                (for
                  existing <- repos.findOpenInvitationByEmail(
                    workspaceId,
                    email
                  )
                  invitation <- existing match
                    case Some(_) =>
                      Left(
                        ApiError.Conflict(
                          "An open invitation for this email already exists."
                        )
                      )
                        .pure[IO]
                    case None =>
                      (repos.createInvitation(
                        workspaceId,
                        email,
                        role.label,
                        user.id
                      ) <* repos.audit(
                        workspaceId,
                        user.id,
                        "invitation.created",
                        "invitation",
                        email
                      )).map(Right(_))
                yield invitation).map {
                  case Left(error)       => Left(failed(error))
                  case Right(invitation) => Right(invitationDto(invitation))
                }
      }

  private def revokeInvitationLogic(
      authorization: Option[String],
      workspaceId: UUID,
      invitationId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.ManageMembers)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          (for
            revoked <- repos.revokeInvitation(workspaceId, invitationId)
            _ <- revoked.traverse(_ =>
              repos.audit(
                workspaceId,
                user.id,
                "invitation.revoked",
                "invitation",
                invitationId.toString
              )
            )
          yield revoked.map(_ => ())).map {
            case None     => Left(failed(ApiError.NotFound("invitation")))
            case Some(()) => Right(())
          }
      }

  private def acceptInvitationLogic(
      authorization: Option[String],
      token: UUID
  ): IO[Either[(SttpStatus, Envelope), WorkspaceDto]] =
    verifyBearer(authorization).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(user)    =>
        (for
          membership <- repos.acceptInvitation(token, user.id)
          dto <- membership match
            case Some(m) =>
              repos.findById(m.workspaceId).map(_.map(w => toDto(w, m.role)))
            case None => IO.pure(None)
        yield dto).map {
          case None      => Left(failed(ApiError.NotFound("invitation")))
          case Some(dto) => Right(dto)
        }
    }

  // -- assembly -------------------------------------------------------------
  // Multi-input endpoints: serverLogic receives the input tuple in order.

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      listWorkspaces.serverLogic(auth => listWorkspacesLogic(auth)),
      createWorkspace.serverLogic((auth, req) =>
        createWorkspaceLogic(auth, req)
      ),
      // Path inputs precede the header input, matching endpoint declaration order.
      getWorkspace.serverLogic((workspaceId, auth) =>
        getWorkspaceLogic(auth, workspaceId)
      ),
      updateWorkspace.serverLogic((workspaceId, auth, req) =>
        updateWorkspaceLogic(auth, workspaceId, req)
      ),
      listMembers.serverLogic((workspaceId, auth) =>
        listMembersLogic(auth, workspaceId)
      ),
      updateMember.serverLogic((workspaceId, userId, auth, req) =>
        updateMemberLogic(auth, workspaceId, userId, req)
      ),
      removeMember.serverLogic((workspaceId, userId, auth) =>
        removeMemberLogic(auth, workspaceId, userId)
      ),
      listInvitations.serverLogic((workspaceId, auth) =>
        listInvitationsLogic(auth, workspaceId)
      ),
      createInvitation.serverLogic((workspaceId, auth, req) =>
        createInvitationLogic(auth, workspaceId, req)
      ),
      revokeInvitation.serverLogic((workspaceId, invitationId, auth) =>
        revokeInvitationLogic(auth, workspaceId, invitationId)
      ),
      acceptInvitation.serverLogic((token, auth) =>
        acceptInvitationLogic(auth, token)
      )
    )
  )
