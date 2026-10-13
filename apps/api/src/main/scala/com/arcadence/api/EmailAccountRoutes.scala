package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.EmailAccountDtos.*
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.email.{EmailAccountStatus, ProviderCapabilities}
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

object EmailAccountDtos:
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import io.circe.{Decoder, Encoder}
  import sttp.tapir.Schema

  final case class ProviderDto(
      provider: String,
      label: String,
      authType: String,
      canSend: Boolean,
      canSyncInbox: Boolean,
      canSendReply: Boolean,
      canRefreshToken: Boolean,
      hasHealthCheck: Boolean,
      permissionsSummary: String,
      connectFlow: String,
      /** True when this server's connect flow is ready (credentials present).
        */
      configured: Boolean
  )
  object ProviderDto:
    given Encoder[ProviderDto] = deriveEncoder
    given Decoder[ProviderDto] = deriveDecoder
    given Schema[ProviderDto] = Schema.derived

  final case class AccountGroupDto(
      id: UUID,
      name: String,
      accountCount: Int,
      createdAt: OffsetDateTime
  )
  object AccountGroupDto:
    given Encoder[AccountGroupDto] = deriveEncoder
    given Decoder[AccountGroupDto] = deriveDecoder
    given Schema[AccountGroupDto] = Schema.derived

  final case class CreateGroupRequest(name: String)
  object CreateGroupRequest:
    given Encoder[CreateGroupRequest] = deriveEncoder
    given Decoder[CreateGroupRequest] = deriveDecoder
    given Schema[CreateGroupRequest] = Schema.derived

  final case class UpdateGroupRequest(name: String)
  object UpdateGroupRequest:
    given Encoder[UpdateGroupRequest] = deriveEncoder
    given Decoder[UpdateGroupRequest] = deriveDecoder
    given Schema[UpdateGroupRequest] = Schema.derived

  final case class EmailAccountDto(
      id: UUID,
      accountGroupId: Option[UUID],
      accountGroupName: Option[String],
      provider: String,
      emailAddress: String,
      displayName: Option[String],
      status: String,
      statusMessage: Option[String],
      dailySendCap: Option[Int],
      isDefault: Boolean,
      lastSyncedAt: Option[OffsetDateTime],
      createdAt: OffsetDateTime
  )
  object EmailAccountDto:
    given Encoder[EmailAccountDto] = deriveEncoder
    given Decoder[EmailAccountDto] = deriveDecoder
    given Schema[EmailAccountDto] = Schema.derived

  /** PATCH semantics: absent field = unchanged; explicit null = clear. circe's
    * derived Option decoding maps null to None (indistinguishable from absent),
    * so these fields use a null-aware decoder.
    */
  final case class UpdateAccountRequest(
      displayName: Option[Option[String]] = None,
      accountGroupId: Option[Option[UUID]] = None,
      dailySendCap: Option[Option[Int]] = None
  )
  object UpdateAccountRequest:
    given Encoder[UpdateAccountRequest] = deriveEncoder
    given Schema[UpdateAccountRequest] = Schema.derived

    /** absent field → None (unchanged); explicit null → Some(None) (clear). */
    given Decoder[UpdateAccountRequest] = Decoder.instance { c =>
      def field[A: Decoder](name: String): Decoder.Result[Option[Option[A]]] =
        c.downField(name).focus match
          case None                => Right(None)
          case Some(v) if v.isNull => Right(Some(None))
          case Some(v)             => v.as[A].map(a => Some(Some(a)))
      for
        displayName <- field[String]("displayName")
        accountGroupId <- field[UUID]("accountGroupId")
        dailySendCap <- field[Int]("dailySendCap")
      yield UpdateAccountRequest(displayName, accountGroupId, dailySendCap)
    }

  final case class HealthEventDto(
      id: UUID,
      eventType: String,
      message: Option[String],
      occurredAt: OffsetDateTime
  )
  object HealthEventDto:
    given Encoder[HealthEventDto] = deriveEncoder
    given Decoder[HealthEventDto] = deriveDecoder
    given Schema[HealthEventDto] = Schema.derived

/** Email account management API (Phase 2.1): capability matrix, account groups,
  * account reads/lifecycle. Credentials are never part of any response; connect
  * flows that create accounts arrive with subphases 2.2–2.4.
  */
final class EmailAccountRoutes(
    authenticator: Authenticator,
    repos: EmailAccountRepos,
    authorizer: Authorizer,
    registry: Option[ProviderRegistry] = None,
    /** Best-effort provider-side revocation before credential purge (OAuth
      * adapters); default no-op for tests without adapters.
      */
    revokeCredentials: (String, UUID, UUID) => IO[Unit] = (_, _, _) => IO.unit
):
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

  // -- mapping ---------------------------------------------------------------

  private def providerDto(c: ProviderCapabilities): ProviderDto =
    ProviderDto(
      provider = c.provider,
      label = c.label,
      authType = c.authType.label,
      canSend = c.canSend,
      canSyncInbox = c.canSyncInbox,
      canSendReply = c.canSendReply,
      canRefreshToken = c.canRefreshToken,
      hasHealthCheck = c.hasHealthCheck,
      permissionsSummary = c.permissionsSummary,
      connectFlow = c.connectFlow,
      configured = registry.exists(_.configured(c.provider))
    )

  private def groupDto(g: repos.GroupView): AccountGroupDto =
    AccountGroupDto(g.id, g.name, g.accountCount, g.createdAt)

  private def accountDto(a: repos.AccountView): EmailAccountDto =
    EmailAccountDto(
      id = a.id,
      accountGroupId = a.accountGroupId,
      accountGroupName = a.accountGroupName,
      provider = a.provider,
      emailAddress = a.emailAddress,
      displayName = a.displayName,
      status = a.status,
      statusMessage = a.statusMessage,
      dailySendCap = a.dailySendCap,
      isDefault = a.isDefault,
      lastSyncedAt = a.lastSyncedAt,
      createdAt = a.createdAt
    )

  private def healthDto(e: repos.HealthEventView): HealthEventDto =
    HealthEventDto(e.id, e.eventType, e.message, e.occurredAt)

  private def validName(name: String): Either[ApiError, String] =
    val trimmed = name.trim
    if trimmed.isEmpty || trimmed.length > 80 then
      Left(ApiError.Invalid("Name must be 1–80 characters."))
    else Right(trimmed)

  // -- endpoints -------------------------------------------------------------

  private val listProviders = endpoint.get
    .in("api" / "v1" / "email-providers")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[ProviderDto]])
    .errorOut(errorOut)

  private def listAccounts = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "email-accounts"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[EmailAccountDto]])
    .errorOut(errorOut)

  private val getAccount = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "email-accounts" / path[UUID](
        "accountId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[EmailAccountDto])
    .errorOut(errorOut)

  private val updateAccount = endpoint.patch
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "email-accounts" / path[UUID](
        "accountId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[UpdateAccountRequest])
    .out(jsonBody[EmailAccountDto])
    .errorOut(errorOut)

  private val pauseAccount = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "email-accounts" / path[UUID](
        "accountId"
      ) / "pause"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[EmailAccountDto])
    .errorOut(errorOut)

  private val resumeAccount = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "email-accounts" / path[UUID](
        "accountId"
      ) / "resume"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[EmailAccountDto])
    .errorOut(errorOut)

  private val disconnectAccount = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "email-accounts" / path[UUID](
        "accountId"
      ) / "disconnect"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[EmailAccountDto])
    .errorOut(errorOut)

  private val deleteAccount = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "email-accounts" / path[UUID](
        "accountId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  private val accountHealth = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "email-accounts" / path[UUID](
        "accountId"
      ) / "health"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[HealthEventDto]])
    .errorOut(errorOut)

  private val listGroups = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "account-groups"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[AccountGroupDto]])
    .errorOut(errorOut)

  private val createGroup = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "account-groups"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[CreateGroupRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[AccountGroupDto])
    .errorOut(errorOut)

  private val updateGroup = endpoint.patch
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "account-groups" / path[UUID](
        "groupId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[UpdateGroupRequest])
    .out(jsonBody[AccountGroupDto])
    .errorOut(errorOut)

  private val deleteGroup = endpoint.delete
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "account-groups" / path[UUID](
        "groupId"
      )
    )
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.NoContent))
    .errorOut(errorOut)

  // -- logic -----------------------------------------------------------------

  private def listProvidersLogic(
      authorization: Option[String]
  ): IO[Either[(SttpStatus, Envelope), List[ProviderDto]]] =
    verifyBearer(authorization).map {
      case Left(envelope) => Left(envelope)
      case Right(_)       => Right(ProviderCapabilities.All.map(providerDto))
    }

  private def listAccountsLogic(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[EmailAccountDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.listAccounts(workspaceId).map(a => Right(a.map(accountDto)))
      }

  private def getAccountLogic(
      authorization: Option[String],
      workspaceId: UUID,
      accountId: UUID
  ): IO[Either[(SttpStatus, Envelope), EmailAccountDto]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.findAccount(workspaceId, accountId).map {
            case None     => Left(failed(ApiError.NotFound("email account")))
            case Some(ac) => Right(accountDto(ac))
          }
      }

  private def updateAccountLogic(
      authorization: Option[String],
      workspaceId: UUID,
      accountId: UUID,
      request: UpdateAccountRequest
  ): IO[Either[(SttpStatus, Envelope), EmailAccountDto]] =
    requirePermission(
      authorization,
      workspaceId,
      Permission.ManageEmailAccounts
    )
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          val validation: Either[ApiError, repos.AccountPatch] =
            for
              displayName <- request.displayName match
                case None =>
                  Right(None): Either[ApiError, Option[Option[String]]]
                case Some(None)       => Right(Some(None))
                case Some(Some(name)) =>
                  val trimmed = name.trim
                  if trimmed.isEmpty || trimmed.length > 120 then
                    Left(
                      ApiError.Invalid("Display name must be 1–120 characters.")
                    )
                  else Right(Some(Some(trimmed)))
              cap <- request.dailySendCap match
                case None => Right(None): Either[ApiError, Option[Option[Int]]]
                case Some(None)      => Right(Some(None))
                case Some(Some(cap)) =>
                  if cap < 1 || cap > 10000 then
                    Left(
                      ApiError.Invalid(
                        "Daily send cap must be between 1 and 10000."
                      )
                    )
                  else Right(Some(Some(cap)))
            yield repos.AccountPatch(
              displayName = displayName,
              accountGroupId = request.accountGroupId,
              dailySendCap = cap
            )
          validation match
            case Left(error)  => IO.pure(Left(failed(error)))
            case Right(patch) =>
              (for
                account <- repos.findAccount(workspaceId, accountId)
                groupOk <- patch.accountGroupId.flatten match
                  case None                   => IO.pure(true)
                  case Some(requestedGroupId) =>
                    repos
                      .listGroups(workspaceId)
                      .map(groups => groups.exists(_.id == requestedGroupId))
                // -1 = account missing, -2 = group missing, 0 = no-op patch
                updated <-
                  if account.isEmpty then IO.pure(-1)
                  else if !groupOk then IO.pure(-2)
                  else repos.updateAccount(workspaceId, accountId, patch)
                _ <-
                  if updated > 0 then
                    repos.audit(
                      workspaceId,
                      user.id,
                      "email_account.updated",
                      "email_account",
                      accountId.toString
                    )
                  else IO.unit
                view <- repos.findAccount(workspaceId, accountId)
              yield (updated, view)).map {
                case (-1, _) => Left(failed(ApiError.NotFound("email account")))
                case (-2, _) => Left(failed(ApiError.NotFound("account group")))
                case (_, None) =>
                  Left(failed(ApiError.NotFound("email account")))
                case (_, Some(v)) => Right(accountDto(v))
              }
      }

  /** pause / resume / disconnect share one shape: validate a legal transition,
    * apply it race-safely, audit, and return the fresh view.
    */
  private def transitionLogic(
      authorization: Option[String],
      workspaceId: UUID,
      accountId: UUID,
      from: Set[EmailAccountStatus],
      to: EmailAccountStatus,
      eventType: String,
      eventMessage: Option[String],
      auditAction: String,
      clearCredentials: Boolean = false
  ): IO[Either[(SttpStatus, Envelope), EmailAccountDto]] =
    requirePermission(
      authorization,
      workspaceId,
      Permission.ManageEmailAccounts
    )
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          (for
            account <- repos.findAccount(workspaceId, accountId)
            current = account
              .flatMap(a => EmailAccountStatus.fromLabel(a.status).toOption)
            allowed = current.exists(c => from.contains(c))
            applied <-
              if account.isEmpty then IO.pure(false)
              else if !allowed then IO.pure(false)
              else
                repos
                  .transitionStatus(
                    workspaceId,
                    accountId,
                    fromStatus = current.get.label,
                    toStatus = to.label,
                    statusMessage = eventMessage,
                    eventType = eventType,
                    eventMessage = eventMessage
                  )
            // Revoke at the provider (best effort) before the local purge so
            // the refresh token stops working even if the purge then fails.
            _ <-
              if applied && clearCredentials then
                account.traverse_(a =>
                  revokeCredentials(a.provider, workspaceId, accountId)
                )
              else IO.unit
            _ <-
              if applied && clearCredentials then
                repos.purgeCredentials(workspaceId, accountId)
              else IO.unit
            _ <-
              if applied then
                repos.audit(
                  workspaceId,
                  user.id,
                  auditAction,
                  "email_account",
                  accountId.toString,
                  Json.obj("status" -> Json.fromString(to.label))
                )
              else IO.unit
            view <- repos.findAccount(workspaceId, accountId)
          yield
            if account.isEmpty then
              Left(failed(ApiError.NotFound("email account")))
            else if !allowed then
              Left(
                failed(
                  ApiError.Conflict(
                    s"Cannot ${auditAction.split('.').last} an account in status ${current.map(_.label).getOrElse("?")}."
                  )
                )
              )
            else
              view
                .map(a => Right(accountDto(a)))
                .getOrElse(Left(failed(ApiError.NotFound("email account"))))
          )
      }

  private def deleteAccountLogic(
      authorization: Option[String],
      workspaceId: UUID,
      accountId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(
      authorization,
      workspaceId,
      Permission.ManageEmailAccounts
    )
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          (for
            deleted <- repos.deleteAccount(workspaceId, accountId)
            _ <-
              if deleted then
                repos.audit(
                  workspaceId,
                  user.id,
                  "email_account.deleted",
                  "email_account",
                  accountId.toString
                )
              else IO.unit
          yield deleted).map {
            case false => Left(failed(ApiError.NotFound("email account")))
            case true  => Right(())
          }
      }

  private def accountHealthLogic(
      authorization: Option[String],
      workspaceId: UUID,
      accountId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[HealthEventDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          (for
            account <- repos.findAccount(workspaceId, accountId)
            events <- account.traverse(_ =>
              repos.listHealthEvents(workspaceId, accountId)
            )
          yield (account, events).tupled).map {
            case None => Left(failed(ApiError.NotFound("email account")))
            case Some((_, events)) => Right(events.map(healthDto))
          }
      }

  private def listGroupsLogic(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[AccountGroupDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.listGroups(workspaceId).map(g => Right(g.map(groupDto)))
      }

  private def createGroupLogic(
      authorization: Option[String],
      workspaceId: UUID,
      request: CreateGroupRequest
  ): IO[Either[(SttpStatus, Envelope), AccountGroupDto]] =
    requirePermission(
      authorization,
      workspaceId,
      Permission.ManageEmailAccounts
    )
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          validName(request.name) match
            case Left(error) => IO.pure(Left(failed(error)))
            case Right(name) =>
              (for
                existing <- repos.listGroups(workspaceId)
                created <- existing.find(_.name.equalsIgnoreCase(name)) match
                  case Some(_) =>
                    IO.pure(None) // caller maps to 409
                  case None =>
                    repos.createGroup(workspaceId, name)
                _ <- created.traverse(g =>
                  repos.audit(
                    workspaceId,
                    user.id,
                    "account_group.created",
                    "account_group",
                    g.id.toString
                  )
                )
              yield created).map {
                case None =>
                  Left(
                    failed(
                      ApiError.Conflict(
                        "A group with this name already exists."
                      )
                    )
                  )
                case Some(g) => Right(groupDto(g))
              }
      }

  private def updateGroupLogic(
      authorization: Option[String],
      workspaceId: UUID,
      groupId: UUID,
      request: UpdateGroupRequest
  ): IO[Either[(SttpStatus, Envelope), AccountGroupDto]] =
    requirePermission(
      authorization,
      workspaceId,
      Permission.ManageEmailAccounts
    )
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          validName(request.name) match
            case Left(error) => IO.pure(Left(failed(error)))
            case Right(name) =>
              (for
                updated <- repos.renameGroup(workspaceId, groupId, name)
                _ <-
                  if updated > 0 then
                    repos.audit(
                      workspaceId,
                      user.id,
                      "account_group.renamed",
                      "account_group",
                      groupId.toString
                    )
                  else IO.unit
                groups <- repos.listGroups(workspaceId)
              yield (updated, groups.find(_.id == groupId))).map {
                case (_, Some(g)) => Right(groupDto(g))
                case _ => Left(failed(ApiError.NotFound("account group")))
              }
      }

  private def deleteGroupLogic(
      authorization: Option[String],
      workspaceId: UUID,
      groupId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    requirePermission(
      authorization,
      workspaceId,
      Permission.ManageEmailAccounts
    )
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          (for
            deleted <- repos.deleteGroup(workspaceId, groupId)
            _ <-
              if deleted > 0 then
                repos.audit(
                  workspaceId,
                  user.id,
                  "account_group.deleted",
                  "account_group",
                  groupId.toString
                )
              else IO.unit
          yield deleted).map {
            case 0 => Left(failed(ApiError.NotFound("account group")))
            case _ => Right(())
          }
      }

  // -- assembly ---------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      listProviders.serverLogic(auth => listProvidersLogic(auth)),
      listAccounts.serverLogic((workspaceId, auth) =>
        listAccountsLogic(auth, workspaceId)
      ),
      getAccount.serverLogic((workspaceId, accountId, auth) =>
        getAccountLogic(auth, workspaceId, accountId)
      ),
      updateAccount.serverLogic((workspaceId, accountId, auth, req) =>
        updateAccountLogic(auth, workspaceId, accountId, req)
      ),
      pauseAccount.serverLogic((workspaceId, accountId, auth) =>
        transitionLogic(
          auth,
          workspaceId,
          accountId,
          from = Set(EmailAccountStatus.Connected),
          to = EmailAccountStatus.Suspended,
          eventType = "paused",
          eventMessage = Some("Account paused by an operator."),
          auditAction = "email_account.paused"
        )
      ),
      resumeAccount.serverLogic((workspaceId, accountId, auth) =>
        transitionLogic(
          auth,
          workspaceId,
          accountId,
          from = Set(EmailAccountStatus.Suspended),
          to = EmailAccountStatus.Connected,
          eventType = "resumed",
          eventMessage = Some("Account resumed by an operator."),
          auditAction = "email_account.resumed"
        )
      ),
      disconnectAccount.serverLogic((workspaceId, accountId, auth) =>
        transitionLogic(
          auth,
          workspaceId,
          accountId,
          from = Set(
            EmailAccountStatus.Connected,
            EmailAccountStatus.ReconnectRequired,
            EmailAccountStatus.RateLimited,
            EmailAccountStatus.Suspended,
            EmailAccountStatus.Error
          ),
          to = EmailAccountStatus.Disconnected,
          eventType = "disconnected",
          eventMessage =
            Some("Account disconnected; stored credentials purged."),
          auditAction = "email_account.disconnected",
          clearCredentials = true
        )
      ),
      deleteAccount.serverLogic((workspaceId, accountId, auth) =>
        deleteAccountLogic(auth, workspaceId, accountId)
      ),
      accountHealth.serverLogic((workspaceId, accountId, auth) =>
        accountHealthLogic(auth, workspaceId, accountId)
      ),
      listGroups.serverLogic((workspaceId, auth) =>
        listGroupsLogic(auth, workspaceId)
      ),
      createGroup.serverLogic((workspaceId, auth, req) =>
        createGroupLogic(auth, workspaceId, req)
      ),
      updateGroup.serverLogic((workspaceId, groupId, auth, req) =>
        updateGroupLogic(auth, workspaceId, groupId, req)
      ),
      deleteGroup.serverLogic((workspaceId, groupId, auth) =>
        deleteGroupLogic(auth, workspaceId, groupId)
      )
    )
  )
