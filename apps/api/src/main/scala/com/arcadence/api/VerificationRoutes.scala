package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.email.verification.{
  EmailVerificationProvider,
  VerificationStatus
}
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.time.OffsetDateTime
import java.util.UUID

object VerificationDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class VerifyResultDto(
      contactId: UUID,
      email: String,
      status: String,
      provider: String,
      details: String,
      suppressed: Boolean
  )
  object VerifyResultDto:
    given Encoder[VerifyResultDto] = deriveEncoder
    given Decoder[VerifyResultDto] = deriveDecoder
    given Schema[VerifyResultDto] = Schema.derived

  final case class VerifyBatchRequest(contactIds: List[UUID])
  object VerifyBatchRequest:
    given Encoder[VerifyBatchRequest] = deriveEncoder
    given Decoder[VerifyBatchRequest] = deriveDecoder
    given Schema[VerifyBatchRequest] = Schema.derived

  final case class VerifyBatchResult(results: List[VerifyResultDto])
  object VerifyBatchResult:
    given Encoder[VerifyBatchResult] = deriveEncoder
    given Decoder[VerifyBatchResult] = deriveDecoder
    given Schema[VerifyBatchResult] = Schema.derived

  final case class SuppressionDto(
      id: UUID,
      email: String,
      contactId: Option[UUID],
      reason: String,
      source: String,
      createdAt: OffsetDateTime
  )
  object SuppressionDto:
    given Encoder[SuppressionDto] = deriveEncoder
    given Decoder[SuppressionDto] = deriveDecoder
    given Schema[SuppressionDto] = Schema.derived

  final case class CreateSuppressionRequest(
      email: Option[String] = None,
      contactId: Option[UUID] = None,
      reason: String
  )
  object CreateSuppressionRequest:
    given Encoder[CreateSuppressionRequest] = deriveEncoder
    given Decoder[CreateSuppressionRequest] = deriveDecoder
    given Schema[CreateSuppressionRequest] = Schema.derived

/** Verification + suppression API (Phase 3.3).
  *
  * Verification: manual (single/batch) via the provider abstraction; invalid
  * results AUTO-suppress (reason=invalid, source=automatic). Syntax+MX alone
  * never yields verified (ADR-0024).
  *
  * Suppression: list/add (edit_content); delete = REACTIVATION, restricted to
  * manage_members (admin+) and audited — unsubscribe/complaint suppressions
  * cannot be removed at all (compliance).
  */
final class VerificationRoutes(
    authenticator: Authenticator,
    repos: LeadsRepos,
    authorizer: Authorizer,
    provider: EmailVerificationProvider
):
  import VerificationDtos.*

  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

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
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

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

  // -- endpoints ---------------------------------------------------------------

  private val verifyContact = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "contacts" / path[UUID]("contactId") / "verify"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[VerifyResultDto])
    .errorOut(errorOut)

  private val verifyBatch = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "contacts" / "verify-batch"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[VerifyBatchRequest])
    .out(jsonBody[VerifyBatchResult])
    .errorOut(errorOut)

  private val listSuppressions = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "suppressions"
    )
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("q"))
    .out(jsonBody[List[SuppressionDto]])
    .errorOut(errorOut)

  private val createSuppression = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "suppressions"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CreateSuppressionRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[SuppressionDto])
    .errorOut(errorOut)

  private val deleteSuppression = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "suppressions" / path[UUID]("suppressionId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  // -- logic -------------------------------------------------------------------

  /** Runs the provider for one workspace-scoped contact and persists the result
    * + provenance. Invalid results auto-suppress.
    */
  private def verifyOne(
      workspaceId: UUID,
      actor: UUID,
      contactId: UUID
  ): IO[Either[(SttpStatus, Envelope), VerifyResultDto]] =
    repos.findContact(workspaceId, contactId).flatMap {
      case None          => IO.pure(Left(failed(ApiError.NotFound("contact"))))
      case Some(contact) =>
        provider.verify(contact.email).flatMap { outcome =>
          val statusLabel = outcome.status match
            case VerificationStatus.Verified => "verified"
            case VerificationStatus.Risky    => "risky"
            case VerificationStatus.Invalid  => "invalid"
            case VerificationStatus.Unknown  => "unknown"
          for
            _ <- repos.applyVerification(
              workspaceId,
              contactId,
              statusLabel,
              outcome.provider,
              outcome.checkedAt
            )
            _ <- repos.recordProvenance(
              workspaceId,
              contactId,
              "verification",
              None,
              Json.obj(
                "status" -> Json.fromString(statusLabel),
                "details" -> Json.fromString(outcome.details)
              )
            )
            suppressed <-
              if outcome.status == VerificationStatus.Invalid then
                repos
                  .addSuppression(
                    workspaceId,
                    contact.email,
                    Some(contactId),
                    "invalid",
                    "automatic",
                    actor
                  )
                  .flatMap { _ =>
                    // invalid -> contact leaves the active pool
                    repos.setContactState(workspaceId, contactId, "bounced")
                  }
                  .map(_ => true)
              else IO.pure(false)
            _ <- repos.audit(
              workspaceId,
              actor,
              "contact.verified",
              "contact",
              contactId.toString,
              Json.obj("status" -> Json.fromString(statusLabel))
            )
          yield Right(
            VerifyResultDto(
              contactId,
              contact.email,
              statusLabel,
              outcome.provider,
              outcome.details,
              suppressed
            )
          )
        }
    }

  private def verifyContactLogic(
      authorization: Option[String],
      workspaceId: UUID,
      contactId: UUID
  ): IO[Either[(SttpStatus, Envelope), VerifyResultDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) => verifyOne(workspaceId, user.id, contactId)
      }

  private val MaxBatchVerify = 25

  private def verifyBatchLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: VerifyBatchRequest
  ): IO[Either[(SttpStatus, Envelope), VerifyBatchResult]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          if request.contactIds.isEmpty then
            IO.pure(
              Left(failed(ApiError.Invalid("Provide at least one contact id.")))
            )
          else if request.contactIds.size > MaxBatchVerify then
            IO.pure(
              Left(
                failed(
                  ApiError.Invalid(
                    s"Batch verification is limited to $MaxBatchVerify contacts."
                  )
                )
              )
            )
          else
            request.contactIds
              .traverse(id => verifyOne(workspaceId, user.id, id))
              .map { results =>
                Right(VerifyBatchResult(results.collect { case Right(r) => r }))
              }
      }

  private def suppressionDto(s: repos.SuppressionView): SuppressionDto =
    SuppressionDto(s.id, s.email, s.contactId, s.reason, s.source, s.createdAt)

  private def listSuppressionsLogic(
      authorization: Option[String],
      workspaceId: UUID,
      q: Option[String]
  ): IO[Either[(SttpStatus, Envelope), List[SuppressionDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos
            .listSuppressions(workspaceId, q.map(_.trim).filter(_.nonEmpty))
            .map(ss => Right(ss.map(suppressionDto)))
      }

  private val SuppressionReasons =
    Set("bounce", "complaint", "unsubscribe", "manual", "invalid")

  private def createSuppressionLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: CreateSuppressionRequest
  ): IO[Either[(SttpStatus, Envelope), SuppressionDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val reason = request.reason.trim.toLowerCase
          if !SuppressionReasons.contains(reason) then
            IO.pure(
              Left(
                failed(
                  ApiError.Invalid(
                    s"Reason must be one of: ${SuppressionReasons.mkString(", ")}."
                  )
                )
              )
            )
          else
            val emailOpt: Option[String] = request.email.map(_.trim.toLowerCase)
            emailOpt match
              case Some(email) if email.nonEmpty =>
                if !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") then
                  IO.pure(
                    Left(
                      failed(ApiError.Invalid("Enter a valid email address."))
                    )
                  )
                else
                  repos.findContactByEmail(workspaceId, email).flatMap {
                    contact =>
                      val contactId =
                        contact.map(_.id).orElse(request.contactId)
                      repos
                        .addSuppression(
                          workspaceId,
                          email,
                          contactId,
                          reason,
                          "manual",
                          user.id
                        )
                        .flatMap { s =>
                          // suppressed contacts leave the active pool
                          contactId.traverse_(cid =>
                            repos.setContactState(workspaceId, cid, "bounced")
                          )
                          repos
                            .audit(
                              workspaceId,
                              user.id,
                              "suppression.created",
                              "suppression",
                              s.id.toString,
                              Json.obj(
                                "email" -> Json.fromString(email),
                                "reason" -> Json.fromString(reason)
                              )
                            )
                            .as(Right(suppressionDto(s)))
                        }
                  }
              case _ =>
                request.contactId match
                  case Some(cid) =>
                    repos.findContact(workspaceId, cid).flatMap {
                      case None =>
                        IO.pure(Left(failed(ApiError.NotFound("contact"))))
                      case Some(contact) =>
                        repos
                          .addSuppression(
                            workspaceId,
                            contact.email,
                            Some(cid),
                            reason,
                            "manual",
                            user.id
                          )
                          .flatMap { s =>
                            repos.setContactState(workspaceId, cid, "bounced")
                            repos
                              .audit(
                                workspaceId,
                                user.id,
                                "suppression.created",
                                "suppression",
                                s.id.toString,
                                Json.obj(
                                  "email" -> Json.fromString(contact.email),
                                  "reason" -> Json.fromString(reason)
                                )
                              )
                              .as(Right(suppressionDto(s)))
                          }
                    }
                  case None =>
                    IO.pure(
                      Left(
                        failed(
                          ApiError.Invalid("Provide an email or contact id.")
                        )
                      )
                    )
      }

  /** Reactivation guard: admin+ only, audited, and NEVER for unsubscribe /
    * complaint suppressions (compliance-critical).
    */
  private def deleteSuppressionLogic(
      authorization: Option[String],
      workspaceId: UUID,
      suppressionId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.ManageMembers)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          repos.findSuppression(workspaceId, suppressionId).flatMap {
            case None => IO.pure(Left(failed(ApiError.NotFound("suppression"))))
            case Some(suppression) =>
              if suppression.reason == "unsubscribe" || suppression.reason == "complaint"
              then
                IO.pure(
                  Left(
                    failed(
                      ApiError.Conflict(
                        s"${suppression.reason} suppressions cannot be removed — the contact must stay suppressed."
                      )
                    )
                  )
                )
              else
                repos.deleteSuppression(workspaceId, suppressionId).flatMap {
                  deleted =>
                    if !deleted then
                      IO.pure(Left(failed(ApiError.NotFound("suppression"))))
                    else
                      val reactivate = suppression.contactId match
                        case Some(cid) =>
                          repos.reactivateContact(workspaceId, cid).void
                        case None => IO.unit
                      reactivate *>
                        repos
                          .audit(
                            workspaceId,
                            user.id,
                            "suppression.removed",
                            "suppression",
                            suppressionId.toString,
                            Json.obj(
                              "email" -> Json.fromString(suppression.email),
                              "reason" -> Json.fromString(suppression.reason)
                            )
                          )
                          .as(Right(()))
                }
          }
      }

  // -- assembly -------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      verifyContact.serverLogic((workspaceId, contactId, auth) =>
        verifyContactLogic(auth, workspaceId, contactId)
      ),
      verifyBatch.serverLogic((workspaceId, auth, req) =>
        verifyBatchLogic(auth, workspaceId, req)
      ),
      listSuppressions.serverLogic((workspaceId, auth, q) =>
        listSuppressionsLogic(auth, workspaceId, q)
      ),
      createSuppression.serverLogic((workspaceId, auth, req) =>
        createSuppressionLogic(auth, workspaceId, req)
      ),
      deleteSuppression.serverLogic((workspaceId, suppressionId, auth) =>
        deleteSuppressionLogic(auth, workspaceId, suppressionId)
      )
    )
  )

object VerificationRoutes:
  val SuppressionReasons =
    Set("bounce", "complaint", "unsubscribe", "manual", "invalid")
