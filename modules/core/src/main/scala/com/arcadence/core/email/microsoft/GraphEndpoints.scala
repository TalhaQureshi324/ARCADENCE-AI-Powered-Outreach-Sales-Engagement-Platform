package com.arcadence.core.email.microsoft

/** Microsoft identity-platform + Graph endpoint targets (tenant-aware).
  *
  * @param tenant
  *   `common` (any account), `organizations`, `consumers`, or a specific tenant
  *   id — recorded per the Phase 2 plan ("record permission, tenant and account
  *   restrictions").
  */
final case class GraphEndpoints(
    tenant: String,
    graphApiBaseUrl: String
) extends Serializable:

  def authorizeUrl: String =
    s"https://login.microsoftonline.com/$tenant/oauth2/v2.0/authorize"
  def tokenUrl: String =
    s"https://login.microsoftonline.com/$tenant/oauth2/v2.0/token"
  def meUrl: String = s"$graphApiBaseUrl/me"
  def sendDraftUrl(draftId: String): String =
    s"$graphApiBaseUrl/me/messages/$draftId/send"
  def createDraftUrl: String = s"$graphApiBaseUrl/me/messages"
  def createReplyUrl(parentMessageId: String): String =
    s"$graphApiBaseUrl/me/messages/$parentMessageId/createReply"
  def inboxMessagesUrl: String =
    s"$graphApiBaseUrl/me/mailFolders/inbox/messages"
  def revokeUrl: String = s"$graphApiBaseUrl/me/revokeSignInSessions"

object GraphEndpoints:
  val DefaultTenant: String = "common"

  def Production(tenant: String = DefaultTenant): GraphEndpoints =
    GraphEndpoints(
      tenant = tenant,
      graphApiBaseUrl = "https://graph.microsoft.com/v1.0"
    )

/** Delegated Microsoft Graph permissions — minimum necessary (ADR-0022): send
  * mail as the user, read mail for inbox synchronization/replies, basic
  * profile, and refresh tokens. Tenant admins may block third-party apps
  * entirely; consent is per-user unless an admin approves tenant-wide.
  */
object GraphScopes:
  val MailSend: String = "https://graph.microsoft.com/Mail.Send"
  val MailRead: String = "https://graph.microsoft.com/Mail.Read"
  val UserRead: String = "https://graph.microsoft.com/User.Read"
  val OfflineAccess: String = "offline_access"
  val OpenId: String = "openid"
  val Email: String = "email"
  val All: List[String] =
    List(OpenId, Email, MailSend, MailRead, UserRead, OfflineAccess)
