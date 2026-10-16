package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.api.CampaignDtos.VariantDto
import com.arcadence.core.email.OutboundMessage
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.util.UUID

object ComposerDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class VariantContentRequest(
      subject: String,
      preheader: String,
      bodyHtml: String,
      bodyText: String,
      signature: String
  )
  object VariantContentRequest:
    given Encoder[VariantContentRequest] = deriveEncoder
    given Decoder[VariantContentRequest] = deriveDecoder
    given Schema[VariantContentRequest] = Schema.derived

  final case class TokenInfo(field: String, fallback: Option[String])
  object TokenInfo:
    given Encoder[TokenInfo] = deriveEncoder
    given Decoder[TokenInfo] = deriveDecoder
    given Schema[TokenInfo] = Schema.derived

  final case class VariantValidation(
      tokens: List[TokenInfo],
      unknown: List[String]
  )
  object VariantValidation:
    given Encoder[VariantValidation] = deriveEncoder
    given Decoder[VariantValidation] = deriveDecoder
    given Schema[VariantValidation] = Schema.derived

  final case class VariantContentResponse(
      variant: VariantDto,
      validation: VariantValidation
  )
  object VariantContentResponse:
    given Encoder[VariantContentResponse] = deriveEncoder
    given Decoder[VariantContentResponse] = deriveDecoder
    given Schema[VariantContentResponse] = Schema.derived

  final case class PreviewRequest(
      stepId: UUID,
      variantKey: String,
      contactId: UUID
  )
  object PreviewRequest:
    given Encoder[PreviewRequest] = deriveEncoder
    given Decoder[PreviewRequest] = deriveDecoder
    given Schema[PreviewRequest] = Schema.derived

  final case class PreviewReport(
      resolved: Map[String, String],
      fellBack: List[String],
      missing: List[String],
      sanitized: Boolean
  )
  object PreviewReport:
    given Encoder[PreviewReport] = deriveEncoder
    given Decoder[PreviewReport] = deriveDecoder
    given Schema[PreviewReport] = Schema.derived

  final case class PreviewResponse(
      subject: String,
      html: String,
      text: String,
      report: PreviewReport
  )
  object PreviewResponse:
    given Encoder[PreviewResponse] = deriveEncoder
    given Decoder[PreviewResponse] = deriveDecoder
    given Schema[PreviewResponse] = Schema.derived

  final case class TestSendRequest(
      stepId: UUID,
      variantKey: String,
      contactId: UUID,
      emailAccountId: Option[UUID]
  )
  object TestSendRequest:
    given Encoder[TestSendRequest] = deriveEncoder
    given Decoder[TestSendRequest] = deriveDecoder
    given Schema[TestSendRequest] = Schema.derived

  final case class TestSendResponse(
      from: String,
      to: String,
      subject: String,
      providerMessageId: String
  )
  object TestSendResponse:
    given Encoder[TestSendResponse] = deriveEncoder
    given Decoder[TestSendResponse] = deriveDecoder
    given Schema[TestSendResponse] = Schema.derived

  final case class TemplateDto(
      id: UUID,
      name: String,
      subject: String,
      preheader: String,
      bodyHtml: String,
      bodyText: String
  )
  object TemplateDto:
    given Encoder[TemplateDto] = deriveEncoder
    given Decoder[TemplateDto] = deriveDecoder
    given Schema[TemplateDto] = Schema.derived

  final case class SaveTemplateRequest(
      name: String,
      subject: String,
      preheader: String,
      bodyHtml: String,
      bodyText: String
  )
  object SaveTemplateRequest:
    given Encoder[SaveTemplateRequest] = deriveEncoder
    given Decoder[SaveTemplateRequest] = deriveDecoder
    given Schema[SaveTemplateRequest] = Schema.derived

  final case class BlockDto(
      id: UUID,
      name: String,
      bodyHtml: String,
      bodyText: String
  )
  object BlockDto:
    given Encoder[BlockDto] = deriveEncoder
    given Decoder[BlockDto] = deriveDecoder
    given Schema[BlockDto] = Schema.derived

  final case class SaveBlockRequest(
      name: String,
      bodyHtml: String,
      bodyText: String
  )
  object SaveBlockRequest:
    given Encoder[SaveBlockRequest] = deriveEncoder
    given Decoder[SaveBlockRequest] = deriveDecoder
    given Schema[SaveBlockRequest] = Schema.derived

/** Composer endpoints (Phase 4.2): variant content editing (draft-only),
  * personalization preview against a REAL contact (missing variables are
  * reported, never fabricated), authorized test sending to the authenticated
  * user's own address, saved templates and reusable blocks.
  */
final class ComposerRoutes(
    authenticator: Authenticator,
    repos: CampaignRepos,
    leadsRepos: LeadsRepos,
    emailAccounts: EmailAccountRepos,
    authorizer: Authorizer,
    registry: Option[ProviderRegistry] = None
):
  import ComposerDtos.*
  import repos.VariantRow

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

  private val putVariantContent = endpoint.put
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "campaigns" / path[UUID]("campaignId") / "steps" / path[UUID](
        "stepId"
      ) / "variants" / path[String]("variantKey")
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[VariantContentRequest])
    .out(jsonBody[VariantContentResponse])
    .errorOut(errorOut)

  private val previewVariant = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "campaigns" / path[UUID]("campaignId") / "preview"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[PreviewRequest])
    .out(jsonBody[PreviewResponse])
    .errorOut(errorOut)

  private val testSendVariant = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "campaigns" / path[UUID]("campaignId") / "test-send"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[TestSendRequest])
    .out(jsonBody[TestSendResponse])
    .errorOut(errorOut)

  private val listTemplates = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "email-templates"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[TemplateDto]])
    .errorOut(errorOut)

  private val createTemplate = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "email-templates"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[SaveTemplateRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[TemplateDto])
    .errorOut(errorOut)

  private val deleteTemplate = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "email-templates" / path[UUID]("templateId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val listBlocks = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "reusable-blocks"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[BlockDto]])
    .errorOut(errorOut)

  private val createBlock = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "reusable-blocks"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[SaveBlockRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[BlockDto])
    .errorOut(errorOut)

  private val deleteBlock = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "reusable-blocks" / path[UUID]("blockId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  // -- helpers --------------------------------------------------------------------

  private def variantDto(v: VariantRow): VariantDto =
    VariantDto(
      variantKey = v.variantKey,
      subject = v.subject,
      preheader = v.preheader,
      bodyHtml = v.bodyHtml,
      bodyText = v.bodyText,
      signature = v.signature
    )

  private def validationFor(texts: String*): VariantValidation =
    val all = texts.mkString("\n")
    VariantValidation(
      tokens =
        Personalization.tokens(all).map { case (f, fb) => TokenInfo(f, fb) },
      unknown = Personalization.unknownTokens(all)
    )

  private case class ContactValues(
      email: String,
      values: Map[String, String]
  )

  private def contactValues(c: leadsRepos.ContactView): ContactValues =
    val resolver = Personalization.resolverFor(
      email = c.email,
      firstName = c.firstName,
      lastName = c.lastName,
      company = c.companyName,
      jobTitle = c.jobTitle,
      region = c.region,
      timezone = c.timezone,
      website = c.website,
      phone = c.phone,
      customFields = c.customFields
    )
    val fields = Personalization.KnownFields ++ c.customFields.asObject.toList
      .flatMap(_.keys)
      .map(k => s"custom.$k")
    ContactValues(
      c.email,
      fields.flatMap(f => resolver(f).filter(_.nonEmpty).map(v => f -> v)).toMap
    )

  /** Loads the variant (workspace-scoped) and the sample contact; renders
    * subject/html/text with a full resolution report. Sanitizes the HTML — the
    * preview shows EXACTLY what a send would contain.
    */
  private def renderPreview(
      workspaceId: UUID,
      campaignId: UUID,
      stepId: UUID,
      variantKey: String,
      contactId: UUID
  ): IO[Either[(SttpStatus, Envelope), (VariantRow, PreviewResponse)]] =
    repos.findVariant(workspaceId, campaignId, stepId, variantKey).flatMap {
      case None => IO.pure(Left(failed(ApiError.NotFound("step variant"))))
      case Some(variant) =>
        leadsRepos.findContact(workspaceId, contactId).flatMap {
          case None => IO.pure(Left(failed(ApiError.NotFound("contact"))))
          case Some(contact) =>
            val cv = contactValues(contact)
            val resolver = Personalization.resolverFor(
              email = contact.email,
              firstName = contact.firstName,
              lastName = contact.lastName,
              company = contact.companyName,
              jobTitle = contact.jobTitle,
              region = contact.region,
              timezone = contact.timezone,
              website = contact.website,
              phone = contact.phone,
              customFields = contact.customFields
            )
            val subjectR = Personalization.render(variant.subject, resolver)
            val htmlR = Personalization.render(variant.bodyHtml, resolver)
            val sigR = Personalization.render(variant.signature, resolver)
            val sanitizedHtml = Personalization.sanitizeHtml(htmlR.text)
            val fullHtml =
              if sigR.text.trim.isEmpty then sanitizedHtml
              else
                sanitizedHtml + Personalization.sanitizeHtml(
                  "<br/>--<br/>" + sigR.text
                )
            val bodyText =
              if variant.bodyText.trim.nonEmpty then
                Personalization.render(variant.bodyText, resolver).text
              else Personalization.htmlToText(htmlR.text)
            val fullText =
              if sigR.text.trim.isEmpty then bodyText
              else bodyText + "\n--\n" + sigR.text
            val resolved = cv.values.map { case (k, v) =>
              k -> (if v.length > 60 then v.take(57) + "..." else v)
            }
            IO.pure(
              Right(
                (
                  variant,
                  PreviewResponse(
                    subject = subjectR.text,
                    html = fullHtml,
                    text = fullText,
                    report = PreviewReport(
                      resolved = resolved,
                      fellBack =
                        (subjectR.fellBack ++ htmlR.fellBack ++ sigR.fellBack).distinct,
                      missing =
                        (subjectR.missing ++ htmlR.missing ++ sigR.missing).distinct,
                      sanitized = true
                    )
                  )
                )
              )
            )
        }
    }

  // -- logic ----------------------------------------------------------------------

  private def putVariantLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID,
      stepId: UUID,
      variantKey: String,
      req: VariantContentRequest
  ): IO[Either[(SttpStatus, Envelope), VariantContentResponse]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          val key = variantKey.trim.toUpperCase
          if key != "A" && key != "Z" then
            IO.pure(
              Left(failed(ApiError.Invalid("Variant key must be A or Z.")))
            )
          else
            repos
              .updateVariantContent(
                workspaceId,
                campaignId,
                stepId,
                key,
                subject = Personalization.sanitizeText(req.subject),
                preheader = Personalization.sanitizeText(req.preheader),
                bodyHtml = Personalization.sanitizeHtml(req.bodyHtml),
                bodyText = req.bodyText,
                signature = Personalization.sanitizeHtml(req.signature)
              )
              .flatMap {
                case false =>
                  IO.pure(
                    Left(
                      failed(
                        ApiError.Conflict(
                          "Variant content can only be saved while the campaign is a draft."
                        )
                      )
                    )
                  )
                case true =>
                  repos
                    .findVariant(workspaceId, campaignId, stepId, key)
                    .flatMap {
                      case None =>
                        IO.pure(Left(failed(ApiError.NotFound("step variant"))))
                      case Some(v) =>
                        IO.pure(
                          Right(
                            VariantContentResponse(
                              variant = variantDto(v),
                              validation = validationFor(
                                v.subject,
                                v.preheader,
                                v.bodyHtml,
                                v.bodyText,
                                v.signature
                              )
                            )
                          )
                        )
                    }
              }
      }

  private def previewLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID,
      req: PreviewRequest
  ): IO[Either[(SttpStatus, Envelope), PreviewResponse]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          renderPreview(
            workspaceId,
            campaignId,
            req.stepId,
            req.variantKey.trim.toUpperCase,
            req.contactId
          ).map {
            case Left(e)          => Left(e)
            case Right((_, resp)) => Right(resp)
          }
      }

  /** Authorized test send: renders for the selected sample contact but ALWAYS
    * delivers to the authenticated user's own email address — never to the
    * contact. Goes out through one of the campaign's assigned mailboxes.
    */
  private def testSendLogic(
      authorization: Option[String],
      workspaceId: UUID,
      campaignId: UUID,
      req: TestSendRequest
  ): IO[Either[(SttpStatus, Envelope), TestSendResponse]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          renderPreview(
            workspaceId,
            campaignId,
            req.stepId,
            req.variantKey.trim.toUpperCase,
            req.contactId
          ).flatMap {
            case Left(e)              => IO.pure(Left(e))
            case Right((_, rendered)) =>
              repos.mailboxIdsFor(campaignId).flatMap {
                case Nil =>
                  IO.pure(
                    Left(
                      failed(
                        ApiError
                          .Conflict("Assign a mailbox to this campaign first.")
                      )
                    )
                  )
                case mailboxIds =>
                  val accountId = req.emailAccountId
                    .filter(mailboxIds.contains)
                    .getOrElse(mailboxIds.head)
                  emailAccounts.findAccount(workspaceId, accountId).flatMap {
                    case None =>
                      IO.pure(Left(failed(ApiError.NotFound("email account"))))
                    case Some(account) =>
                      registry match
                        case None =>
                          IO.pure(
                            Left(
                              failed(
                                ApiError.Invalid(
                                  "Provider adapters are not available."
                                )
                              )
                            )
                          )
                        case Some(reg) =>
                          reg.adapter(account.provider) match
                            case None =>
                              IO.pure(
                                Left(
                                  failed(
                                    ApiError.Invalid(
                                      "This account's provider adapter is unavailable."
                                    )
                                  )
                                )
                              )
                            case Some(adapter) =>
                              val load = reg.credentialLoader(
                                emailAccounts,
                                workspaceId,
                                accountId,
                                account.provider
                              )
                              adapter
                                .submit(
                                  load,
                                  OutboundMessage(
                                    from = account.emailAddress,
                                    to = List(user.email),
                                    subject = "[TEST] " + rendered.subject,
                                    textBody = rendered.text,
                                    htmlBody = Some(rendered.html),
                                    headers = Map("X-Arcadence-Test" -> "true")
                                  )
                                )
                                .map {
                                  case Left(err) =>
                                    Left(
                                      failed(
                                        ApiError.Invalid(
                                          s"Test send failed: ${err.detail}"
                                        )
                                      )
                                    )
                                  case Right(messageId) =>
                                    Right(
                                      TestSendResponse(
                                        from = account.emailAddress,
                                        to = user.email,
                                        subject = "[TEST] " + rendered.subject,
                                        providerMessageId = messageId.value
                                      )
                                    )
                                }
                  }
              }
          }
      }

  private def templatesLogicList(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[TemplateDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos
            .listTemplates(workspaceId)
            .map(ts =>
              Right(
                ts.map(t => TemplateDto(t._1, t._2, t._3, t._4, t._5, t._6))
              )
            )
      }

  private def templatesLogicCreate(
      authorization: Option[String],
      workspaceId: UUID,
      req: SaveTemplateRequest
  ): IO[Either[(SttpStatus, Envelope), TemplateDto]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val name = req.name.trim
          if name.isEmpty || name.length > 120 then
            IO.pure(
              Left(
                failed(
                  ApiError.Invalid("Template name must be 1–120 characters.")
                )
              )
            )
          else
            repos
              .createTemplate(
                workspaceId,
                user.id,
                name,
                req.subject,
                req.preheader,
                Personalization.sanitizeHtml(req.bodyHtml),
                req.bodyText
              )
              .flatMap {
                case None =>
                  IO.pure(
                    Left(
                      failed(
                        ApiError
                          .Conflict("A template with this name already exists.")
                      )
                    )
                  )
                case Some(id) =>
                  IO.pure(
                    Right(
                      TemplateDto(
                        id,
                        name,
                        req.subject,
                        req.preheader,
                        req.bodyHtml,
                        req.bodyText
                      )
                    )
                  )
              }
      }

  private def templatesLogicDelete(
      authorization: Option[String],
      workspaceId: UUID,
      templateId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.deleteTemplate(workspaceId, templateId).map {
            case true  => Right(())
            case false => Left(failed(ApiError.NotFound("template")))
          }
      }

  private def blocksLogicList(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[BlockDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos
            .listBlocks(workspaceId)
            .map(bs => Right(bs.map(b => BlockDto(b._1, b._2, b._3, b._4))))
      }

  private def blocksLogicCreate(
      authorization: Option[String],
      workspaceId: UUID,
      req: SaveBlockRequest
  ): IO[Either[(SttpStatus, Envelope), BlockDto]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val name = req.name.trim
          if name.isEmpty || name.length > 120 then
            IO.pure(
              Left(
                failed(ApiError.Invalid("Block name must be 1–120 characters."))
              )
            )
          else
            repos
              .createBlock(
                workspaceId,
                user.id,
                name,
                Personalization.sanitizeHtml(req.bodyHtml),
                req.bodyText
              )
              .flatMap {
                case None =>
                  IO.pure(
                    Left(
                      failed(
                        ApiError
                          .Conflict("A block with this name already exists.")
                      )
                    )
                  )
                case Some(id) =>
                  IO.pure(Right(BlockDto(id, name, req.bodyHtml, req.bodyText)))
              }
      }

  private def blocksLogicDelete(
      authorization: Option[String],
      workspaceId: UUID,
      blockId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(authorization, workspaceId, Permission.ManageCampaigns)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.deleteBlock(workspaceId, blockId).map {
            case true  => Right(())
            case false => Left(failed(ApiError.NotFound("block")))
          }
      }

  // -- assembly --------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      putVariantContent.serverLogic(
        (workspaceId, campaignId, stepId, variantKey, auth, req) =>
          putVariantLogic(
            auth,
            workspaceId,
            campaignId,
            stepId,
            variantKey,
            req
          )
      ),
      previewVariant.serverLogic((workspaceId, campaignId, auth, req) =>
        previewLogic(auth, workspaceId, campaignId, req)
      ),
      testSendVariant.serverLogic((workspaceId, campaignId, auth, req) =>
        testSendLogic(auth, workspaceId, campaignId, req)
      ),
      listTemplates.serverLogic((workspaceId, auth) =>
        templatesLogicList(auth, workspaceId)
      ),
      createTemplate.serverLogic((workspaceId, auth, req) =>
        templatesLogicCreate(auth, workspaceId, req)
      ),
      deleteTemplate.serverLogic((workspaceId, templateId, auth) =>
        templatesLogicDelete(auth, workspaceId, templateId)
      ),
      listBlocks.serverLogic((workspaceId, auth) =>
        blocksLogicList(auth, workspaceId)
      ),
      createBlock.serverLogic((workspaceId, auth, req) =>
        blocksLogicCreate(auth, workspaceId, req)
      ),
      deleteBlock.serverLogic((workspaceId, blockId, auth) =>
        blocksLogicDelete(auth, workspaceId, blockId)
      )
    )
  )
