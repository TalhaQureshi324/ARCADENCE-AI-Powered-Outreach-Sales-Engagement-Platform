package com.arcadence.core.email

/** What a provider can do, expressed separately from the provider adapter
  * itself: not every provider supports identical operations, and the UI/API
  * must derive what they offer from this matrix instead of hardcoding
  * provider-specific assumptions.
  */
enum AuthType(val label: String):
  /** OAuth 2.0 authorization-code with offline refresh. */
  case OAuth extends AuthType("oauth2")

  /** Username/password against SMTP submission / IMAP retrieval over TLS. */
  case PasswordTls extends AuthType("password_tls")

final case class ProviderCapabilities(
    provider: EmailProvider.Id,
    label: String,
    authType: AuthType,
    canSend: Boolean,
    canSyncInbox: Boolean,
    canSendReply: Boolean,
    canRefreshToken: Boolean,
    hasHealthCheck: Boolean,
    /** Human-readable scope/permission explanation shown on the connect card.
      */
    permissionsSummary: String,
    /** Which subphase wires the connect flow (honest UI availability). */
    connectFlow: String
)

/** The static capability catalog. Single source of truth for the GET
  * /api/v1/email-providers response and the dashboard connect cards.
  */
object ProviderCapabilities:

  val Catalog: Map[EmailProvider.Id, ProviderCapabilities] = Map(
    EmailProvider.Gmail -> ProviderCapabilities(
      provider = EmailProvider.Gmail,
      label = "Gmail / Google Workspace",
      authType = AuthType.OAuth,
      canSend = true,
      canSyncInbox = true,
      canSendReply = true,
      canRefreshToken = true,
      hasHealthCheck = true,
      permissionsSummary = "Read-only mailbox metadata, drafts and send on your behalf " +
        "(gmail.compose, gmail.readonly + userinfo.profile). Gmail scopes are " +
        "restricted by Google: production use requires OAuth app verification.",
      connectFlow = "subphase 2.2"
    ),
    EmailProvider.Microsoft -> ProviderCapabilities(
      provider = EmailProvider.Microsoft,
      label = "Microsoft 365 / Outlook",
      authType = AuthType.OAuth,
      canSend = true,
      canSyncInbox = true,
      canSendReply = true,
      canRefreshToken = true,
      hasHealthCheck = true,
      permissionsSummary =
        "Read and send mail as you via Microsoft Graph (Mail.Read, " +
          "Mail.Send, User.Read, offline_access). Tenant admins may block " +
          "third-party apps; consent is per-user unless admin-approved.",
      connectFlow = "subphase 2.3"
    ),
    EmailProvider.SmtpImap -> ProviderCapabilities(
      provider = EmailProvider.SmtpImap,
      label = "SMTP / IMAP",
      authType = AuthType.PasswordTls,
      canSend = true,
      canSyncInbox = true,
      canSendReply = true,
      canRefreshToken = false,
      hasHealthCheck = true,
      permissionsSummary =
        "Sends over authenticated TLS SMTP and reads over TLS IMAP using " +
          "your credentials. Use an app password where the provider demands " +
          "it — never disable two-factor protection.",
      connectFlow = "subphase 2.4"
    )
  )

  def forProvider(id: EmailProvider.Id): Option[ProviderCapabilities] =
    Catalog.get(id)

  val All: List[ProviderCapabilities] = EmailProvider.All.flatMap(Catalog.get)
