package com.arcadence.core.email

/** Account lifecycle states, mirroring the `connection_status` Postgres enum.
  *
  * Transition rules are enforced in the API (never in the database): health
  * problems (auth failures, provider throttles) move accounts towards
  * `reconnect_required` / `rate_limited` / `error`; operators pause, resume and
  * disconnect; only a successful provider connect flow may bring an account
  * back to `connected`.
  */
enum EmailAccountStatus(val label: String):

  case Connected extends EmailAccountStatus("connected")
  case ReconnectRequired extends EmailAccountStatus("reconnect_required")
  case RateLimited extends EmailAccountStatus("rate_limited")
  case Suspended extends EmailAccountStatus("suspended")
  case Error extends EmailAccountStatus("error")
  case Disconnected extends EmailAccountStatus("disconnected")

object EmailAccountStatus:

  val All: List[EmailAccountStatus] = List(
    Connected,
    ReconnectRequired,
    RateLimited,
    Suspended,
    Error,
    Disconnected
  )

  /** Parses a label from the database. Unknown labels are a data error. */
  def fromLabel(label: String): Either[String, EmailAccountStatus] =
    All.find(_.label == label).toRight(s"Unknown account status: $label")

  /** Legal operator/system transitions. Anything not listed is rejected with
    * the current status kept intact.
    */
  def canTransitionTo(
      from: EmailAccountStatus,
      to: EmailAccountStatus
  ): Boolean =
    (from, to) match
      // Operator pause/resume
      case (Connected, Suspended) => true
      case (Suspended, Connected) => true
      // Operator disconnect from any active state
      case (
            Connected | ReconnectRequired | RateLimited | Error | Suspended,
            Disconnected
          ) =>
        true
      // Recovery paths (worker health checks / reconnect flows)
      case (ReconnectRequired | RateLimited | Error, Connected) => true
      // Health degradation from any active state
      case (Connected | ReconnectRequired | RateLimited | Suspended, Error) =>
        true
      case (Connected, ReconnectRequired) => true
      case (Connected, RateLimited)       => true
      // Everything else (e.g. Disconnected -> anything without a connect flow)
      case _ => false
