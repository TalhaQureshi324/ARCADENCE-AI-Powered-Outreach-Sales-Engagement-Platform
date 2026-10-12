package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import Codecs.given
import com.arcadence.core.health.{
  HealthResponse,
  ReadyResponse,
  ServiceIdentity
}
import org.http4s.HttpRoutes
import org.http4s.server.middleware.CORS
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

/** Liveness and readiness endpoints, described once in Tapir and interpreted to
  * http4s routes. Readiness optionally includes dependency checks.
  */
final class HealthRoutes(
    identity: ServiceIdentity,
    dbCheck: Option[IO[Boolean]] = None
):

  private val healthEndpoint =
    endpoint.get.in("health").out(jsonBody[HealthResponse])

  private val readyEndpoint =
    endpoint.get.in("ready").out(jsonBody[ReadyResponse])

  private val liveness: IO[HealthResponse] =
    IO.pure(HealthResponse.up(identity))

  private val readiness: IO[ReadyResponse] = dbCheck match
    case None        => IO.pure(ReadyResponse.ready(identity))
    case Some(check) =>
      check.attempt.map {
        case Right(true) =>
          ReadyResponse.ready(
            identity,
            Map("process" -> "ok", "database" -> "ok")
          )
        case _ =>
          ReadyResponse(
            identity.service,
            identity.version,
            ReadyResponse.NotReady,
            Map("process" -> "ok", "database" -> "failed")
          )
      }

  private val interpreted: HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      List[ServerEndpoint[Fs2Streams[IO], IO]](
        healthEndpoint.serverLogicSuccess(_ => liveness),
        readyEndpoint.serverLogicSuccess(_ => readiness)
      )
    )

  // CORS: allow all origins without credentials for local development.
  // Environment-scoped origins arrive with the deployment hardening.
  private val corsPolicy =
    CORS.policy.withAllowOriginAll.withAllowCredentials(false)

  val http: HttpRoutes[IO] = corsPolicy(interpreted)
