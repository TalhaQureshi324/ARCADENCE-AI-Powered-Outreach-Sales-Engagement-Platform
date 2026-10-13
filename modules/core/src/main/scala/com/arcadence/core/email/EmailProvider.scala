package com.arcadence.core.email

import cats.effect.IO

import java.time.OffsetDateTime

/** Provider identifiers, mirroring the `email_provider` Postgres enum. */
object EmailProvider:
  type Id = String // "gmail" | "microsoft" | "smtp_imap"

  val Gmail: Id = "gmail"
  val Microsoft: Id = "microsoft"
  val SmtpImap: Id = "smtp_imap"

  val All: List[Id] = List(Gmail, Microsoft, SmtpImap)

  def fromId(id: String): Either[String, Id] =
    All.find(_ == id).toRight(s"Unknown email provider: $id")

/** Provider-agnostic email account adapter contract (docs/ARCHITECTURE.md
  * §"Provider adapter layer"). Concrete adapters for Gmail (2.2), Microsoft
  * Graph (2.3) and SMTP/IMAP (2.4) implement this; the API and worker depend
  * only on the interface and the capability matrix.
  *
  * All operations are effectful and may fail with [[ProviderError]] values —
  * adapters never throw across this boundary. Errors carry a provider-specific
  * detail string safe for display (no credentials, no tokens).
  *
  * A credentials value is an opaque provider-specific payload (refresh token,
  * OAuth tokens, or SMTP/IMAP password) that adapters persist only through the
  * caller-provided [[store]] function — the framework encrypts it at rest; the
  * adapter never sees the ciphertext.
  */
trait EmailProvider:

  import EmailProvider.*

  def id: EmailProvider.Id
  def capabilities: ProviderCapabilities

  /** Exchanges an authorization result (code + redirect target) for stored
    * credentials. Only for OAuth providers; password providers return
    * ProviderError.NotSupported.
    */
  def completeAuthorization(
      authorization: AuthorizationResult,
      store: Credentials => IO[Unit]
  ): IO[Either[ProviderError, AccountIdentity]]

  /** Validates stored credentials (via [[load]]) against the provider. */
  def validateConnection(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, AccountIdentity]]

  /** Submits an outbound message. Returns the provider message identifier when
    * the submission is accepted (at-least-once: the caller deduplicates).
    */
  def submit(
      load: IO[Option[Credentials]],
      message: OutboundMessage
  ): IO[Either[ProviderError, ProviderMessageId]]

  /** Sends a reply in an existing provider thread. */
  def submitReply(
      load: IO[Option[Credentials]],
      reply: ReplyMessage
  ): IO[Either[ProviderError, ProviderMessageId]]

  /** Pulls new inbox messages since the given cursor. */
  def syncInbox(
      load: IO[Option[Credentials]],
      cursor: Option[String]
  ): IO[Either[ProviderError, InboxBatch]]

  /** Best-effort health probe (connection + quota) without side effects. */
  def healthCheck(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, HealthSnapshot]]

  /** Refreshes stored credentials when the provider supports it. */
  def refresh(
      load: IO[Option[Credentials]],
      store: Credentials => IO[Unit]
  ): IO[Either[ProviderError, Unit]]

  /** Revokes server-side access where the provider offers revocation. */
  def revokeAccess(
      load: IO[Option[Credentials]]
  ): IO[Either[ProviderError, Unit]]

final case class AccountIdentity(
    emailAddress: String,
    displayName: Option[String],
    /** Provider tenant / workspace identifier where applicable (Microsoft). */
    externalAccountId: Option[String]
)

final case class AuthorizationResult(
    code: String,
    redirectUri: String,
    /** Anti-CSRF state value; adapters validate before exchanging. */
    state: String,
    /** PKCE code_verifier when the flow used PKCE (S256). */
    codeVerifier: Option[String] = None
)

/** Opaque credential payload — concrete implementations live in adapters and
  * are serialized then encrypted by the storage layer, never logged.
  */
trait Credentials extends Product with Serializable

final case class OutboundMessage(
    from: String,
    to: List[String],
    subject: String,
    textBody: String,
    htmlBody: Option[String],
    headers: Map[String, String]
)

final case class ReplyMessage(
    providerThreadId: String,
    inReplyToMessageId: String,
    from: String,
    to: List[String],
    subject: String,
    textBody: String,
    htmlBody: Option[String]
)

final case class ProviderMessageId(value: String)

final case class InboxBatch(
    messages: List[InboxMessage],
    nextCursor: Option[String]
)

final case class InboxMessage(
    providerMessageId: String,
    providerThreadId: Option[String],
    internetMessageId: Option[String],
    fromAddress: String,
    subject: Option[String],
    receivedAt: OffsetDateTime
)

final case class HealthSnapshot(
    reachable: Boolean,
    quotaRemaining: Option[Int],
    checkedAt: OffsetDateTime
)

/** Provider failures as values, safe to surface (no secrets in detail). */
enum ProviderError:
  case NotSupported(operation: String)
  case InvalidCredentials(detail: String)
  case PermissionDenied(detail: String)
  case RateLimited(retryAfterSeconds: Option[Int])
  case Throttled(detail: String)
  case Unreachable(detail: String)
  case Unexpected(detail: String)

object ProviderError:
  extension (error: ProviderError)
    def detail: String = error match
      case NotSupported(op)       => s"Operation not supported: $op"
      case InvalidCredentials(d)  => s"Credentials rejected: $d"
      case PermissionDenied(d)    => s"Permission denied: $d"
      case RateLimited(Some(sec)) => s"Rate limited; retry after ${sec}s"
      case RateLimited(None)      => "Rate limited; retry later"
      case Throttled(d)           => s"Provider throttling: $d"
      case Unreachable(d)         => s"Provider unreachable: $d"
      case Unexpected(d)          => s"Unexpected provider error: $d"
