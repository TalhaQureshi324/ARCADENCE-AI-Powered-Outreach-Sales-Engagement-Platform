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
  val NotReady = "not_ready"

  /** Process-level readiness only; callers with dependencies pass explicit
    * checks.
    */
  def ready(
      identity: ServiceIdentity,
      checks: Map[String, String] = Map("process" -> "ok")
  ): ReadyResponse =
    ReadyResponse(identity.service, identity.version, Ready, checks)
