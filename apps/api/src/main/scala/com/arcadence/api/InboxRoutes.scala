package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.ProviderRegistry
import com.arcadence.core.jobs.JobQueue
import com.arcadence.api.Permissions.Permission
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.time.{OffsetDateTime, ZonedDateTime}
import java.util.UUID

object InboxDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class ThreadDto(
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
  object ThreadDto:
    given Encoder[ThreadDto] = deriveEncoder
    given Decoder[ThreadDto] = deriveDecoder
    given Schema[ThreadDto] = Schema.derived

  final case class ThreadPage(
      items: List[ThreadDto],
      nextCursor: Option[String]
  )
  object ThreadPage:
    given Encoder[ThreadPage] = deriveEncoder
    given Decoder[ThreadPage] = deriveDecoder
    given Schema[ThreadPage] = Schema.derived

  final case class MessageDto(
      id: UUID,
      direction: String,
      fromAddress: String,
      subject: String,
      bodyText: String,
      bodyHtml: String,
      occurredAt: OffsetDateTime
  )
  object MessageDto:
    given Encoder[MessageDto] = deriveEncoder
    given Decoder[MessageDto] = deriveDecoder
    given Schema[MessageDto] = Schema.derived

  final case class ThreadDetail(
      thread: ThreadDto,
      messages: List[MessageDto],
      notes: List[NoteDto]
  )
  object ThreadDetail:
    given Encoder[ThreadDetail] = deriveEncoder
    given Decoder[ThreadDetail] = deriveDecoder
    given Schema[ThreadDetail] = Schema.derived

  final case class ReplyRequest(bodyText: String)
  object ReplyRequest:
    given Encoder[ReplyRequest] = deriveEncoder
    given Decoder[ReplyRequest] = deriveDecoder
    given Schema[ReplyRequest] = Schema.derived

  final case class AssignRequest(assignedTo: Option[UUID])
  object AssignRequest:
    given Encoder[AssignRequest] = deriveEncoder
    given Decoder[AssignRequest] = deriveDecoder
    given Schema[AssignRequest] = Schema.derived

  final case class NoteRequest(body: String)
  object NoteRequest:
    given Encoder[NoteRequest] = deriveEncoder
    given Decoder[NoteRequest] = deriveDecoder
    given Schema[NoteRequest] = Schema.derived

  final case class NoteDto(
      id: UUID,
      authorName: Option[String],
      body: String,
      createdAt: OffsetDateTime
  )
  object NoteDto:
    given Encoder[NoteDto] = deriveEncoder
    given Decoder[NoteDto] = deriveDecoder
    given Schema[NoteDto] = Schema.derived

/** Unibox API (Phases 6.3/6.4): thread list with filters, thread detail, real
  * replies through the owning provider adapter (threading preserved via
  * submitReply when the provider thread is known), read/unread, archive,
  * assignment, internal notes — with workspace isolation everywhere.
  */
final class InboxRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO],
    sync: InboxSyncService,
    registry: Option[ProviderRegistry],
    emailAccounts: EmailAccountRepos
):

  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def verifyBearer(
      a: Option[String]
  ): IO[Either[(SttpStatus, Envelope), AuthenticatedUser]] =
    a match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator
          .verify(token.stripPrefix("Bearer ").trim)
          .map(
            _.leftMap(e =>
              (SttpStatus.Unauthorized, Envelope(e.code, e.message))
            )
          )
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private def requirePermission(
      authorization: Option[String],
      workspaceId: UUID,
      permission: Permission
  ): IO[Either[(SttpStatus, Envelope), AuthenticatedUser]] =
    verifyBearer(authorization).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(user)    =>
        authorizer.requirePermission(user, workspaceId, permission).map {
          case Left(error) => Left(failed(error))
          case Right(_)    => Right(user)
        }
    }

  private val authorizer = new Authorizer(xa)

  // -- endpoints ------------------------------------------------------------------

  private val listThreads = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "inbox")
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("classification"))
    .in(query[Option[String]]("status"))
    .in(query[Option[Boolean]]("unread"))
    .in(query[Option[UUID]]("assignedTo"))
    .in(query[Option[String]]("q"))
    .out(jsonBody[InboxDtos.ThreadPage])
    .errorOut(errorOut)

  private val syncInbox = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "inbox" / "sync"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[com.arcadence.api.InboxDtos.ThreadPage])
    .errorOut(errorOut)

  private val getThread =
    endpoint.get
      .in(
        "api" / "v1" / "workspaces" / path[UUID](
          "workspaceId"
        ) / "inbox" / path[UUID]("threadId")
      )
      .in(header[Option[String]]("Authorization"))
      .out(jsonBody[InboxDtos.ThreadDetail])
      .errorOut(errorOut)

  private val replyThread =
    endpoint.post
      .in(
        "api" / "v1" / "workspaces" / path[UUID](
          "workspaceId"
        ) / "inbox" / path[UUID]("threadId") / "reply"
      )
      .in(header[Option[String]]("Authorization"))
      .in(jsonBody[InboxDtos.ReplyRequest])
      .out(statusCode(SttpStatus.Created))
      .out(jsonBody[com.arcadence.api.InboxDtos.MessageDto])
      .errorOut(errorOut)

  private val patchThread =
    endpoint.patch
      .in(
        "api" / "v1" / "workspaces" / path[UUID](
          "workspaceId"
        ) / "inbox" / path[UUID]("threadId")
      )
      .in(header[Option[String]]("Authorization"))
      .in(jsonBody[InboxDtos.AssignRequest])
      .out(statusCode(SttpStatus.NoContent))
      .errorOut(errorOut)

  private val addNote =
    endpoint.post
      .in(
        "api" / "v1" / "workspaces" / path[UUID](
          "workspaceId"
        ) / "inbox" / path[UUID]("threadId") / "notes"
      )
      .in(header[Option[String]]("Authorization"))
      .in(jsonBody[InboxDtos.NoteRequest])
      .out(statusCode(SttpStatus.Created))
      .errorOut(errorOut)

  // -- logic wiring -----------------------------------------------------------------

  private def listLogic(
      auth: Option[String],
      ws: UUID,
      cls: Option[String],
      status: Option[String],
      unread: Option[Boolean],
      assigned: Option[UUID],
      q: Option[String]
  ): IO[Either[(SttpStatus, Envelope), InboxDtos.ThreadPage]] =
    requirePermission(auth, ws, Permission.ViewWorkspace).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        InboxLogic.list(xa, ws, cls, status, unread, assigned, q).map(Right(_))
    }

  private def syncLogic(
      auth: Option[String],
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), InboxDtos.ThreadPage]] =
    requirePermission(auth, ws, Permission.EditContent).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        sync.syncWorkspace(ws) *> InboxLogic
          .list(xa, ws, None, None, None, None, None)
          .map(Right(_))
    }

  private def envStatus(env: Envelope): SttpStatus =
    env.code match
      case "not_found"         => SttpStatus.NotFound
      case "validation_failed" => SttpStatus.UnprocessableEntity
      case "provider_error"    => SttpStatus.BadGateway
      case _                   => SttpStatus.InternalServerError

  private def getLogic(
      auth: Option[String],
      ws: UUID,
      threadId: UUID
  ): IO[Either[(SttpStatus, Envelope), InboxDtos.ThreadDetail]] =
    requirePermission(auth, ws, Permission.ViewWorkspace).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        InboxLogic.detail(xa, ws, threadId).map {
          case None      => Left(failed(ApiError.NotFound("conversation")))
          case Some(det) => Right(det)
        }
    }

  private def replyLogic(
      auth: Option[String],
      ws: UUID,
      threadId: UUID,
      req: InboxDtos.ReplyRequest
  ): IO[Either[(SttpStatus, Envelope), InboxDtos.MessageDto]] =
    requirePermission(auth, ws, Permission.EditContent).flatMap {
      case Left(env)   => IO.pure(Left(env))
      case Right(user) =>
        InboxLogic
          .reply(
            xa,
            ws,
            threadId,
            user.id,
            req.bodyText,
            registry,
            emailAccounts
          )
          .map {
            case Left(env) => Left((envStatus(env), env))
            case Right(m)  => Right(m)
          }
    }

  private def patchLogic(
      auth: Option[String],
      ws: UUID,
      threadId: UUID,
      req: InboxDtos.AssignRequest
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(auth, ws, Permission.EditContent).flatMap {
      case Left(env)   => IO.pure(Left(env))
      case Right(user) =>
        InboxLogic.patch(xa, ws, threadId, req.assignedTo, user.id).map {
          case true  => Right(())
          case false => Left(failed(ApiError.NotFound("conversation")))
        }
    }

  private def noteLogic(
      auth: Option[String],
      ws: UUID,
      threadId: UUID,
      req: InboxDtos.NoteRequest
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(auth, ws, Permission.EditContent).flatMap {
      case Left(env)   => IO.pure(Left(env))
      case Right(user) =>
        InboxLogic.addNote(xa, ws, threadId, user.id, req.body.trim).map {
          case true  => Right(())
          case false => Left(failed(ApiError.NotFound("conversation")))
        }
    }

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      listThreads.serverLogic((ws, auth, cls, status, unread, assigned, q) =>
        listLogic(auth, ws, cls, status, unread, assigned, q)
      ),
      syncInbox.serverLogic((ws, auth) => syncLogic(auth, ws)),
      getThread.serverLogic((ws, threadId, auth) =>
        getLogic(auth, ws, threadId)
      ),
      replyThread.serverLogic((ws, threadId, auth, req) =>
        replyLogic(auth, ws, threadId, req)
      ),
      patchThread.serverLogic((ws, threadId, auth, req) =>
        patchLogic(auth, ws, threadId, req)
      ),
      addNote.serverLogic((ws, threadId, auth, req) =>
        noteLogic(auth, ws, threadId, req)
      )
    )
  )
