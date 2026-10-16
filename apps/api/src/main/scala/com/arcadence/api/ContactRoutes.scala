package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import doobie.Fragment
import doobie.implicits.*
import doobie.postgres.implicits.*
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

import java.time.{OffsetDateTime, ZoneOffset}
import java.util.Base64
import java.util.UUID
import scala.util.Try

object LeadDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class ContactDto(
      id: UUID,
      companyId: Option[UUID],
      companyName: Option[String],
      email: String,
      emailDisplay: Option[String],
      firstName: Option[String],
      lastName: Option[String],
      jobTitle: Option[String],
      website: Option[String],
      phone: Option[String],
      region: Option[String],
      timezone: Option[String],
      notes: Option[String],
      customFields: Json,
      verificationStatus: String,
      verificationCheckedAt: Option[OffsetDateTime],
      verificationProvider: Option[String],
      state: String,
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime
  )
  object ContactDto:
    given Encoder[ContactDto] = deriveEncoder
    given Decoder[ContactDto] = deriveDecoder
    given Schema[ContactDto] = Schema.derived

  final case class ContactPage(
      items: List[ContactDto],
      nextCursor: Option[String],
      total: Long
  )
  object ContactPage:
    given Encoder[ContactPage] = deriveEncoder
    given Decoder[ContactPage] = deriveDecoder
    given Schema[ContactPage] = Schema.derived

  final case class CreateContactRequest(
      email: String,
      emailDisplay: Option[String] = None,
      firstName: Option[String] = None,
      lastName: Option[String] = None,
      jobTitle: Option[String] = None,
      companyId: Option[UUID] = None,
      website: Option[String] = None,
      phone: Option[String] = None,
      region: Option[String] = None,
      timezone: Option[String] = None,
      notes: Option[String] = None,
      customFields: Option[Json] = None
  )
  object CreateContactRequest:
    given Encoder[CreateContactRequest] = deriveEncoder
    given Decoder[CreateContactRequest] = deriveDecoder
    given Schema[CreateContactRequest] = Schema.derived

  final case class UpdateContactRequest(
      emailDisplay: Option[Option[String]] = None,
      firstName: Option[Option[String]] = None,
      lastName: Option[Option[String]] = None,
      jobTitle: Option[Option[String]] = None,
      companyId: Option[Option[UUID]] = None,
      website: Option[Option[String]] = None,
      phone: Option[Option[String]] = None,
      region: Option[Option[String]] = None,
      timezone: Option[Option[String]] = None,
      notes: Option[Option[String]] = None,
      customFields: Option[Json] = None
  )
  object UpdateContactRequest:
    given Encoder[UpdateContactRequest] = deriveEncoder
    given Schema[UpdateContactRequest] = Schema.derived
    given Decoder[UpdateContactRequest] = Decoder.instance { c =>
      def field[A: Decoder](name: String): Decoder.Result[Option[Option[A]]] =
        c.downField(name).focus match
          case None                => Right(None)
          case Some(v) if v.isNull => Right(Some(None))
          case Some(v)             => v.as[A].map(a => Some(Some(a)))
      for
        emailDisplay <- field[String]("emailDisplay")
        firstName <- field[String]("firstName")
        lastName <- field[String]("lastName")
        jobTitle <- field[String]("jobTitle")
        companyId <- field[UUID]("companyId")
        website <- field[String]("website")
        phone <- field[String]("phone")
        region <- field[String]("region")
        timezone <- field[String]("timezone")
        notes <- field[String]("notes")
        rawCustomFields <- field[Json]("customFields")
        customFields = rawCustomFields.map(_.getOrElse(Json.obj()))
      yield UpdateContactRequest(
        emailDisplay,
        firstName,
        lastName,
        jobTitle,
        companyId,
        website,
        phone,
        region,
        timezone,
        notes,
        customFields
      )
    }

  final case class BatchContactItem(
      email: String,
      firstName: Option[String] = None,
      lastName: Option[String] = None,
      jobTitle: Option[String] = None,
      companyId: Option[UUID] = None,
      phone: Option[String] = None,
      region: Option[String] = None,
      timezone: Option[String] = None,
      customFields: Option[Json] = None
  )
  object BatchContactItem:
    given Encoder[BatchContactItem] = deriveEncoder
    given Decoder[BatchContactItem] = deriveDecoder
    given Schema[BatchContactItem] = Schema.derived

  final case class BatchContactsRequest(contacts: List[BatchContactItem])
  object BatchContactsRequest:
    given Encoder[BatchContactsRequest] = deriveEncoder
    given Decoder[BatchContactsRequest] = deriveDecoder
    given Schema[BatchContactsRequest] = Schema.derived

  final case class BatchResultItem(
      email: String,
      outcome: String, // created | updated | skipped
      contactId: Option[UUID] = None
  )
  object BatchResultItem:
    given Encoder[BatchResultItem] = deriveEncoder
    given Decoder[BatchResultItem] = deriveDecoder
    given Schema[BatchResultItem] = Schema.derived

  final case class BatchContactsResult(
      created: Int,
      updated: Int,
      skipped: Int,
      items: List[BatchResultItem]
  )
  object BatchContactsResult:
    given Encoder[BatchContactsResult] = deriveEncoder
    given Decoder[BatchContactsResult] = deriveDecoder
    given Schema[BatchContactsResult] = Schema.derived

  final case class ProvenanceDto(
      id: UUID,
      source: String,
      importJobId: Option[UUID],
      details: Json,
      createdAt: OffsetDateTime
  )
  object ProvenanceDto:
    given Encoder[ProvenanceDto] = deriveEncoder
    given Decoder[ProvenanceDto] = deriveDecoder
    given Schema[ProvenanceDto] = Schema.derived

  // companies
  final case class CompanyDto(
      id: UUID,
      name: String,
      domain: Option[String],
      website: Option[String],
      contactCount: Int,
      createdAt: OffsetDateTime
  )
  object CompanyDto:
    given Encoder[CompanyDto] = deriveEncoder
    given Decoder[CompanyDto] = deriveDecoder
    given Schema[CompanyDto] = Schema.derived

  final case class CreateCompanyRequest(
      name: String,
      domain: Option[String] = None,
      website: Option[String] = None
  )
  object CreateCompanyRequest:
    given Encoder[CreateCompanyRequest] = deriveEncoder
    given Decoder[CreateCompanyRequest] = deriveDecoder
    given Schema[CreateCompanyRequest] = Schema.derived

  final case class UpdateCompanyRequest(
      name: Option[String] = None,
      domain: Option[Option[String]] = None,
      website: Option[Option[String]] = None
  )
  object UpdateCompanyRequest:
    given Encoder[UpdateCompanyRequest] = deriveEncoder
    given Decoder[UpdateCompanyRequest] = deriveDecoder
    given Schema[UpdateCompanyRequest] = Schema.derived

  // lists
  final case class LeadListDto(
      id: UUID,
      name: String,
      description: Option[String],
      memberCount: Int,
      createdAt: OffsetDateTime
  )
  object LeadListDto:
    given Encoder[LeadListDto] = deriveEncoder
    given Decoder[LeadListDto] = deriveDecoder
    given Schema[LeadListDto] = Schema.derived

  final case class CreateListRequest(
      name: String,
      description: Option[String] = None
  )
  object CreateListRequest:
    given Encoder[CreateListRequest] = deriveEncoder
    given Decoder[CreateListRequest] = deriveDecoder
    given Schema[CreateListRequest] = Schema.derived

  final case class UpdateListRequest(
      name: Option[String] = None,
      description: Option[Option[String]] = None
  )
  object UpdateListRequest:
    given Encoder[UpdateListRequest] = deriveEncoder
    given Decoder[UpdateListRequest] = deriveDecoder
    given Schema[UpdateListRequest] = Schema.derived

  final case class AddMembersRequest(contactIds: List[UUID])
  object AddMembersRequest:
    given Encoder[AddMembersRequest] = deriveEncoder
    given Decoder[AddMembersRequest] = deriveDecoder
    given Schema[AddMembersRequest] = Schema.derived

  final case class AddMembersResult(added: Int)
  object AddMembersResult:
    given Encoder[AddMembersResult] = deriveEncoder
    given Decoder[AddMembersResult] = deriveDecoder
    given Schema[AddMembersResult] = Schema.derived

  // tags
  final case class TagDto(
      id: UUID,
      name: String,
      color: Option[String],
      contactCount: Int,
      createdAt: OffsetDateTime
  )
  object TagDto:
    given Encoder[TagDto] = deriveEncoder
    given Decoder[TagDto] = deriveDecoder
    given Schema[TagDto] = Schema.derived

  final case class CreateTagRequest(name: String, color: Option[String] = None)
  object CreateTagRequest:
    given Encoder[CreateTagRequest] = deriveEncoder
    given Decoder[CreateTagRequest] = deriveDecoder
    given Schema[CreateTagRequest] = Schema.derived

  final case class AddTagsRequest(tagIds: List[UUID])
  object AddTagsRequest:
    given Encoder[AddTagsRequest] = deriveEncoder
    given Decoder[AddTagsRequest] = deriveDecoder
    given Schema[AddTagsRequest] = Schema.derived

  // custom fields
  final case class CustomFieldDto(
      id: UUID,
      name: String,
      fieldType: String,
      description: Option[String],
      createdAt: OffsetDateTime
  )
  object CustomFieldDto:
    given Encoder[CustomFieldDto] = deriveEncoder
    given Decoder[CustomFieldDto] = deriveDecoder
    given Schema[CustomFieldDto] = Schema.derived

  final case class CreateCustomFieldRequest(
      name: String,
      fieldType: String = "text",
      description: Option[String] = None
  )
  object CreateCustomFieldRequest:
    given Encoder[CreateCustomFieldRequest] = deriveEncoder
    given Decoder[CreateCustomFieldRequest] = deriveDecoder
    given Schema[CreateCustomFieldRequest] = Schema.derived

  final case class UpdateCustomFieldRequest(
      name: Option[String] = None,
      description: Option[Option[String]] = None
  )
  object UpdateCustomFieldRequest:
    given Encoder[UpdateCustomFieldRequest] = deriveEncoder
    given Decoder[UpdateCustomFieldRequest] = deriveDecoder
    given Schema[UpdateCustomFieldRequest] = Schema.derived

/** Lead management API (Phase 3.1). Reads require view access; writes require
  * edit_content (member and above). Emails are normalized to lowercase on
  * write; the original casing is kept as the display value when provided.
  */
final class ContactRoutes(
    authenticator: Authenticator,
    repos: LeadsRepos,
    authorizer: Authorizer
):
  import LeadDtos.*

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

  // -- helpers ----------------------------------------------------------------

  private val EmailPattern = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"

  private def normalizeEmail(
      raw: String
  ): Either[ApiError, (String, Option[String])] =
    val trimmed = raw.trim
    if !trimmed.matches(EmailPattern) then
      Left(ApiError.Invalid("Enter a valid email address."))
    else
      val normalized = trimmed.toLowerCase
      val display = Option.when(trimmed != normalized)(trimmed)
      Right((normalized, display))

  private def validateCustomFields(
      workspaceId: UUID,
      fields: Json
  ): IO[Either[ApiError, Unit]] =
    fields.asObject match
      case None =>
        IO.pure(Left(ApiError.Invalid("customFields must be an object.")))
      case Some(obj) =>
        val keys = obj.keys.map(_.trim.toLowerCase).toSet
        if keys.isEmpty then IO.pure(Right(()))
        else
          repos.knownFieldNames(workspaceId).map { known =>
            val unknown = keys.diff(known)
            if unknown.nonEmpty then
              Left(
                ApiError.Invalid(
                  s"Unknown custom fields: ${unknown.mkString(", ")}. Define them first."
                )
              )
            else Right(())
          }

  // cursor codec: base64url(json({"t": epochMillis, "id": uuid, "e": email?}))
  // the email is present when sorting by email (keyset must match the sort)
  final case class Cursor(
      createdAt: OffsetDateTime,
      id: UUID,
      email: Option[String]
  )

  private def encodeCursor(c: Cursor): String =
    val base = Json.obj(
      "t" -> Json.fromLong(c.createdAt.toInstant.toEpochMilli),
      "id" -> Json.fromString(c.id.toString)
    )
    val payload = c.email
      .map(e => base.asObject.get.add("e", Json.fromString(e)).asJson)
      .getOrElse(base)
    Base64.getUrlEncoder.withoutPadding.encodeToString(
      payload.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )

  private def decodeCursor(raw: String): Either[ApiError, Cursor] =
    Try(Base64.getUrlDecoder.decode(raw)).toEither.left
      .map(_ => ApiError.Invalid("Malformed pagination cursor."))
      .flatMap { bytes =>
        jsonParse(
          new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
        ).left
          .map(_ => ApiError.Invalid("Malformed pagination cursor."))
          .flatMap { json =>
            for
              t <- json.hcursor
                .get[Long]("t")
                .left
                .map(_ => ApiError.Invalid("Malformed cursor."))
              id <- json.hcursor
                .get[UUID]("id")
                .left
                .map(_ => ApiError.Invalid("Malformed cursor."))
              email = json.hcursor.get[String]("e").toOption
            yield Cursor(
              OffsetDateTime
                .ofInstant(java.time.Instant.ofEpochMilli(t), ZoneOffset.UTC),
              id,
              email
            )
          }
      }

  private def toDto(v: repos.ContactView): ContactDto =
    ContactDto(
      id = v.id,
      companyId = v.companyId,
      companyName = v.companyName,
      email = v.email,
      emailDisplay = v.emailDisplay,
      firstName = v.firstName,
      lastName = v.lastName,
      jobTitle = v.jobTitle,
      website = v.website,
      phone = v.phone,
      region = v.region,
      timezone = v.timezone,
      notes = v.notes,
      customFields = v.customFields,
      verificationStatus = v.verificationStatus,
      verificationCheckedAt = v.verificationCheckedAt,
      verificationProvider = v.verificationProvider,
      state = v.state,
      createdAt = v.createdAt,
      updatedAt = v.updatedAt
    )

  /** Cursor reflects the last KEPT row; hasMore must be computed by the caller
    * (fetched size compared to the requested limit).
    */
  private def pageDto(
      items: List[ContactDto],
      raw: List[repos.ContactView],
      total: Long,
      hasMore: Boolean,
      sort: String
  ): ContactPage =
    val next =
      if hasMore then
        raw.lastOption.map { last =>
          encodeCursor(Cursor(last.createdAt, last.id, Some(last.email)))
        }
      else None
    ContactPage(items, next, total)

  private def filterFromParams(
      q: Option[String],
      state: Option[String],
      verification: Option[String],
      companyId: Option[UUID],
      listId: Option[UUID],
      tagId: Option[UUID],
      sort: Option[String],
      limit: Option[Int],
      cursor: Option[String]
  ): IO[Either[ApiError, repos.ContactFilter]] =
    val parsed = cursor.map(decodeCursor) match
      case Some(Left(error)) =>
        IO.pure(Left(error): Either[ApiError, repos.ContactFilter])
      case other =>
        IO.pure(
          Right(
            repos.ContactFilter(
              q = q.map(_.trim).filter(_.nonEmpty),
              state = state,
              verification = verification,
              companyId = companyId,
              listId = listId,
              tagId = tagId,
              sort = sort.getOrElse("created_desc"),
              limit =
                limit.map(l => math.min(math.max(l, 1), 100)).getOrElse(25),
              cursorCreatedAt = other.flatMap(_.toOption.map(_.createdAt)),
              cursorId = other.flatMap(_.toOption.map(_.id)),
              cursorEmail = other.flatMap(_.toOption.flatMap(_.email))
            )
          )
        )
    parsed

  // -- endpoints ---------------------------------------------------------------

  private val createContact = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "contacts")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CreateContactRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[ContactDto])
    .errorOut(errorOut)

  private val listContacts = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "contacts")
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("q"))
    .in(query[Option[String]]("state"))
    .in(query[Option[String]]("verification"))
    .in(query[Option[UUID]]("companyId"))
    .in(query[Option[UUID]]("listId"))
    .in(query[Option[UUID]]("tagId"))
    .in(query[Option[String]]("sort"))
    .in(query[Option[Int]]("limit"))
    .in(query[Option[String]]("cursor"))
    .out(jsonBody[ContactPage])
    .errorOut(errorOut)

  private val getContact = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "contacts" / path[UUID]("contactId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[ContactDto])
    .errorOut(errorOut)

  private val updateContact = endpoint.patch
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "contacts" / path[UUID]("contactId")
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[UpdateContactRequest])
    .out(jsonBody[ContactDto])
    .errorOut(errorOut)

  private val deleteContact = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "contacts" / path[UUID]("contactId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val batchContacts = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "contacts" / "batch"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[BatchContactsRequest])
    .out(jsonBody[BatchContactsResult])
    .errorOut(errorOut)

  private val contactProvenance = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "contacts" / path[UUID]("contactId") / "provenance"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[ProvenanceDto]])
    .errorOut(errorOut)

  private val addContactTags = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "contacts" / path[UUID]("contactId") / "tags"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AddTagsRequest])
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val removeContactTag = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "contacts" / path[UUID]("contactId") / "tags" / path[UUID]("tagId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val listCompanies = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "companies")
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("q"))
    .out(jsonBody[List[CompanyDto]])
    .errorOut(errorOut)

  private val createCompany = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "companies")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CreateCompanyRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[CompanyDto])
    .errorOut(errorOut)

  private val updateCompany = endpoint.patch
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "companies" / path[UUID]("companyId")
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[UpdateCompanyRequest])
    .out(jsonBody[CompanyDto])
    .errorOut(errorOut)

  private val deleteCompany = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "companies" / path[UUID]("companyId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val listLeadLists = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "lead-lists")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[LeadListDto]])
    .errorOut(errorOut)

  private val createLeadList = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "lead-lists")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CreateListRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[LeadListDto])
    .errorOut(errorOut)

  private val updateLeadList = endpoint.patch
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "lead-lists" / path[UUID]("listId")
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[UpdateListRequest])
    .out(jsonBody[LeadListDto])
    .errorOut(errorOut)

  private val deleteLeadList = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "lead-lists" / path[UUID]("listId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val addListMembers = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "lead-lists" / path[UUID]("listId") / "members"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[AddMembersRequest])
    .out(jsonBody[AddMembersResult])
    .errorOut(errorOut)

  private val removeListMember = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "lead-lists" / path[UUID]("listId") / "members" / path[UUID](
        "contactId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val listListMembers = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "lead-lists" / path[UUID]("listId") / "members"
    )
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[Int]]("limit"))
    .in(query[Option[String]]("cursor"))
    .out(jsonBody[ContactPage])
    .errorOut(errorOut)

  private val listTags = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "tags")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[TagDto]])
    .errorOut(errorOut)

  private val createTag = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "tags")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CreateTagRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[TagDto])
    .errorOut(errorOut)

  private val deleteTag = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "tags" / path[
        UUID
      ]("tagId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val listCustomFields = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "custom-fields"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[CustomFieldDto]])
    .errorOut(errorOut)

  private val createCustomField = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "custom-fields"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CreateCustomFieldRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[CustomFieldDto])
    .errorOut(errorOut)

  private val updateCustomField = endpoint.patch
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "custom-fields" / path[UUID]("fieldId")
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[UpdateCustomFieldRequest])
    .out(jsonBody[CustomFieldDto])
    .errorOut(errorOut)

  private val deleteCustomField = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "custom-fields" / path[UUID]("fieldId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  // -- logic --------------------------------------------------------------------

  private def createContactLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: CreateContactRequest
  ): IO[Either[(SttpStatus, Envelope), ContactDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          normalizeEmail(request.email) match
            case Left(error)             => IO.pure(Left(failed(error)))
            case Right((email, display)) =>
              validateCustomFields(
                workspaceId,
                request.customFields.getOrElse(Json.obj())
              ).flatMap {
                case Left(error) => IO.pure(Left(failed(error)))
                case Right(())   =>
                  repos.findContactByEmail(workspaceId, email).flatMap {
                    case Some(_) =>
                      IO.pure(
                        Left(
                          failed(
                            ApiError.Conflict(
                              "A contact with this email already exists."
                            )
                          )
                        )
                      )
                    case None =>
                      repos
                        .createContact(
                          workspaceId,
                          "manual",
                          repos.NewContactRow(
                            companyId = request.companyId,
                            email = email,
                            emailDisplay = request.emailDisplay.orElse(display),
                            firstName =
                              request.firstName.map(_.trim).filter(_.nonEmpty),
                            lastName =
                              request.lastName.map(_.trim).filter(_.nonEmpty),
                            jobTitle =
                              request.jobTitle.map(_.trim).filter(_.nonEmpty),
                            website =
                              request.website.map(_.trim).filter(_.nonEmpty),
                            phone =
                              request.phone.map(_.trim).filter(_.nonEmpty),
                            region =
                              request.region.map(_.trim).filter(_.nonEmpty),
                            timezone =
                              request.timezone.map(_.trim).filter(_.nonEmpty),
                            notes =
                              request.notes.map(_.trim).filter(_.nonEmpty),
                            customFields =
                              request.customFields.getOrElse(Json.obj())
                          )
                        )
                        .flatMap { view =>
                          repos
                            .audit(
                              workspaceId,
                              user.id,
                              "contact.created",
                              "contact",
                              view.id.toString
                            )
                            .as(Right(toDto(view)))
                        }
                  }
              }
      }

  private def listContactsLogic(
      authorization: Option[String],
      workspaceId: UUID,
      q: Option[String],
      state: Option[String],
      verification: Option[String],
      companyId: Option[UUID],
      listId: Option[UUID],
      tagId: Option[UUID],
      sort: Option[String],
      limit: Option[Int],
      cursor: Option[String]
  ): IO[Either[(SttpStatus, Envelope), ContactPage]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          filterFromParams(
            q,
            state,
            verification,
            companyId,
            listId,
            tagId,
            sort,
            limit,
            cursor
          ).flatMap {
            case Left(error)   => IO.pure(Left(failed(error)))
            case Right(filter) =>
              (
                repos.listContacts(workspaceId, filter),
                repos.countContacts(workspaceId, filter)
              ).parTupled
                .map { (views, total) =>
                  Right(
                    pageDto(
                      views.map(toDto),
                      views,
                      total,
                      hasMore = views.size == filter.limit,
                      sort = filter.sort
                    )
                  )
                }
          }
      }

  private def getContactLogic(
      authorization: Option[String],
      workspaceId: UUID,
      contactId: UUID
  ): IO[Either[(SttpStatus, Envelope), ContactDto]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.findContact(workspaceId, contactId).map {
            case None    => Left(failed(ApiError.NotFound("contact")))
            case Some(v) => Right(toDto(v))
          }
      }

  private def updateContactLogic(
      authorization: Option[String],
      workspaceId: UUID,
      contactId: UUID,
      request: UpdateContactRequest
  ): IO[Either[(SttpStatus, Envelope), ContactDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val customCheck: IO[Either[ApiError, Unit]] =
            request.customFields match
              case Some(fields) => validateCustomFields(workspaceId, fields)
              case None         => IO.pure(Right(()))
          customCheck.flatMap {
            case Left(error) => IO.pure(Left(failed(error)))
            case Right(())   =>
              (for
                existing <- repos.findContact(workspaceId, contactId)
                companyOk <- request.companyId.flatten match
                  case None                     => IO.pure(true)
                  case Some(requestedCompanyId) =>
                    repos
                      .listCompanies(workspaceId, None)
                      .map(cs => cs.exists(_.id == requestedCompanyId))
                sets = List.newBuilder[Fragment]
                result <- (existing, companyOk) match
                  case (None, _) =>
                    IO.pure(Left(failed(ApiError.NotFound("contact"))))
                  case (_, false) =>
                    IO.pure(Left(failed(ApiError.NotFound("company"))))
                  case (Some(_), true) =>
                    request.emailDisplay
                      .foreach(v => sets += fr"email_display = $v")
                    request.firstName.foreach(v => sets += fr"first_name = $v")
                    request.lastName.foreach(v => sets += fr"last_name = $v")
                    request.jobTitle.foreach(v => sets += fr"job_title = $v")
                    request.companyId.foreach(v => sets += fr"company_id = $v")
                    request.website.foreach(v => sets += fr"website = $v")
                    request.phone.foreach(v => sets += fr"phone = $v")
                    request.region.foreach(v => sets += fr"region = $v")
                    request.timezone.foreach(v => sets += fr"timezone = $v")
                    request.notes.foreach(v => sets += fr"notes = $v")
                    request.customFields.foreach(v =>
                      sets += fr"custom_fields = ${v.noSpaces}::jsonb"
                    )
                    for
                      _ <- repos.updateContact(
                        workspaceId,
                        contactId,
                        sets.result()
                      )
                      _ <- repos.audit(
                        workspaceId,
                        user.id,
                        "contact.updated",
                        "contact",
                        contactId.toString
                      )
                      view <- repos.findContact(workspaceId, contactId)
                    yield view match
                      case None    => Left(failed(ApiError.NotFound("contact")))
                      case Some(v) => Right(toDto(v))
              yield result)
          }
      }

  private def deleteContactLogic(
      authorization: Option[String],
      workspaceId: UUID,
      contactId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          repos.deleteContact(workspaceId, contactId).flatMap {
            case false => IO.pure(Left(failed(ApiError.NotFound("contact"))))
            case true  =>
              repos
                .audit(
                  workspaceId,
                  user.id,
                  "contact.deleted",
                  "contact",
                  contactId.toString
                )
                .as(Right(()))
          }
      }

  private val MaxBatch = 200

  private def batchContactsLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: BatchContactsRequest
  ): IO[Either[(SttpStatus, Envelope), BatchContactsResult]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          if request.contacts.isEmpty then
            IO.pure(
              Left(failed(ApiError.Invalid("Provide at least one contact.")))
            )
          else if request.contacts.size > MaxBatch then
            IO.pure(
              Left(
                failed(
                  ApiError.Invalid(s"Batch is limited to $MaxBatch contacts.")
                )
              )
            )
          else
            // validate every email first — reject the whole batch on any invalid
            val normalized: Either[ApiError, List[(String, BatchContactItem)]] =
              request.contacts
                .traverse(item =>
                  normalizeEmail(item.email).map(email => (email._1, item))
                )
            normalized match
              case Left(error)  => IO.pure(Left(failed(error)))
              case Right(items) =>
                val duplicateInBatch =
                  items.map(_._1).groupBy(identity).exists(_._2.size > 1)
                if duplicateInBatch then
                  IO.pure(
                    Left(
                      failed(
                        ApiError.Invalid(
                          "The batch contains duplicate email addresses."
                        )
                      )
                    )
                  )
                else
                  items
                    .foldLeftM(BatchContactsResult(0, 0, 0, Nil)) {
                      (acc, pair) =>
                        val (email, item) = pair
                        repos.findContactByEmail(workspaceId, email).flatMap {
                          case Some(existing) =>
                            // batch semantics: keep existing, report skipped
                            val next = acc.copy(
                              skipped = acc.skipped + 1,
                              items = acc.items :+ BatchResultItem(
                                email,
                                "skipped",
                                Some(existing.id)
                              )
                            )
                            IO.pure(next)
                          case None =>
                            repos
                              .createContact(
                                workspaceId,
                                "api",
                                repos.NewContactRow(
                                  companyId = item.companyId,
                                  email = email,
                                  emailDisplay = None,
                                  firstName = item.firstName,
                                  lastName = item.lastName,
                                  jobTitle = item.jobTitle,
                                  website = None,
                                  phone = item.phone,
                                  region = item.region,
                                  timezone = item.timezone,
                                  notes = None,
                                  customFields =
                                    item.customFields.getOrElse(Json.obj())
                                )
                              )
                              .map { view =>
                                acc.copy(
                                  created = acc.created + 1,
                                  items = acc.items :+ BatchResultItem(
                                    email,
                                    "created",
                                    Some(view.id)
                                  )
                                )
                              }
                        }
                    }
                    .map(result => Right(result))
      }

  private def provenanceLogic(
      authorization: Option[String],
      workspaceId: UUID,
      contactId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[ProvenanceDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.listProvenance(workspaceId, contactId).map { rows =>
            Right(
              rows.map(p =>
                ProvenanceDto(
                  p.id,
                  p.source,
                  p.importJobId,
                  p.details,
                  p.createdAt
                )
              )
            )
          }
      }

  private def addTagsLogic(
      authorization: Option[String],
      workspaceId: UUID,
      contactId: UUID,
      request: AddTagsRequest
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          if request.tagIds.isEmpty then
            IO.pure(Left(failed(ApiError.Invalid("Provide at least one tag."))))
          else
            repos
              .addContactTags(workspaceId, contactId, request.tagIds)
              .flatMap { added =>
                if added == 0 && request.tagIds.nonEmpty then
                  repos.findContact(workspaceId, contactId).map {
                    case None    => Left(failed(ApiError.NotFound("contact")))
                    case Some(_) => Right(())
                  }
                else IO.pure(Right(()))
              }
      }

  private def removeTagLogic(
      authorization: Option[String],
      workspaceId: UUID,
      contactId: UUID,
      tagId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.removeContactTag(workspaceId, contactId, tagId).map {
            case false => Left(failed(ApiError.NotFound("tag on contact")))
            case true  => Right(())
          }
      }

  private def companyDtoOf(c: repos.CompanyView): CompanyDto =
    CompanyDto(c.id, c.name, c.domain, c.website, c.contactCount, c.createdAt)

  private def listCompaniesLogic(
      authorization: Option[String],
      workspaceId: UUID,
      q: Option[String]
  ): IO[Either[(SttpStatus, Envelope), List[CompanyDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos
            .listCompanies(workspaceId, q.map(_.trim).filter(_.nonEmpty))
            .map(cs => Right(cs.map(companyDtoOf)))
      }

  private def createCompanyLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: CreateCompanyRequest
  ): IO[Either[(SttpStatus, Envelope), CompanyDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val name = request.name.trim
          if name.isEmpty || name.length > 200 then
            IO.pure(
              Left(
                failed(
                  ApiError.Invalid("Company name must be 1–200 characters.")
                )
              )
            )
          else
            repos
              .createCompany(
                workspaceId,
                name,
                request.domain.map(_.trim).filter(_.nonEmpty),
                request.website.map(_.trim).filter(_.nonEmpty)
              )
              .map {
                case None =>
                  Left(
                    failed(
                      ApiError.Conflict(
                        "A company with this name or domain already exists."
                      )
                    )
                  )
                case Some(c) => Right(companyDtoOf(c))
              }
      }

  private def updateCompanyLogic(
      authorization: Option[String],
      workspaceId: UUID,
      companyId: UUID,
      request: UpdateCompanyRequest
  ): IO[Either[(SttpStatus, Envelope), CompanyDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val nameOk =
            request.name.forall(n => n.trim.nonEmpty && n.trim.length <= 200)
          if !nameOk then
            IO.pure(
              Left(
                failed(
                  ApiError.Invalid("Company name must be 1–200 characters.")
                )
              )
            )
          else
            repos
              .updateCompany(
                workspaceId,
                companyId,
                request.name.map(_.trim),
                request.domain,
                request.website
              )
              .flatMap { updated =>
                if updated == 0 && request.name.isEmpty && request.domain.isEmpty && request.website.isEmpty
                then IO.pure(Left(failed(ApiError.NotFound("company"))))
                else if updated == 0 then
                  IO.pure(Left(failed(ApiError.NotFound("company"))))
                else
                  repos.listCompanies(workspaceId, None).map { cs =>
                    cs.find(_.id == companyId) match
                      case None    => Left(failed(ApiError.NotFound("company")))
                      case Some(c) => Right(companyDtoOf(c))
                  }
              }
      }

  private def deleteCompanyLogic(
      authorization: Option[String],
      workspaceId: UUID,
      companyId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          repos.deleteCompany(workspaceId, companyId).map {
            case false => Left(failed(ApiError.NotFound("company")))
            case true  => Right(())
          }
      }

  private def listDtoOf(l: repos.LeadListView): LeadListDto =
    LeadListDto(l.id, l.name, l.description, l.memberCount, l.createdAt)

  private def listLeadListsLogic(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[LeadListDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.listLeadLists(workspaceId).map(ls => Right(ls.map(listDtoOf)))
      }

  private def createLeadListLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: CreateListRequest
  ): IO[Either[(SttpStatus, Envelope), LeadListDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val name = request.name.trim
          if name.isEmpty || name.length > 120 then
            IO.pure(
              Left(
                failed(ApiError.Invalid("List name must be 1–120 characters."))
              )
            )
          else
            repos
              .createLeadList(
                workspaceId,
                user.id,
                name,
                request.description.map(_.trim).filter(_.nonEmpty)
              )
              .map {
                case None =>
                  Left(
                    failed(
                      ApiError.Conflict("A list with this name already exists.")
                    )
                  )
                case Some(l) => Right(listDtoOf(l))
              }
      }

  private def updateLeadListLogic(
      authorization: Option[String],
      workspaceId: UUID,
      listId: UUID,
      request: UpdateListRequest
  ): IO[Either[(SttpStatus, Envelope), LeadListDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          val nameOk =
            request.name.forall(n => n.trim.nonEmpty && n.trim.length <= 120)
          if !nameOk then
            IO.pure(
              Left(
                failed(ApiError.Invalid("List name must be 1–120 characters."))
              )
            )
          else
            repos
              .updateLeadList(
                workspaceId,
                listId,
                request.name.map(_.trim),
                request.description
              )
              .flatMap { updated =>
                if updated == 0 then
                  IO.pure(Left(failed(ApiError.NotFound("list"))))
                else
                  repos.listLeadLists(workspaceId).map { ls =>
                    ls.find(_.id == listId) match
                      case None    => Left(failed(ApiError.NotFound("list")))
                      case Some(l) => Right(listDtoOf(l))
                  }
              }
      }

  private def deleteLeadListLogic(
      authorization: Option[String],
      workspaceId: UUID,
      listId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.deleteLeadList(workspaceId, listId).map {
            case false => Left(failed(ApiError.NotFound("list")))
            case true  => Right(())
          }
      }

  private def addMembersLogic(
      authorization: Option[String],
      workspaceId: UUID,
      listId: UUID,
      request: AddMembersRequest
  ): IO[Either[(SttpStatus, Envelope), AddMembersResult]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          if request.contactIds.isEmpty then
            IO.pure(
              Left(failed(ApiError.Invalid("Provide at least one contact id.")))
            )
          else if request.contactIds.size > MaxBatch then
            IO.pure(
              Left(
                failed(
                  ApiError.Invalid(s"Batch is limited to $MaxBatch contacts.")
                )
              )
            )
          else
            repos
              .addListMembers(workspaceId, user.id, listId, request.contactIds)
              .flatMap { added =>
                if added == 0 then
                  repos.listLeadLists(workspaceId).map { ls =>
                    if ls.exists(_.id == listId) then Right(AddMembersResult(0))
                    else Left(failed(ApiError.NotFound("list")))
                  }
                else IO.pure(Right(AddMembersResult(added)))
              }
      }

  private def removeMemberLogic(
      authorization: Option[String],
      workspaceId: UUID,
      listId: UUID,
      contactId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.removeListMember(workspaceId, listId, contactId).map {
            case false => Left(failed(ApiError.NotFound("list membership")))
            case true  => Right(())
          }
      }

  private def listMembersLogic(
      authorization: Option[String],
      workspaceId: UUID,
      listId: UUID,
      limit: Option[Int],
      cursor: Option[String]
  ): IO[Either[(SttpStatus, Envelope), ContactPage]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          filterFromParams(
            None,
            None,
            None,
            None,
            Some(listId),
            None,
            None,
            limit,
            cursor
          ).flatMap {
            case Left(error)   => IO.pure(Left(failed(error)))
            case Right(filter) =>
              repos.listMembers(workspaceId, listId, filter).map { views =>
                Right(
                  pageDto(
                    views.map(toDto),
                    views,
                    views.size.toLong,
                    hasMore = views.size == filter.limit,
                    sort = filter.sort
                  )
                )
              }
          }
      }

  private def tagDtoOf(t: repos.TagView): TagDto =
    TagDto(t.id, t.name, t.color, t.contactCount, t.createdAt)

  private def listTagsLogic(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[TagDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.listTags(workspaceId).map(ts => Right(ts.map(tagDtoOf)))
      }

  private def createTagLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: CreateTagRequest
  ): IO[Either[(SttpStatus, Envelope), TagDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val name = request.name.trim
          if name.isEmpty || name.length > 60 then
            IO.pure(
              Left(
                failed(ApiError.Invalid("Tag name must be 1–60 characters."))
              )
            )
          else
            repos
              .createTag(
                workspaceId,
                name,
                request.color.map(_.trim).filter(_.nonEmpty)
              )
              .map {
                case None =>
                  Left(
                    failed(
                      ApiError.Conflict("A tag with this name already exists.")
                    )
                  )
                case Some(t) => Right(tagDtoOf(t))
              }
      }

  private def deleteTagLogic(
      authorization: Option[String],
      workspaceId: UUID,
      tagId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.deleteTag(workspaceId, tagId).map {
            case false => Left(failed(ApiError.NotFound("tag")))
            case true  => Right(())
          }
      }

  private def fieldDtoOf(f: repos.CustomFieldView): CustomFieldDto =
    CustomFieldDto(f.id, f.name, f.fieldType, f.description, f.createdAt)

  private val FieldTypes = Set("text", "number", "date", "boolean", "url")

  private def listFieldsLogic(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[CustomFieldDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos
            .listCustomFields(workspaceId)
            .map(fs => Right(fs.map(fieldDtoOf)))
      }

  private def createFieldLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: CreateCustomFieldRequest
  ): IO[Either[(SttpStatus, Envelope), CustomFieldDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val name = request.name.trim
          if name.isEmpty || name.length > 60 then
            IO.pure(
              Left(
                failed(ApiError.Invalid("Field name must be 1–60 characters."))
              )
            )
          else if !FieldTypes.contains(request.fieldType) then
            IO.pure(
              Left(
                failed(
                  ApiError.Invalid(
                    s"Field type must be one of: ${FieldTypes.mkString(", ")}.油"
                  )
                )
              )
            )
          else
            repos
              .createCustomField(
                workspaceId,
                name,
                request.fieldType,
                request.description.map(_.trim).filter(_.nonEmpty)
              )
              .map {
                case None =>
                  Left(
                    failed(
                      ApiError
                        .Conflict("A field with this name already exists.")
                    )
                  )
                case Some(f) => Right(fieldDtoOf(f))
              }
      }

  private def updateFieldLogic(
      authorization: Option[String],
      workspaceId: UUID,
      fieldId: UUID,
      request: UpdateCustomFieldRequest
  ): IO[Either[(SttpStatus, Envelope), CustomFieldDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          val nameOk =
            request.name.forall(n => n.trim.nonEmpty && n.trim.length <= 60)
          if !nameOk then
            IO.pure(
              Left(
                failed(ApiError.Invalid("Field name must be 1–60 characters."))
              )
            )
          else
            repos
              .updateCustomField(
                workspaceId,
                fieldId,
                request.name.map(_.trim),
                request.description
              )
              .flatMap { updated =>
                if updated == 0 then
                  IO.pure(Left(failed(ApiError.NotFound("custom field"))))
                else
                  repos.listCustomFields(workspaceId).map { fs =>
                    fs.find(_.id == fieldId) match
                      case None =>
                        Left(failed(ApiError.NotFound("custom field")))
                      case Some(f) => Right(fieldDtoOf(f))
                  }
              }
      }

  private def deleteFieldLogic(
      authorization: Option[String],
      workspaceId: UUID,
      fieldId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.deleteCustomField(workspaceId, fieldId).map {
            case false => Left(failed(ApiError.NotFound("custom field")))
            case true  => Right(())
          }
      }

  // -- assembly -------------------------------------------------------------------

  private def routes: List[ServerEndpoint[Fs2Streams[IO], IO]] = List(
    createContact.serverLogic((workspaceId, auth, req) =>
      createContactLogic(auth, workspaceId, req)
    ),
    listContacts.serverLogic(
      (
          workspaceId,
          auth,
          q,
          state,
          verification,
          companyId,
          listId,
          tagId,
          sort,
          limit,
          cursor
      ) =>
        listContactsLogic(
          auth,
          workspaceId,
          q,
          state,
          verification,
          companyId,
          listId,
          tagId,
          sort,
          limit,
          cursor
        )
    ),
    getContact.serverLogic((workspaceId, contactId, auth) =>
      getContactLogic(auth, workspaceId, contactId)
    ),
    updateContact.serverLogic((workspaceId, contactId, auth, req) =>
      updateContactLogic(auth, workspaceId, contactId, req)
    ),
    deleteContact.serverLogic((workspaceId, contactId, auth) =>
      deleteContactLogic(auth, workspaceId, contactId)
    ),
    batchContacts.serverLogic((workspaceId, auth, req) =>
      batchContactsLogic(auth, workspaceId, req)
    ),
    contactProvenance.serverLogic((workspaceId, contactId, auth) =>
      provenanceLogic(auth, workspaceId, contactId)
    ),
    addContactTags.serverLogic((workspaceId, contactId, auth, req) =>
      addTagsLogic(auth, workspaceId, contactId, req)
    ),
    removeContactTag.serverLogic((workspaceId, contactId, tagId, auth) =>
      removeTagLogic(auth, workspaceId, contactId, tagId)
    ),
    listCompanies.serverLogic((workspaceId, auth, q) =>
      listCompaniesLogic(auth, workspaceId, q)
    ),
    createCompany.serverLogic((workspaceId, auth, req) =>
      createCompanyLogic(auth, workspaceId, req)
    ),
    updateCompany.serverLogic((workspaceId, companyId, auth, req) =>
      updateCompanyLogic(auth, workspaceId, companyId, req)
    ),
    deleteCompany.serverLogic((workspaceId, companyId, auth) =>
      deleteCompanyLogic(auth, workspaceId, companyId)
    ),
    listLeadLists.serverLogic((workspaceId, auth) =>
      listLeadListsLogic(auth, workspaceId)
    ),
    createLeadList.serverLogic((workspaceId, auth, req) =>
      createLeadListLogic(auth, workspaceId, req)
    ),
    updateLeadList.serverLogic((workspaceId, listId, auth, req) =>
      updateLeadListLogic(auth, workspaceId, listId, req)
    ),
    deleteLeadList.serverLogic((workspaceId, listId, auth) =>
      deleteLeadListLogic(auth, workspaceId, listId)
    ),
    addListMembers.serverLogic((workspaceId, listId, auth, req) =>
      addMembersLogic(auth, workspaceId, listId, req)
    ),
    removeListMember.serverLogic((workspaceId, listId, contactId, auth) =>
      removeMemberLogic(auth, workspaceId, listId, contactId)
    ),
    listListMembers.serverLogic((workspaceId, listId, auth, limit, cursor) =>
      listMembersLogic(auth, workspaceId, listId, limit, cursor)
    ),
    listTags.serverLogic((workspaceId, auth) =>
      listTagsLogic(auth, workspaceId)
    ),
    createTag.serverLogic((workspaceId, auth, req) =>
      createTagLogic(auth, workspaceId, req)
    ),
    deleteTag.serverLogic((workspaceId, tagId, auth) =>
      deleteTagLogic(auth, workspaceId, tagId)
    ),
    listCustomFields.serverLogic((workspaceId, auth) =>
      listFieldsLogic(auth, workspaceId)
    ),
    createCustomField.serverLogic((workspaceId, auth, req) =>
      createFieldLogic(auth, workspaceId, req)
    ),
    updateCustomField.serverLogic((workspaceId, fieldId, auth, req) =>
      updateFieldLogic(auth, workspaceId, fieldId, req)
    ),
    deleteCustomField.serverLogic((workspaceId, fieldId, auth) =>
      deleteFieldLogic(auth, workspaceId, fieldId)
    )
  )

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(routes)
