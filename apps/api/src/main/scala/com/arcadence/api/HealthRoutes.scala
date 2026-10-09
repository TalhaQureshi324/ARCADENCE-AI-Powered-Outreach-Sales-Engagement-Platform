package com.arcadence.api

import cats.effect.IO
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
  * http4s routes.
  */
final class HealthRoutes(identity: ServiceIdentity):

  import Codecs.given

  private val healthEndpoint =
    endpoint.get.in("health").out(jsonBody[HealthResponse])

  private val readyEndpoint =
    endpoint.get.in("ready").out(jsonBody[ReadyResponse])

  private val interpreted: HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      List[ServerEndpoint[Fs2Streams[IO], IO]](
        healthEndpoint.serverLogicSuccess[IO](_ =>
          IO.pure(HealthResponse.up(identity))
        ),
        readyEndpoint.serverLogicSuccess[IO](_ =>
          IO.pure(ReadyResponse.ready(identity))
        )
      )
    )

  // CORS: allow all origins without credentials for local development.
  // Phase 0.4 replaces this with environment-scoped origins per deployment.
  private val corsPolicy =
    CORS.policy.withAllowOriginAll.withAllowCredentials(false)

  val http: HttpRoutes[IO] = corsPolicy(interpreted)
