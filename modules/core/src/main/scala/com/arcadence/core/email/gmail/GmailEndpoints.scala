package com.arcadence.core.email.gmail

/** Google OAuth + Gmail API endpoint targets. Overridable so tests can point
  * the adapter at a stub server exercising the full flows (Phase 2.2).
  */
final case class GmailEndpoints(
    authorizeUrl: String,
    tokenUrl: String,
    userInfoUrl: String,
    gmailApiBaseUrl: String,
    revokeUrl: String
)

object GmailEndpoints:
  val Production: GmailEndpoints = GmailEndpoints(
    authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
    tokenUrl = "https://oauth2.googleapis.com/token",
    userInfoUrl = "https://openidconnect.googleapis.com/v1/userinfo",
    gmailApiBaseUrl = "https://gmail.googleapis.com/gmail/v1/users/me",
    revokeUrl = "https://accounts.google.com/o/oauth2/revoke"
  )

/** Minimum-necessary Gmail scopes (ADR-0021): send on behalf of the user, read
  * access for inbox synchronization/replies, and mailbox identity. gmail.send
  * and gmail.readonly are RESTRICTED scopes — production use requires Google's
  * OAuth verification incl. security assessment; a testing app is not
  * production approval.
  */
object GmailScopes:
  val Send: String = "https://www.googleapis.com/auth/gmail.send"
  val Readonly: String = "https://www.googleapis.com/auth/gmail.readonly"
  val Email: String = "https://www.googleapis.com/auth/userinfo.email"
  val All: List[String] = List(Send, Readonly, Email)
