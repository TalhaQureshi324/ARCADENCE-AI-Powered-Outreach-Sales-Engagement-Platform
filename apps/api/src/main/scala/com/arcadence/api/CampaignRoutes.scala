package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
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

object CampaignDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class VariantDto(
      variantKey: String,
      subject: String = "",
      preheader: String = "",
      bodyHtml: String = "",
      bodyText: String = "",
      signature: String = ""
  )
  object VariantDto:
    given Encoder[VariantDto] = deriveEncoder
    given Decoder[VariantDto] = deriveDecoder
    given Schema[VariantDto] = Schema.derived

  final case class StepDto(
      id: UUID,
      position: Int,
      `type`: String,
      name: String,
      waitDays: Option[Int],
      key: Option[String],
      condition: Option[Json],
      tagId: Option[UUID],
      taskDueDays: Option[Int],
      businessDays: Boolean,
      variants: List[VariantDto]
  )

  final case class EdgeDto(from: UUID, to: UUID, branchKey: Option[String])
  object EdgeDto:
    given Encoder[EdgeDto] = deriveEncoder
    given Decoder[EdgeDto] = deriveDecoder
    given Schema[EdgeDto] = Schema.derived
  object StepDto:
    given Encoder[StepDto] = deriveEncoder
    given Decoder[StepDto] = deriveDecoder
    given Schema[StepDto] = Schema.derived

  final case class AudienceDto(
      id: UUID,
      leadListId: Option[UUID],
      segmentId: Option[UUID]
  )
  object AudienceDto:
    given Encoder[AudienceDto] = deriveEncoder
    given Decoder[AudienceDto] = deriveDecoder
    given Schema[AudienceDto] = Schema.derived

  final case class CampaignDto(
      id: UUID,
      workspaceId: UUID,
      name: String,
      description: String,
      tags: List[String],
      status: String,
      timezone: String,
      dailyCap: Int,
      openTracking: Boolean,
      clickTracking: Boolean,
      threadMode: Boolean,
      sendWindowStart: String,
      sendWindowEnd: String,
      businessDaysOnly: Boolean,
      stepCount: Int,
      activatedAt: Option[OffsetDateTime],
      archivedAt: Option[OffsetDateTime],
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime
  )
  object CampaignDto:
    given Encoder[CampaignDto] = deriveEncoder
    given Decoder[CampaignDto] = deriveDecoder
    given Schema[CampaignDto] = Schema.derived

  final case class CampaignDetailDto(
      campaign: CampaignDto,
      steps: List[StepDto],
      edges: List[EdgeDto],
      mailboxIds: List[UUID],
      audiences: List[AudienceDto]
  )

  final case class ValidateGraphRequest(
      steps: List[StepInput],
      edges: List[EdgeInput]
  )
  object ValidateGraphRequest:
    given Encoder[ValidateGraphRequest] = deriveEncoder
    given Decoder[ValidateGraphRequest] = deriveDecoder
    given Schema[ValidateGraphRequest] = Schema.derived

  final case class GraphValidationDto(valid: Boolean, errors: List[String])
  object GraphValidationDto:
    given Encoder[GraphValidationDto] = deriveEncoder
    given Decoder[GraphValidationDto] = deriveDecoder
    given Schema[GraphValidationDto] = Schema.derived

  final case class SimulateRequest(contactId: UUID)
  object SimulateRequest:
    given Encoder[SimulateRequest] = deriveEncoder
    given Decoder[SimulateRequest] = deriveDecoder
    given Schema[SimulateRequest] = Schema.derived

  final case class PlannedStepDto(
      stepKey: String,
      stepType: String,
      name: String,
      plannedAt: OffsetDateTime,
      note: String
  )
  object PlannedStepDto:
    given Encoder[PlannedStepDto] = deriveEncoder
    given Decoder[PlannedStepDto] = deriveDecoder
    given Schema[PlannedStepDto] = Schema.derived

  final case class SimulationDto(timezone: String, plan: List[PlannedStepDto])
  object SimulationDto:
    given Encoder[SimulationDto] = deriveEncoder
    given Decoder[SimulationDto] = deriveDecoder
    given Schema[SimulationDto] = Schema.derived

  final case class PreflightIssue(code: String, message: String)
  object PreflightIssue:
    given Encoder[PreflightIssue] = deriveEncoder
    given Decoder[PreflightIssue] = deriveDecoder
    given Schema[PreflightIssue] = Schema.derived

  final case class PreflightDto(
      blockers: List[PreflightIssue],
      warnings: List[PreflightIssue]
  )
  object PreflightDto:
    given Encoder[PreflightDto] = deriveEncoder
    given Decoder[PreflightDto] = deriveDecoder
    given Schema[PreflightDto] = Schema.derived
  object CampaignDetailDto:
    given Encoder[CampaignDetailDto] = deriveEncoder
    given Decoder[CampaignDetailDto] = deriveDecoder
    given Schema[CampaignDetailDto] = Schema.derived

  final case class CreateCampaignRequest(
      name: String,
      description: Option[String] = None,
      tags: Option[List[String]] = None,
      timezone: Option[String] = None,
      dailyCap: Option[Int] = None
  )
  object CreateCampaignRequest:
    given Encoder[CreateCampaignRequest] = deriveEncoder
    given Decoder[CreateCampaignRequest] = deriveDecoder
    given Schema[CreateCampaignRequest] = Schema.derived

  final case class VariantInput(
      variantKey: String,
      subject: Option[String] = None,
      preheader: Option[String] = None,
      bodyHtml: Option[String] = None,
      bodyText: Option[String] = None,
      signature: Option[String] = None
  )
  object VariantInput:
    given Encoder[VariantInput] = deriveEncoder
    given Decoder[VariantInput] = deriveDecoder
    given Schema[VariantInput] = Schema.derived

  final case class StepInput(
      `type`: String,
      name: Option[String] = None,
      waitDays: Option[Int] = None,
      key: Option[String] = None,
      condition: Option[Json] = None,
      tagId: Option[UUID] = None,
      taskDueDays: Option[Int] = None,
      businessDays: Option[Boolean] = None,
      variants: Option[List[VariantInput]] = None
  )

  final case class EdgeInput(
      from: String,
      to: String,
      branchKey: Option[String] = None
  )
  object EdgeInput:
    given Encoder[EdgeInput] = deriveEncoder
    given Decoder[EdgeInput] = deriveDecoder
    given Schema[EdgeInput] = Schema.derived
  object StepInput:
    given Encoder[StepInput] = deriveEncoder
    given Decoder[StepInput] = deriveDecoder
    given Schema[StepInput] = Schema.derived

  final case class AudienceInput(
      leadListId: Option[UUID] = None,
      segmentId: Option[UUID] = None
  )
  object AudienceInput:
    given Encoder[AudienceInput] = deriveEncoder
    given Decoder[AudienceInput] = deriveDecoder
    given Schema[AudienceInput] = Schema.derived

  final case class UpdateCampaignRequest(
      name: Option[String] = None,
      description: Option[String] = None,
      tags: Option[List[String]] = None,
      timezone: Option[String] = None,
      dailyCap: Option[Int] = None,
      threadMode: Option[Boolean] = None,
      sendWindowStart: Option[String] = None,
      sendWindowEnd: Option[String] = None,
      businessDaysOnly: Option[Boolean] = None,
      steps: Option[List[StepInput]] = None,
      edges: Option[List[EdgeInput]] = None,
      mailboxIds: Option[List[UUID]] = None,
      audiences: Option[List[AudienceInput]] = None
  )
  object UpdateCampaignRequest:
    given Encoder[UpdateCampaignRequest] = deriveEncoder
    given Decoder[UpdateCampaignRequest] = deriveDecoder
    given Schema[UpdateCampaignRequest] = Schema.derived

  final case class TransitionRequest(action: String)
  object TransitionRequest:
    given Encoder[TransitionRequest] = deriveEncoder
    given Decoder[TransitionRequest] = deriveDecoder
    given Schema[TransitionRequest] = Schema.derived

  final case class EnrollRequest(contactIds: List[UUID])
  object EnrollRequest:
    given Encoder[EnrollRequest] = deriveEncoder
    given Decoder[EnrollRequest] = deriveDecoder
    given Schema[EnrollRequest] = Schema.derived

  final case class EnrollResult(enrolled: Int)
  object EnrollResult:
    given Encoder[EnrollResult] = deriveEncoder
    given Decoder[EnrollResult] = deriveDecoder
    given Schema[EnrollResult] = Schema.derived

  final case class TransitionResult(status: String)
  object TransitionResult:
    given Encoder[TransitionResult] = deriveEncoder
    given Decoder[TransitionResult] = deriveDecoder
    given Schema[TransitionResult] = Schema.derived

  final case class VersionDto(
      id: UUID,
      version: Int,
      definition: Json,
      createdAt: OffsetDateTime
  )
  object VersionDto:
    given Encoder[VersionDto] = deriveEncoder
    given Decoder[VersionDto] = deriveDecoder
    given Schema[VersionDto] = Schema.derived

/** Campaign lifecycle (docs/ARCHITECTURE.md §7, Phase 4.1). Structural edits
  * (steps, mailboxes, audiences) and identity changes (name, timezone) are
  * locked while a campaign is active/paused so live enrollments keep executing
  * a stable definition; completed/cancelled/archived campaigns are fully
  * read-only. Drafts alone are hard-deletable.
  */
final class CampaignRoutes(
    authenticator: Authenticator,
    repos: CampaignRepos,
    leadsRepos: LeadsRepos,
    emailAccounts: EmailAccountRepos,
    authorizer: Authorizer
):
  import CampaignDtos.*

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

  // -- endpoints ------------------------------------------------------------------

  private val base =
    "api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "campaigns"

  private val listCampaigns = endpoint.get
    .in(base)
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("status"))
    .out(jsonBody[List[CampaignDto]])
    .errorOut(errorOut)

  private val createCampaign = endpoint.post
    .in(base)
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CreateCampaignRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[CampaignDto])
    .errorOut(errorOut)

  private val getCampaign = endpoint.get
    .in(base / path[UUID]("campaignId"))
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[CampaignDetailDto])
    .errorOut(errorOut)

  private val updateCampaign = endpoint.patch
    .in(base / path[UUID]("campaignId"))
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[UpdateCampaignRequest])
    .out(jsonBody[CampaignDetailDto])
    .errorOut(errorOut)

  private val deleteCampaign = endpoint.delete
    .in(base / path[UUID]("campaignId"))
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val duplicateCampaign = endpoint.post
    .in(base / path[UUID]("campaignId") / "duplicate")
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[CampaignDetailDto])
    .errorOut(errorOut)

  private val transitionCampaign = endpoint.post
    .in(base / path[UUID]("campaignId") / "transition")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[TransitionRequest])
    .out(jsonBody[TransitionResult])
    .errorOut(errorOut)

  private val enrollContacts = endpoint.post
    .in(base / path[UUID]("campaignId") / "enroll")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[EnrollRequest])
    .out(jsonBody[EnrollResult])
    .errorOut(errorOut)

  private val validateGraph = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "campaigns" / path[UUID]("campaignId") / "validate-graph"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[ValidateGraphRequest])
    .out(jsonBody[GraphValidationDto])
    .errorOut(errorOut)

  private val simulate = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "campaigns" / path[UUID]("campaignId") / "simulate"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[SimulateRequest])
    .out(jsonBody[SimulationDto])
    .errorOut(errorOut)

  private val preflight = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "campaigns" / path[UUID]("campaignId") / "preflight"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[PreflightDto])
    .errorOut(errorOut)

  private val listVersions = endpoint.get
    .in(base / path[UUID]("campaignId") / "versions")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[VersionDto]])
    .errorOut(errorOut)

  // -- mapping --------------------------------------------------------------------

  private def campaignDto(c: repos.CampaignRow, stepCount: Int): CampaignDto =
    CampaignDto(
      id = c.id,
      workspaceId = c.workspaceId,
      name = c.name,
      description = c.description,
      tags = c.tags,
      status = c.status,
      timezone = c.timezone,
      dailyCap = c.dailyCap,
      openTracking = c.openTracking,
      clickTracking = c.clickTracking,
      threadMode = c.threadMode,
      sendWindowStart = c.sendWindowStart.toString,
      sendWindowEnd = c.sendWindowEnd.toString,
      businessDaysOnly = c.businessDaysOnly,
      stepCount = stepCount,
      activatedAt = c.activatedAt,
      archivedAt = c.archivedAt,
      createdAt = c.createdAt,
      updatedAt = c.updatedAt
    )

  private def detail(
      c: repos.CampaignRow,
      steps: List[repos.StepRow],
      variants: List[repos.VariantRow],
      edges: List[repos.EdgeRow],
      mailboxIds: List[UUID],
      audiences: List[repos.AudienceRow]
  ): CampaignDetailDto =
    val detailEdges =
      edges.map(e => EdgeDto(e.fromStepId, e.toStepId, e.branchKey))
    val byStep = variants.groupBy(_.stepId)
    CampaignDetailDto(
      campaign = campaignDto(c, steps.size),
      steps = steps.map(s =>
        StepDto(
          id = s.id,
          position = s.position,
          `type` = s.`type`,
          name = s.name,
          waitDays = s.waitDays,
          key = s.clientKey,
          condition = s.condition,
          tagId = s.tagId,
          taskDueDays = s.taskDueDays,
          businessDays = s.businessDays,
          variants = byStep
            .getOrElse(s.id, Nil)
            .map(v =>
              VariantDto(
                v.variantKey,
                v.subject,
                v.preheader,
                v.bodyHtml,
                v.bodyText,
                v.signature
              )
            )
        )
      ),
      edges = detailEdges,
      mailboxIds = mailboxIds,
      audiences =
        audiences.map(a => AudienceDto(a.id, a.leadListId, a.segmentId))
    )

  private def loadDetail(
      workspaceId: UUID,
      campaignId: UUID
  ): IO[Option[CampaignDetailDto]] =
    repos.findCampaign(workspaceId, campaignId).flatMap {
      case None    => IO.pure(None)
      case Some(c) =>
        (
          repos.stepsFor(c.id),
          repos.variantsFor(c.id),
          repos.edgesFor(c.id),
          repos.mailboxIdsFor(c.id),
          repos.audiencesFor(c.id)
        ).parMapN { (steps, variants, edges, mailboxes, audiences) =>
          Some(detail(c, steps, variants, edges, mailboxes, audiences))
        }
    }

  private def toGStep(si: StepInput, key: String): SequenceGraph.GStep =
    SequenceGraph.GStep(
      key = key,
      stepType = si.`type`,
      waitDays = si.waitDays,
      condition = si.condition,
      tagId = si.tagId,
      taskDueDays = si.taskDueDays,
      variantCount = si.variants.getOrElse(Nil).size
    )

  private def toGStepRow(sr: repos.StepRow): SequenceGraph.GStep =
    SequenceGraph.GStep(
      key = sr.clientKey.getOrElse(sr.position.toString),
      stepType = sr.`type`,
      waitDays = sr.waitDays,
      condition = sr.condition,
      tagId = sr.tagId,
      taskDueDays = sr.taskDueDays,
      variantCount = 1 // variant shape is enforced at save time
    )

  /** Validates the submitted graph and maps it to repo input (editor keys
    * preserved). Legacy linear saves keep using replaceSteps.
    */
  private def prepareGraph(
      stepsIn: List[StepInput],
      edgesIn: List[EdgeInput]
  ): Either[List[String], (List[repos.GraphStepIn], List[repos.GraphEdgeIn])] =
    val gSteps = stepsIn.zipWithIndex.map((si, idx) =>
      toGStep(si, si.key.getOrElse(s"s${idx + 1}"))
    )
    val gEdges =
      edgesIn.map(e => SequenceGraph.GEdge(e.from, e.to, e.branchKey))
    val errors = SequenceGraph.validate(gSteps, gEdges)
    if errors.nonEmpty then Left(errors)
    else
      val inputs = stepsIn.zipWithIndex.map { (si, idx) =>
        val key = si.key.getOrElse(s"s${idx + 1}")
        val variantsIn: List[VariantInput] = si.`type` match
          case "email" =>
            si.variants.getOrElse(Nil) match
              case Nil => List(VariantInput("A"))
              case vs  => vs
          case _ => Nil
        repos.GraphStepIn(
          key = key,
          position = idx + 1,
          stepType = si.`type`,
          name = si.name.getOrElse("").trim,
          waitDays = si.waitDays,
          businessDays = si.businessDays.getOrElse(false),
          condition = si.condition,
          tagId = si.tagId,
          taskDueDays = si.taskDueDays,
          variants = variantsIn.map(v =>
            repos.VariantRow(
              UUID.randomUUID(),
              v.variantKey,
              v.subject.getOrElse(""),
              v.preheader.getOrElse(""),
              v.bodyHtml.getOrElse(""),
              v.bodyText.getOrElse(""),
              v.signature.getOrElse("")
            )
          )
        )
      }
      Right(
        (inputs, edgesIn.map(e => repos.GraphEdgeIn(e.from, e.to, e.branchKey)))
      )

  private val StepTypes = Set("email", "wait")

  /** Validates the step list shape (types, wait rules, variant keys, order). */
  private def validateSteps(steps: List[StepInput]): Either[String, List[
    (Int, String, String, Option[Int], List[repos.VariantRow])
  ]] =
    if steps.isEmpty then Left("A campaign needs at least one step.")
    else if steps.size > 100 then Left("A campaign can have at most 100 steps.")
    else
      steps.zipWithIndex.traverse { (s, idx) =>
        val position = idx + 1
        if !StepTypes.contains(s.`type`) then
          Left(s"Unknown step type: ${s.`type`}")
        else if s.`type` == "wait" && s.waitDays.isEmpty then
          Left(s"Step $position: wait steps need waitDays.")
        else if s.`type` == "wait" && s.waitDays.exists(d => d < 0 || d > 365)
        then Left(s"Step $position: waitDays must be 0–365.")
        else if s.`type` == "email" && s.variants.isEmpty then
          Left(s"Step $position: email steps need at least one variant.")
        else if s.`type` == "wait" && s.variants.exists(_.nonEmpty) then
          Left(s"Step $position: wait steps cannot have variants.")
        else
          val variantsIn = s.variants.getOrElse(Nil)
          val keys = variantsIn.map(_.variantKey)
          if !keys.forall(k => k == "A" || k == "Z") then
            Left(s"Step $position: variant keys must be A or Z.")
          else if keys.distinct.size != keys.size then
            Left(s"Step $position: duplicate variant keys.")
          else
            Right(
              (
                position,
                s.`type`,
                s.name.getOrElse("").trim,
                s.waitDays,
                variantsIn.map(v =>
                  repos.VariantRow(
                    UUID.randomUUID(),
                    v.variantKey,
                    v.subject.getOrElse(""),
                    v.preheader.getOrElse(""),
                    v.bodyHtml.getOrElse(""),
                    v.bodyText.getOrElse(""),
                    ""
                  )
                )
              )
            )
      }

  private def validateTimezone(tz: String): Boolean =
    try
      java.time.ZoneId.of(tz)
      true
    catch case _: Exception => false

  // -- logic ----------------------------------------------------------------------

  private def listLogic(
      authorization: Option[String],
      workspaceId: UUID,
      status: Option[String]
  ): IO[Either[(SttpStatus, Envelope), List[CampaignDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.listCampaigns(workspaceId, status).flatMap { cs =>
            cs.traverse(c =>
              repos.stepsFor(c.id).map(n => campaignDto(c, n.size))
            ).map(dtos => Right(dtos))
          }
      }

  private def createLogic(
      authorization: Option[String],
      workspaceId: UUID,
      req: CreateCampaignRequest
  ): IO[Either[(SttpStatus, Envelope), CampaignDto]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val name = req.name.trim
          if name.isEmpty || name.length > 140 then
            IO.pure(
              Left(
                failed(
                  ApiError.Invalid("Campaign name must be 1–140 characters.")
                )
              )
            )
          else if !validateTimezone(req.timezone.getOrElse("Etc/UTC")) then
            IO.pure(
              Left(
                failed(ApiError.Invalid(s"Unknown timezone: ${req.timezone}"))
              )
            )
          else if req.dailyCap.exists(c => c < 1 || c > 10000) then
            IO.pure(Left(failed(ApiError.Invalid("dailyCap must be 1–10000."))))
          else
            repos
              .createCampaign(
                workspaceId,
                user.id,
                name,
                req.description.getOrElse("").trim,
                req.tags.getOrElse(Nil).map(_.trim).filter(_.nonEmpty).distinct,
                req.timezone.getOrElse("Etc/UTC"),
                req.dailyCap.getOrElse(50)
              )
              .flatMap {
                case None =>
                  IO.pure(
                    Left(
                      failed(
                        ApiError
                          .Conflict("A campaign with this name already exists.")
                      )
                    )
                  )
                case Some(c) =>
                  loadDetail(workspaceId, c.id).map(d => Right(d.get.campaign))
              }
      }

  private def getLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID
  ): IO[Either[(SttpStatus, Envelope), CampaignDetailDto]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          loadDetail(workspaceId, campaignId).map {
            case None      => Left(failed(ApiError.NotFound("campaign")))
            case Some(det) => Right(det)
          }
      }

  /** Structural references must exist in this workspace; audiences need exactly
    * one of list/segment. Checked before any mutation so failures come back as
    * validation errors, not 500s.
    */
  private def refsCheck(
      workspaceId: UUID,
      req: UpdateCampaignRequest
  ): IO[Either[String, Unit]] =
    val mailboxCheck = req.mailboxIds match
      case None      => IO.pure(Right(()))
      case Some(ids) =>
        repos.countOwnedEmailAccounts(workspaceId, ids.distinct).map { owned =>
          if owned != ids.distinct.size.toLong
          then
            Left("One or more sending accounts do not exist in this workspace.")
          else Right(())
        }
    val audienceCheck = req.audiences match
      case None     => IO.pure(Right(()))
      case Some(as) =>
        as.foldLeft(IO.pure(Right(()): Either[String, Unit])) { (acc, a) =>
          (a.leadListId, a.segmentId) match
            case (Some(listId), None) =>
              acc.flatMap(_ =>
                repos.countOwnedList(workspaceId, listId).map {
                  case 0 =>
                    Left(
                      "A lead list in audiences does not exist in this workspace."
                    )
                  case _ => Right(())
                }
              )
            case (None, Some(segmentId)) =>
              acc.flatMap(_ =>
                repos.countOwnedSegment(workspaceId, segmentId).map {
                  case 0 =>
                    Left(
                      "A segment in audiences does not exist in this workspace."
                    )
                  case _ => Right(())
                }
              )
            case _ =>
              IO.pure(
                Left(
                  "Each audience must reference exactly one list or segment."
                )
              )
        }
    (mailboxCheck, audienceCheck).parMapN {
      case (Left(e), _)           => Left(e)
      case (_, Left(e))           => Left(e)
      case (Right(()), Right(())) => Right(())
    }

  private def updateLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID,
      req: UpdateCampaignRequest
  ): IO[Either[(SttpStatus, Envelope), CampaignDetailDto]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          repos.findCampaign(workspaceId, campaignId).flatMap {
            case None    => IO.pure(Left(failed(ApiError.NotFound("campaign"))))
            case Some(c) =>
              val status = c.status
              val structural =
                req.steps.isDefined || req.mailboxIds.isDefined || req.audiences.isDefined ||
                  req.threadMode.isDefined || req.sendWindowStart.isDefined ||
                  req.sendWindowEnd.isDefined || req.businessDaysOnly.isDefined
              val identity = req.name.isDefined || req.timezone.isDefined
              val locked = status == "active" || status == "paused"
              val frozen =
                status == "completed" || status == "cancelled" || status == "archived"
              if frozen then
                IO.pure(
                  Left(
                    failed(
                      ApiError.Conflict(
                        s"A $status campaign is read-only. Archive it in the UI or duplicate it to iterate."
                      )
                    )
                  )
                )
              else if locked && identity then
                IO.pure(
                  Left(
                    failed(
                      ApiError.Conflict(
                        "Name and timezone are locked while the campaign is running — pause or complete it first."
                      )
                    )
                  )
                )
              else if structural && !(status == "draft") then
                repos.activeEnrollmentCount(c.id).flatMap { n =>
                  IO.pure(
                    Left(
                      failed(
                        ApiError.Conflict(
                          s"Sequence structure is locked once a campaign leaves draft" +
                            (if n > 0 then
                               s" ($n active enrollment(s) depend on it)."
                             else ".")
                        )
                      )
                    )
                  )
                }
              else
                val graphMode = req.edges.isDefined
                val parsed =
                  if status != "draft" then
                    Right(None) // structural already rejected
                  else if graphMode then
                    Right(
                      None
                    ) // graph saves are validated by SequenceGraph below
                  else req.steps.traverse(validateSteps)
                parsed match
                  case Left(error) =>
                    IO.pure(Left(failed(ApiError.Invalid(error))))
                  case Right(_) =>
                    val tzCheck =
                      req.timezone.filterNot(validateTimezone) match
                        case Some(bad) => Left(s"Unknown timezone: $bad")
                        case None      => Right(())
                    tzCheck match
                      case Left(error) =>
                        IO.pure(Left(failed(ApiError.Invalid(error))))
                      case Right(()) =>
                        val graphErrors: List[String] =
                          (req.edges, req.steps) match
                            case (Some(edgesIn), Some(stepsIn)) =>
                              SequenceGraph.validate(
                                stepsIn.zipWithIndex.map((si, idx) =>
                                  toGStep(si, si.key.getOrElse(s"s${idx + 1}"))
                                ),
                                edgesIn.map(e =>
                                  SequenceGraph.GEdge(e.from, e.to, e.branchKey)
                                )
                              )
                            case _ => Nil
                        if graphErrors.nonEmpty then
                          IO.pure(
                            Left(
                              failed(
                                ApiError.Invalid(graphErrors.mkString(" "))
                              )
                            )
                          )
                        else
                          refsCheck(workspaceId, req).flatMap {
                            case Left(error) =>
                              IO.pure(Left(failed(ApiError.Invalid(error))))
                            case Right(()) =>
                              for
                                fieldResult <- repos.updateCampaignFields(
                                  workspaceId,
                                  campaignId,
                                  req.name.map(_.trim),
                                  req.description.map(_.trim),
                                  req.tags.map(
                                    _.map(_.trim).filter(_.nonEmpty).distinct
                                  ),
                                  req.timezone,
                                  req.dailyCap,
                                  req.threadMode,
                                  req.sendWindowStart
                                    .map(v => java.time.LocalTime.parse(v)),
                                  req.sendWindowEnd
                                    .map(v => java.time.LocalTime.parse(v)),
                                  req.businessDaysOnly
                                )
                                conflicted = fieldResult == -1
                                _ <-
                                  if conflicted then IO.pure(())
                                  else
                                    (req.steps, parsed) match
                                      case (Some(stepsIn), _) if graphMode =>
                                        prepareGraph(
                                          stepsIn,
                                          req.edges.getOrElse(Nil)
                                        ) match
                                          case Left(errors) =>
                                            IO.raiseError(
                                              new IllegalArgumentException(
                                                errors.mkString(" ")
                                              )
                                            )
                                          case Right(
                                                (graphSteps, graphEdges)
                                              ) =>
                                            repos.replaceGraph(
                                              campaignId,
                                              user.id,
                                              graphSteps,
                                              graphEdges
                                            )
                                      case (Some(_), Right(Some(validated))) =>
                                        repos.replaceSteps(
                                          campaignId,
                                          user.id,
                                          validated
                                        )
                                      case _ => IO.unit
                                _ <-
                                  if conflicted then IO.pure(())
                                  else
                                    req.mailboxIds.traverse_(ids =>
                                      repos.replaceMailboxes(
                                        campaignId,
                                        ids.distinct
                                      )
                                    )
                                _ <-
                                  if conflicted then IO.pure(())
                                  else
                                    req.audiences.traverse_(as =>
                                      repos.replaceAudiences(
                                        campaignId,
                                        as.map(a => (a.leadListId, a.segmentId))
                                      )
                                    )
                                detailOpt <-
                                  if conflicted then IO.pure(None)
                                  else loadDetail(workspaceId, campaignId)
                              yield
                                if conflicted then
                                  Left(
                                    failed(
                                      ApiError.Conflict(
                                        "A campaign with this name already exists."
                                      )
                                    )
                                  )
                                else Right(detailOpt.get)
                          }
          }
      }

  private def validateGraphLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID,
      req: ValidateGraphRequest
  ): IO[Either[(SttpStatus, Envelope), GraphValidationDto]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.findCampaign(workspaceId, campaignId).flatMap {
            case None    => IO.pure(Left(failed(ApiError.NotFound("campaign"))))
            case Some(_) =>
              val gSteps = req.steps.zipWithIndex
                .map((si, idx) => toGStep(si, si.key.getOrElse(s"s${idx + 1}")))
              val gEdges = req.edges
                .map(e => SequenceGraph.GEdge(e.from, e.to, e.branchKey))
              val errors = SequenceGraph.validate(gSteps, gEdges)
              IO.pure(Right(GraphValidationDto(errors.isEmpty, errors)))
          }
      }

  private def simulateLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID,
      req: SimulateRequest
  ): IO[Either[(SttpStatus, Envelope), SimulationDto]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.findCampaign(workspaceId, campaignId).flatMap {
            case None    => IO.pure(Left(failed(ApiError.NotFound("campaign"))))
            case Some(c) =>
              leadsRepos.findContact(workspaceId, req.contactId).flatMap {
                case None => IO.pure(Left(failed(ApiError.NotFound("contact"))))
                case Some(contactView) =>
                  (repos.stepsFor(c.id), repos.edgesFor(c.id))
                    .parMapN { (steps, edges) =>
                      val keyById = steps
                        .flatMap(sr => sr.clientKey.map(k => sr.id -> k))
                        .toMap
                      val resolver = Personalization.resolverFor(
                        email = contactView.email,
                        firstName = contactView.firstName,
                        lastName = contactView.lastName,
                        company = contactView.companyName,
                        jobTitle = contactView.jobTitle,
                        region = contactView.region,
                        timezone = contactView.timezone,
                        website = contactView.website,
                        phone = contactView.phone,
                        customFields = contactView.customFields
                      )
                      val zone = scala.util
                        .Try(java.time.ZoneId.of(c.timezone))
                        .getOrElse(java.time.ZoneId.of("Etc/UTC"))
                      Schedule.simulate(
                        steps.map(sr =>
                          Schedule.SimStep(
                            key = sr.clientKey.getOrElse(sr.position.toString),
                            position = sr.position,
                            stepType = sr.`type`,
                            name = sr.name,
                            waitDays = sr.waitDays,
                            businessDays = sr.businessDays,
                            condition = sr.condition
                          )
                        ),
                        edges.flatMap { e =>
                          for
                            from <- keyById.get(e.fromStepId)
                            to <- keyById.get(e.toStepId)
                          yield Schedule.SimEdge(from, to, e.branchKey)
                        },
                        zone,
                        c.sendWindowStart,
                        c.sendWindowEnd,
                        c.businessDaysOnly,
                        resolver,
                        java.time.ZonedDateTime.now(zone)
                      )
                    }
                    .flatMap {
                      case Left(err) =>
                        IO.pure(Left(failed(ApiError.Invalid(err))))
                      case Right(plan) =>
                        IO.pure(
                          Right(
                            SimulationDto(
                              timezone = c.timezone,
                              plan = plan.map(p =>
                                PlannedStepDto(
                                  p.stepKey,
                                  p.stepType,
                                  p.name,
                                  p.plannedAt.toOffsetDateTime,
                                  p.note
                                )
                              )
                            )
                          )
                        )
                    }
              }
          }
      }

  /** Launch preflight (Phase 4.4): blockers prevent activation; warnings do not
    * (domain authentication and suppression overlap are advisory until
    * deliverability enforcement lands).
    */
  private def preflightFor(
      workspaceId: UUID,
      campaignId: UUID
  ): IO[PreflightDto] =
    repos.findCampaign(workspaceId, campaignId).flatMap {
      case None =>
        IO.pure(
          PreflightDto(
            List(PreflightIssue("not_found", "Campaign not found.")),
            Nil
          )
        )
      case Some(c) =>
        (
          repos.stepsFor(c.id),
          repos.edgesFor(c.id),
          repos.variantsFor(c.id),
          repos.audiencesFor(
            c.id
          ),
          repos.mailboxIdsFor(c.id)
        ).parMapN { (steps, edges, variants, audiences, mailboxIds) =>
          val blockers = List.newBuilder[PreflightIssue]
          val warnings = List.newBuilder[PreflightIssue]

          if steps.isEmpty then
            blockers += PreflightIssue(
              "no_steps",
              "The campaign has no steps yet."
            )
          else
            val gSteps = steps.map { sr =>
              SequenceGraph.GStep(
                key = sr.clientKey.getOrElse(sr.position.toString),
                stepType = sr.`type`,
                waitDays = sr.waitDays,
                condition = sr.condition,
                tagId = sr.tagId,
                taskDueDays = sr.taskDueDays,
                variantCount = 1
              )
            }
            val graphErrors =
              if edges.isEmpty then SequenceGraph.validateLinear(gSteps)
              else
                val keyById =
                  steps.flatMap(st => st.clientKey.map(k => st.id -> k)).toMap
                SequenceGraph.validate(
                  gSteps,
                  edges.flatMap(e =>
                    for
                      from <- keyById.get(e.fromStepId)
                      to <- keyById.get(e.toStepId)
                    yield SequenceGraph.GEdge(from, to, e.branchKey)
                  )
                )
            if graphErrors.nonEmpty then
              blockers += PreflightIssue(
                "graph_invalid",
                "Sequence problems: " + graphErrors.mkString(" ")
              )

          val unknown = Personalization.unknownTokens(
            variants
              .map(v =>
                s"${v.subject}\n${v.preheader}\n${v.bodyHtml}\n${v.bodyText}"
              )
              .mkString("\n")
          )
          if unknown.nonEmpty then
            blockers += PreflightIssue(
              "unknown_token",
              "Unknown personalization tokens: " + unknown.mkString(", ") + "."
            )

          if audiences.isEmpty then
            blockers += PreflightIssue(
              "no_audience",
              "No audience is assigned."
            )
          if mailboxIds.isEmpty then
            blockers += PreflightIssue(
              "no_mailbox",
              "No sending mailbox is assigned."
            )

          val audienceSize = repos.listAudienceSize(campaignId)
          val suppressed = repos.suppressedInListAudience(campaignId)
          val domainCount = repos.verifiedDomainCount(c.id)
          val connectedCheck =
            if mailboxIds.isEmpty then IO.pure(0)
            else
              mailboxIds.foldLeft(IO.pure(0)) { (acc, id) =>
                acc.flatMap { n =>
                  emailAccounts.findAccount(workspaceId, id).map {
                    case Some(a) if a.status == "connected" => n + 1
                    case _                                  => n
                  }
                }
              }
          val result: IO[PreflightDto] = audienceSize.flatMap { size =>
            suppressed.flatMap { sup =>
              domainCount.flatMap { domains =>
                connectedCheck.flatMap { connected =>
                  if audiences.exists(_.leadListId.isDefined) && size == 0 then
                    warnings += PreflightIssue(
                      "empty_audience",
                      "The assigned lead lists have no contacts yet — enroll or import before sending."
                    )
                  if sup > 0 then
                    warnings += PreflightIssue(
                      "suppressed_in_audience",
                      s"$sup audience contact(s) are suppressed — they are skipped at send time."
                    )
                  if mailboxIds.nonEmpty && connected == 0 then
                    blockers += PreflightIssue(
                      "no_connected_mailbox",
                      "None of the assigned mailboxes is connected — reconnect on the Email Accounts page."
                    )
                  if mailboxIds.nonEmpty && domains == 0 then
                    warnings += PreflightIssue(
                      "domain_not_verified",
                      "No assigned mailbox uses a verified sending domain — deliverability may suffer."
                    )
                  IO.pure(PreflightDto(blockers.result(), warnings.result()))
                }
              }
            }
          }
          result
        }.flatten
    }

  private def preflightLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID
  ): IO[Either[(SttpStatus, Envelope), PreflightDto]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.findCampaign(workspaceId, campaignId).flatMap {
            case None    => IO.pure(Left(failed(ApiError.NotFound("campaign"))))
            case Some(_) => preflightFor(workspaceId, campaignId).map(Right(_))
          }
      }

  private def deleteLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.findCampaign(workspaceId, campaignId).flatMap {
            case None    => IO.pure(Left(failed(ApiError.NotFound("campaign"))))
            case Some(c) =>
              if c.status != "draft" then
                IO.pure(
                  Left(
                    failed(
                      ApiError.Conflict(
                        "Only drafts can be deleted. Move the campaign to archived instead."
                      )
                    )
                  )
                )
              else
                repos.deleteDraft(workspaceId, campaignId).map(_ => Right(()))
          }
      }

  private def duplicateLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID
  ): IO[Either[(SttpStatus, Envelope), CampaignDetailDto]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          repos.findCampaign(workspaceId, campaignId).flatMap {
            case None => IO.pure(Left(failed(ApiError.NotFound("campaign"))))
            case Some(src) =>
              repos.listCampaigns(workspaceId, None).flatMap { all =>
                val base = s"${src.name} (copy)"
                val taken = all.map(_.name.toLowerCase).toSet
                val newName =
                  if !taken.contains(base.toLowerCase) then base
                  else
                    (2 to 50)
                      .map(n => s"${src.name} (copy $n)")
                      .find(n => !taken.contains(n.toLowerCase))
                      .getOrElse(
                        s"${src.name} (copy ${UUID.randomUUID().toString.take(8)})"
                      )
                repos
                  .duplicateCampaign(workspaceId, user.id, campaignId, newName)
                  .flatMap {
                    case None =>
                      IO.pure(Left(failed(ApiError.NotFound("campaign"))))
                    case Some(id) =>
                      loadDetail(workspaceId, id).map {
                        case None => Left(failed(ApiError.NotFound("campaign")))
                        case Some(d) => Right(d)
                      }
                  }
              }
          }
      }

  /** Permitted transitions (Phase 4.1): activate: draft → active (needs ≥1
    * step, ≥1 audience, ≥1 mailbox) pause: active → paused resume: paused →
    * active complete: active → completed cancel: active|paused → cancelled
    * archive: draft|completed|cancelled → archived
    */
  private def allowedTransition(
      status: String,
      action: String
  ): Option[String] =
    (status, action) match
      case ("draft", "activate")    => Some("active")
      case ("active", "pause")      => Some("paused")
      case ("paused", "resume")     => Some("active")
      case ("active", "complete")   => Some("completed")
      case ("active", "cancel")     => Some("cancelled")
      case ("paused", "cancel")     => Some("cancelled")
      case ("draft", "archive")     => Some("archived")
      case ("completed", "archive") => Some("archived")
      case ("cancelled", "archive") => Some("archived")
      case _                        => None

  private def transitionLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID,
      req: TransitionRequest
  ): IO[Either[(SttpStatus, Envelope), TransitionResult]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          repos.findCampaign(workspaceId, campaignId).flatMap {
            case None    => IO.pure(Left(failed(ApiError.NotFound("campaign"))))
            case Some(c) =>
              val action = req.action.trim.toLowerCase
              val nextOpt = allowedTransition(c.status, action)
              nextOpt match
                case None =>
                  IO.pure(
                    Left(
                      failed(
                        ApiError.Conflict(
                          s"Cannot $action a campaign in '${c.status}' state."
                        )
                      )
                    )
                  )
                case Some(next) =>
                  val prerequisites: IO[Option[String]] =
                    if action != "activate" then IO.pure(None)
                    else
                      (
                        repos.stepsFor(c.id),
                        repos.audiencesFor(c.id),
                        repos.mailboxIdsFor(c.id),
                        repos.edgesFor(c.id)
                      ).parMapN { (steps, audiences, mailboxes, edges) =>
                        val missing = List(
                          if steps.isEmpty then Some("at least one step")
                          else None,
                          if audiences.isEmpty then
                            Some("at least one audience")
                          else None,
                          if mailboxes.isEmpty then
                            Some("at least one sending mailbox")
                          else None
                        ).flatten
                        val graphErrors: List[String] =
                          if steps.isEmpty then Nil
                          else
                            val gSteps = steps.map(toGStepRow)
                            if edges.isEmpty then
                              SequenceGraph.validateLinear(gSteps)
                            else
                              val keyById =
                                steps
                                  .flatMap(st =>
                                    st.clientKey.map(k => st.id -> k)
                                  )
                                  .toMap
                              SequenceGraph.validate(
                                gSteps,
                                edges.flatMap(e =>
                                  for
                                    from <- keyById.get(e.fromStepId)
                                    to <- keyById.get(e.toStepId)
                                  yield SequenceGraph
                                    .GEdge(from, to, e.branchKey)
                                )
                              )
                        val all = missing ++ graphErrors
                        if all.isEmpty then None
                        else Some(all.mkString("; "))
                      }
                  prerequisites.flatMap {
                    case Some(missing) =>
                      IO.pure(
                        Left(
                          failed(
                            ApiError
                              .Conflict(s"Cannot activate: needs $missing.")
                          )
                        )
                      )
                    case None if action == "activate" =>
                      preflightFor(workspaceId, campaignId).flatMap { pf =>
                        if pf.blockers.nonEmpty then
                          IO.pure(
                            Left(
                              failed(
                                ApiError.Conflict(
                                  "Cannot activate \u2014 preflight found blocking issues: " +
                                    pf.blockers
                                      .map(b => b.message)
                                      .mkString(" ")
                                )
                              )
                            )
                          )
                        else
                          repos
                            .transitionStatus(
                              workspaceId,
                              campaignId,
                              c.status,
                              next
                            )
                            .flatMap {
                              case 0 =>
                                IO.pure(
                                  Left(
                                    failed(
                                      ApiError.Conflict(
                                        "Campaign state changed concurrently \u2014 reload and retry."
                                      )
                                    )
                                  )
                                )
                              case _ =>
                                repos
                                  .audit(
                                    workspaceId,
                                    user.id,
                                    "campaign.transitioned",
                                    "campaign",
                                    campaignId.toString,
                                    Json.obj(
                                      "from" -> Json.fromString(c.status),
                                      "to" -> Json.fromString(next),
                                      "action" -> Json.fromString(action)
                                    )
                                  )
                                  .map(_ => Right(TransitionResult(next)))
                            }
                      }
                    case None =>
                      repos
                        .transitionStatus(
                          workspaceId,
                          campaignId,
                          c.status,
                          next
                        )
                        .flatMap {
                          case 0 =>
                            IO.pure(
                              Left(
                                failed(
                                  ApiError.Conflict(
                                    "Campaign state changed concurrently — reload and retry."
                                  )
                                )
                              )
                            )
                          case _ =>
                            repos
                              .audit(
                                workspaceId,
                                user.id,
                                "campaign.transitioned",
                                "campaign",
                                campaignId.toString,
                                Json.obj(
                                  "from" -> Json.fromString(c.status),
                                  "to" -> Json.fromString(next),
                                  "action" -> Json.fromString(action)
                                )
                              )
                              .map(_ => Right(TransitionResult(next)))
                        }
                  }
          }
      }

  private def enrollLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID,
      req: EnrollRequest
  ): IO[Either[(SttpStatus, Envelope), EnrollResult]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.findCampaign(workspaceId, campaignId).flatMap {
            case None    => IO.pure(Left(failed(ApiError.NotFound("campaign"))))
            case Some(c) =>
              if c.status != "active" then
                IO.pure(
                  Left(
                    failed(
                      ApiError.Conflict(
                        "Contacts can only be enrolled in active campaigns."
                      )
                    )
                  )
                )
              else if req.contactIds.isEmpty then
                IO.pure(
                  Left(
                    failed(ApiError.Invalid("contactIds must not be empty."))
                  )
                )
              else
                repos
                  .enroll(workspaceId, campaignId, req.contactIds.distinct)
                  .map(n => Right(EnrollResult(n)))
          }
      }

  private def versionsLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[VersionDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos
            .versionsFor(workspaceId, campaignId)
            .map(vs =>
              Right(
                vs.map(v =>
                  VersionDto(v.id, v.version, v.definition, v.createdAt)
                )
              )
            )
      }

  // -- assembly --------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      listCampaigns.serverLogic((workspaceId, auth, status) =>
        listLogic(auth, workspaceId, status)
      ),
      createCampaign.serverLogic((workspaceId, auth, req) =>
        createLogic(auth, workspaceId, req)
      ),
      getCampaign.serverLogic((workspaceId, campaignId, auth) =>
        getLogic(auth, workspaceId, campaignId)
      ),
      updateCampaign.serverLogic((workspaceId, campaignId, auth, req) =>
        updateLogic(auth, workspaceId, campaignId, req)
      ),
      deleteCampaign.serverLogic((workspaceId, campaignId, auth) =>
        deleteLogic(auth, workspaceId, campaignId)
      ),
      duplicateCampaign.serverLogic((workspaceId, campaignId, auth) =>
        duplicateLogic(auth, workspaceId, campaignId)
      ),
      transitionCampaign.serverLogic((workspaceId, campaignId, auth, req) =>
        transitionLogic(auth, workspaceId, campaignId, req)
      ),
      enrollContacts.serverLogic((workspaceId, campaignId, auth, req) =>
        enrollLogic(auth, workspaceId, campaignId, req)
      ),
      validateGraph.serverLogic((workspaceId, campaignId, auth, req) =>
        validateGraphLogic(auth, workspaceId, campaignId, req)
      ),
      simulate.serverLogic((workspaceId, campaignId, auth, req) =>
        simulateLogic(auth, workspaceId, campaignId, req)
      ),
      preflight.serverLogic((workspaceId, campaignId, auth) =>
        preflightLogic(auth, workspaceId, campaignId)
      ),
      listVersions.serverLogic((workspaceId, campaignId, auth) =>
        versionsLogic(auth, workspaceId, campaignId)
      )
    )
  )
