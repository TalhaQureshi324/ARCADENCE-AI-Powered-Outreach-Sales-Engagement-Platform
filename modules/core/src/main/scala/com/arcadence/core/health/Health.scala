package com.arcadence.core.health

/** Identity advertised by every Arcadence process on its health endpoints. */
final case class ServiceIdentity(service: String, version: String)

final case class HealthResponse(
    service: String,
    version: String,
    status: String
)

object HealthResponse:
  val Up = "up"

  def up(identity: ServiceIdentity): HealthResponse =
    HealthResponse(identity.service, identity.version, Up)

final case class ReadyResponse(
    service: String,
    version: String,
    status: String,
    checks: Map[String, String]
)

object ReadyResponse:
  val Ready = "ready"

  /** Process-level readiness only; database and dependency checks are added in
    * later phases.
    */
  def ready(identity: ServiceIdentity): ReadyResponse =
    ReadyResponse(
      identity.service,
      identity.version,
      Ready,
      Map("process" -> "ok")
    )
