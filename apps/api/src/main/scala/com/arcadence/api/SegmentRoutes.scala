package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.LeadDtos.ContactDto
import com.arcadence.api.Permissions.Permission
import doobie.Fragment
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

object SegmentDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class SegmentDto(
      id: UUID,
      name: String,
      filters: Json,
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime
  )
  object SegmentDto:
    given Encoder[SegmentDto] = deriveEncoder
    given Decoder[SegmentDto] = deriveDecoder
    given Schema[SegmentDto] = Schema.derived

  final case class CreateSegmentRequest(name: String, filters: Json)
  object CreateSegmentRequest:
    given Encoder[CreateSegmentRequest] = deriveEncoder
    given Decoder[CreateSegmentRequest] = deriveDecoder
    given Schema[CreateSegmentRequest] = Schema.derived

  final case class UpdateSegmentRequest(name: Option[String] = None, filters: Option[Json] = None)
  object UpdateSegmentRequest:
    given Encoder[UpdateSegmentRequest] = deriveEncoder
    given Decoder[UpdateSegmentRequest] = deriveDecoder
    given Schema[UpdateSegmentRequest] = Schema.derived

  final case class SegmentPreview(count: Long, sample: List[ContactDto])
  object SegmentPreview:
    given Encoder[SegmentPreview] = deriveEncoder
    given Decoder[SegmentPreview] = deriveDecoder
    given Schema[SegmentPreview] = Schema.derived

  final case class BulkTagRequest(segmentId: UUID, tagId: UUID)
  object BulkTagRequest:
    given Encoder[BulkTagRequest] = deriveEncoder
    given Decoder[BulkTagRequest] = deriveDecoder
    given Schema[BulkTagRequest] = Schema.derived

  final case class BulkTagResult(tagged: Int)
  object BulkTagResult:
    given Encoder[BulkTagResult] = deriveEncoder
    given Decoder[BulkTagResult] = deriveDecoder
    given Schema[BulkTagResult] = Schema.derived

  final case class MergeContactRequest(sourceContactId: UUID)
  object MergeContactRequest:
    given Encoder[MergeContactRequest] = deriveEncoder
    given Decoder[MergeContactRequest] = deriveDecoder
    given Schema[MergeContactRequest] = Schema.derived

  final case class MergeContactResult(mergedId: UUID, keptId: UUID, filledFields: Int, movedTags: Int, movedListMemberships: Int)
  object MergeContactResult:
    given Encoder[MergeContactResult] = deriveEncoder
    given Decoder[MergeContactResult] = deriveDecoder
    given Schema[MergeContactResult] = Schema.derived

  final case class DataQualityDto(
      total: Long,
      valid: Long,
      risky: Long,
      invalid: Long,
      unknown: Long,
      active: Long,
      unsubscribed: Long,
      bounced: Long,
      complained: Long,
      missingName: Long,
      missingCompany: Long
  )
  object DataQualityDto:
    given Encoder[DataQualityDto] = deriveEncoder
    given Decoder[DataQualityDto] = deriveDecoder
    given Schema[DataQualityDto] = Schema.derived

/** Segmentation API (Phase 3.4): saved segments with AND/OR filter trees
  * (whitelisted fields, parameter-bound values), preview counts, bulk tagging,
  * CSV export, contact merge and the data-quality dashboard.
  */
final class SegmentRoutes(
    authenticator: Authenticator,
    repos: SegmentRepos,
    leadsRepos: LeadsRepos,
    authorizer: Authorizer
):
  import leadsRepos.ContactView
  import SegmentDtos.*

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
          .map(_.leftMap(e => (SttpStatus.Unauthorized, Envelope(e.code, e.message))))
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

  private def previewDto(c: repos.leadsRepos.ContactView): ContactDto =
    ContactDto(
      id = c.id,
      companyId = c.companyId,
      companyName = c.companyName,
      email = c.email,
      emailDisplay = c.emailDisplay,
      firstName = c.firstName,
      lastName = c.lastName,
      jobTitle = c.jobTitle,
      website = c.website,
      phone = c.phone,
      region = c.region,
      timezone = c.timezone,
      notes = c.notes,
      customFields = c.customFields,
      verificationStatus = c.verificationStatus,
      verificationCheckedAt = c.verificationCheckedAt,
      verificationProvider = c.verificationProvider,
      state = c.state,
      createdAt = c.createdAt,
      updatedAt = c.updatedAt
    )

  private def segmentDto(s: repos.SegmentView): SegmentDto =
    SegmentDto(s.id, s.name, s.filters, s.createdAt, s.updatedAt)

  private val listSegments = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "segments")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[SegmentDto]])
    .errorOut(errorOut)

  private val createSegment = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "segments")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CreateSegmentRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[SegmentDto])
    .errorOut(errorOut)

  private val getSegment = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "segments" / path[UUID]("segmentId"))
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[SegmentDto])
    .errorOut(errorOut)

  private val updateSegment = endpoint.patch
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "segments" / path[UUID]("segmentId"))
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[UpdateSegmentRequest])
    .out(jsonBody[SegmentDto])
    .errorOut(errorOut)

  private val deleteSegment = endpoint.delete
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "segments" / path[UUID]("segmentId"))
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val previewSegment = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "segments" / path[UUID]("segmentId") / "preview")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[SegmentPreview])
    .errorOut(errorOut)

  private val exportSegment = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "segments" / path[UUID]("segmentId") / "export")
    .in(header[Option[String]]("Authorization"))
    .out(byteArrayBody)
    .errorOut(errorOut)

  private val bulkTag = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "segments" / path[UUID]("segmentId") / "bulk-tag")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[BulkTagRequest])
    .out(jsonBody[BulkTagResult])
    .errorOut(errorOut)

  private val dataQuality = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "contacts" / "data-quality")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[DataQualityDto])
    .errorOut(errorOut)

  private val mergeContact = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "contacts" / path[UUID]("contactId") / "merge")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[MergeContactRequest])
    .out(jsonBody[MergeContactResult])
    .errorOut(errorOut)

  // -- logic --------------------------------------------------------------------

  private def segmentWhere(workspaceId: UUID, segmentId: UUID): IO[Either[(SttpStatus, Envelope), Fragment]] =
    repos.findSegment(workspaceId, segmentId).map {
      case None => Left(failed(ApiError.NotFound("segment")))
      case Some(segment) =>
        repos.filterFragment(workspaceId, segment.filters) match
          case Left(error) => Left(failed(ApiError.Invalid(error)))
          case Right(where) => Right(where)
    }

  private def listSegmentsLogic(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[SegmentDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(_)       => repos.listSegments(workspaceId).map(ss => Right(ss.map(segmentDto)))
    }

  private def createSegmentLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: CreateSegmentRequest
  ): IO[Either[(SttpStatus, Envelope), SegmentDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent).flatMap {
      case Left(envelope)   => IO.pure(Left(envelope))
      case Right((user, _)) =>
        val name = request.name.trim
        if name.isEmpty || name.length > 120 then
          IO.pure(Left(failed(ApiError.Invalid("Segment name must be 1–120 characters."))))
        else
          // validate the filter tree by building it once
          repos.filterFragment(workspaceId, request.filters) match
            case Left(error) => IO.pure(Left(failed(ApiError.Invalid(error))))
            case Right(_)    =>
              repos.createSegment(workspaceId, user.id, name, request.filters).map {
                case None    => Left(failed(ApiError.Conflict("A segment with this name already exists.")))
                case Some(s) => Right(segmentDto(s))
              }
    }

  private def getSegmentLogic(
      authorization: Option[String],
      workspaceId: UUID,
      segmentId: UUID
  ): IO[Either[(SttpStatus, Envelope), SegmentDto]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(_)       =>
        repos.findSegment(workspaceId, segmentId).map {
          case None    => Left(failed(ApiError.NotFound("segment")))
          case Some(s) => Right(segmentDto(s))
        }
    }

  private def updateSegmentLogic(
      authorization: Option[String],
      workspaceId: UUID,
      segmentId: UUID,
      request: UpdateSegmentRequest
  ): IO[Either[(SttpStatus, Envelope), SegmentDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(_)       =>
        val nameOk = request.name.forall(n => n.trim.nonEmpty && n.trim.length <= 120)
        if !nameOk then IO.pure(Left(failed(ApiError.Invalid("Segment name must be 1–120 characters."))))
        else
          val filtersValid: IO[Either[(SttpStatus, Envelope), Unit]] =
            request.filters match
              case Some(f) =>
                repos.filterFragment(workspaceId, f) match
                  case Left(error) => IO.pure(Left(failed(ApiError.Invalid(error))))
                  case Right(_)    => IO.pure(Right(()))
              case None => IO.pure(Right(()))
          filtersValid.flatMap {
            case Left(envelope) => IO.pure(Left(envelope))
            case Right(())      =>
              repos.updateSegment(workspaceId, segmentId, request.name.map(_.trim), request.filters).flatMap { updated =>
                if updated == 0 then IO.pure(Left(failed(ApiError.NotFound("segment"))))
                else repos.findSegment(workspaceId, segmentId).map {
                  case None    => Left(failed(ApiError.NotFound("segment")))
                  case Some(s) => Right(segmentDto(s))
                }
              }
          }
    }

  private def deleteSegmentLogic(
      authorization: Option[String],
      workspaceId: UUID,
      segmentId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.EditContent).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(_)       =>
        repos.deleteSegment(workspaceId, segmentId).map {
          case false => Left(failed(ApiError.NotFound("segment")))
          case true  => Right(())
        }
    }

  private def previewLogic(
      authorization: Option[String],
      workspaceId: UUID,
      segmentId: UUID
  ): IO[Either[(SttpStatus, Envelope), SegmentPreview]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(_)       =>
        segmentWhere(workspaceId, segmentId).flatMap {
          case Left(envelope) => IO.pure(Left(envelope))
          case Right(where)   =>
            for
              count <- repos.contactCount(workspaceId, where)
              sample <- repos.contactPage(workspaceId, where, 10)
            yield Right(
              SegmentPreview(count, sample.map(c => previewDto(c)))
            )
        }
    }

  private def exportLogic(
      authorization: Option[String],
      workspaceId: UUID,
      segmentId: UUID
  ): IO[Either[(SttpStatus, Envelope), Array[Byte]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(_)       =>
        segmentWhere(workspaceId, segmentId).flatMap {
          case Left(envelope) => IO.pure(Left(envelope))
          case Right(where)   =>
            repos.contactPage(workspaceId, where, 50_000).map { contacts =>
              val header = List("email", "first_name", "last_name", "job_title", "company", "phone", "region", "timezone", "state", "verification_status")
              val rows = contacts.map { c =>
                List(
                  c.emailDisplay.getOrElse(c.email),
                  c.firstName.getOrElse(""),
                  c.lastName.getOrElse(""),
                  c.jobTitle.getOrElse(""),
                  c.companyName.getOrElse(""),
                  c.phone.getOrElse(""),
                  c.region.getOrElse(""),
                  c.timezone.getOrElse(""),
                  c.state,
                  c.verificationStatus
                )
              }
              val csv = CsvReader.renderCsv(header :: rows)
              Right(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            }
        }
    }

  private def bulkTagLogic(
      authorization: Option[String],
      workspaceId: UUID,
      segmentId: UUID,
      request: BulkTagRequest
  ): IO[Either[(SttpStatus, Envelope), BulkTagResult]] =
    requirePermission(authorization, workspaceId, Permission.EditContent).flatMap {
      case Left(envelope)   => IO.pure(Left(envelope))
      case Right((user, _)) =>
        segmentWhere(workspaceId, segmentId).flatMap {
          case Left(envelope) => IO.pure(Left(envelope))
          case Right(where)   =>
            for
              tagged <- leadsRepos.bulkTagByFragment(workspaceId, request.tagId, where)
              _ <- leadsRepos.audit(workspaceId, user.id, "segment.bulk_tagged", "segment", segmentId.toString,
                    Json.obj("tagId" -> Json.fromString(request.tagId.toString), "tagged" -> Json.fromInt(tagged)))
            yield Right(BulkTagResult(tagged))
        }
    }

  private def dataQualityLogic(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), DataQualityDto]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(_)       =>
          leadsRepos.dataQuality(workspaceId).flatMap { json =>
            val parsed = for
              total <- json.hcursor.get[Long]("total").toOption
              valid <- json.hcursor.get[Long]("valid").toOption
              risky <- json.hcursor.get[Long]("risky").toOption
              invalid <- json.hcursor.get[Long]("invalid").toOption
              unknown <- json.hcursor.get[Long]("unknown").toOption
              active <- json.hcursor.get[Long]("active").toOption
              unsubscribed <- json.hcursor.get[Long]("unsubscribed").toOption
              bounced <- json.hcursor.get[Long]("bounced").toOption
              complained <- json.hcursor.get[Long]("complained").toOption
              missingName <- json.hcursor.get[Long]("missingName").toOption
              missingCompany <- json.hcursor.get[Long]("missingCompany").toOption
            yield DataQualityDto(total, valid, risky, invalid, unknown, active, unsubscribed, bounced, complained, missingName, missingCompany)
            parsed match
              case Some(dq) => IO.pure(Right(dq))
              case None     => IO.pure(Left(failed(ApiError.Invalid("Data quality read failed"))))
          }
    }

  private def mergeLogic(
      authorization: Option[String],
      workspaceId: UUID,
      contactId: UUID,
      request: MergeContactRequest
  ): IO[Either[(SttpStatus, Envelope), MergeContactResult]] =
    requirePermission(authorization, workspaceId, Permission.EditContent).flatMap {
      case Left(envelope)   => IO.pure(Left(envelope))
      case Right((user, _)) =>
        if request.sourceContactId == contactId then
          IO.pure(Left(failed(ApiError.Invalid("Cannot merge a contact into itself."))))
        else
          (leadsRepos.findContact(workspaceId, contactId), leadsRepos.findContact(workspaceId, request.sourceContactId)).parTupled.flatMap {
            case (keptOpt, sourceOpt) =>
              (keptOpt, sourceOpt) match
                case (None, _) => IO.pure(Left(failed(ApiError.NotFound("contact"))))
                case (_, None) => IO.pure(Left(failed(ApiError.NotFound("source contact"))))
                case (Some(kept), Some(source)) =>
                  for
                    merge <- leadsRepos.mergeContacts(workspaceId, kept.id, source.id)
                    _ <- leadsRepos.audit(workspaceId, user.id, "contact.merged", "contact", kept.id.toString,
                          Json.obj("sourceId" -> Json.fromString(source.id.toString),
                            "filledFields" -> Json.fromInt(merge._1),
                            "movedTags" -> Json.fromInt(merge._2),
                            "movedListMemberships" -> Json.fromInt(merge._3)))
                  yield Right(
                    MergeContactResult(kept.id, source.id, merge._1, merge._2, merge._3)
                  )
          }
    }

  // -- assembly --------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      listSegments.serverLogic((workspaceId, auth) => listSegmentsLogic(auth, workspaceId)),
      createSegment.serverLogic((workspaceId, auth, req) => createSegmentLogic(auth, workspaceId, req)),
      getSegment.serverLogic((workspaceId, segmentId, auth) => getSegmentLogic(auth, workspaceId, segmentId)),
      updateSegment.serverLogic((workspaceId, segmentId, auth, req) => updateSegmentLogic(auth, workspaceId, segmentId, req)),
      deleteSegment.serverLogic((workspaceId, segmentId, auth) => deleteSegmentLogic(auth, workspaceId, segmentId)),
      previewSegment.serverLogic((workspaceId, segmentId, auth) => previewLogic(auth, workspaceId, segmentId)),
      exportSegment.serverLogic((workspaceId, segmentId, auth) => exportLogic(auth, workspaceId, segmentId)),
      bulkTag.serverLogic((workspaceId, segmentId, auth, req) => bulkTagLogic(auth, workspaceId, segmentId, req)),
      dataQuality.serverLogic((workspaceId, auth) => dataQualityLogic(auth, workspaceId)),
      mergeContact.serverLogic((workspaceId, contactId, auth, req) => mergeLogic(auth, workspaceId, contactId, req))
    )
  )

