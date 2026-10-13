package com.arcadence.api

import io.circe.{Decoder, Encoder}
import sttp.tapir.Schema
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import org.http4s.Status

/** Machine-readable domain errors mapped to the API error envelope (docs/API.md
  * §2). Every business rule violation is one of these — no bare exceptions
  * across the boundary.
  */
sealed trait ApiError:
  def code: String
  def message: String
  def status: Status

object ApiError:
  case object Unauthorized extends ApiError:
    val code = "unauthorized"
    val message = "Authentication is required."
    val status = Status.Unauthorized

  case object WorkspaceNotFound extends ApiError:
    val code = "workspace_not_found"
    val message = "The requested workspace does not exist."
    val status = Status.NotFound

  final case class InsufficientRole(required: String) extends ApiError:
    val code = "insufficient_role"
    val message = s"Your role does not allow this action (requires: $required)."
    val status = Status.Forbidden

  final case class NotFound(resource: String) extends ApiError:
    val code = "not_found"
    val message = s"The requested $resource does not exist."
    val status = Status.NotFound

  final case class Invalid(message: String) extends ApiError:
    val code = "validation_failed"
    val status = Status.UnprocessableEntity

  final case class Conflict(message: String) extends ApiError:
    val code = "conflict"
    val status = Status.Conflict

  final case class ProviderNotConfigured(detail: String) extends ApiError:
    val code = "not_configured"
    val message = detail
    val status = Status.ServiceUnavailable

  /** Provider-side failure surfaced verbatim (safe detail, no secrets). */
  final case class Provider(detail: String) extends ApiError:
    val code = "provider_error"
    val message = s"Provider rejected the operation: $detail"
    val status = Status.BadGateway

  /** Wire shape of the error envelope (without the requestId, which the edge
    * middleware attaches).
    */
  final case class Envelope(code: String, message: String)

  object Envelope:
    given Encoder[Envelope] = deriveEncoder
    given Decoder[Envelope] = deriveDecoder
    given Schema[Envelope] = Schema.derived

  extension (error: ApiError)
    def toEnvelope: Envelope = Envelope(error.code, error.message)
