package com.arcadence.api

import cats.data.Kleisli
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import com.arcadence.core.build.BuildInfo
import com.arcadence.core.config.ApiConfig
import com.arcadence.core.health.ServiceIdentity
import com.comcast.ip4s.*
import doobie.implicits.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, HttpRoutes}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import scala.concurrent.duration.*

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    ApiConfig.fromEnv(sys.env) match
      case Left(error) =>
        IO.consoleForIO
          .errorln(s"arcadence-api: invalid configuration: $error") *> IO.pure(
          ExitCode.Error
        )

      case Right(config) =>
        given LoggerFactory[IO] = Slf4jFactory.create[IO]
        val logger = LoggerFactory[IO].getLogger
        val identity = ServiceIdentity("arcadence-api", BuildInfo.version)

        val applyLogLevel: IO[Unit] = IO {
          val root = org.slf4j.LoggerFactory
            .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            .asInstanceOf[ch.qos.logback.classic.Logger]
          root.setLevel(ch.qos.logback.classic.Level.valueOf(config.logLevel))
        }

        val resources: IO[(doobie.Transactor[IO], Authenticator)] =
          for
            transactor <- Db.transactor(config.databaseUrl)
            jwksUri <- IO.fromEither(
              org.http4s.Uri
                .fromString(config.jwksUrl)
                .leftMap(e =>
                  new IllegalArgumentException(
                    s"Invalid SUPABASE_JWKS_URL: ${e.message}"
                  )
                )
            )
            authenticator = SupabaseJwtAuthenticator(
              config.jwtIssuer,
              config.jwtAudience,
              jwksUri
            )
          yield (transactor, authenticator)

        resources.flatMap { (transactor, authenticator) =>
          val repositories = new WorkspaceRepos(transactor)
          val authorizer = new Authorizer(transactor)
          val workspaceRoutes = new WorkspaceRoutes(
            authenticator = authenticator,
            repos = repositories,
            authorizer = authorizer,
            appOrigin = "http://localhost:3000"
          )

          val dbReady: IO[Boolean] =
            sql"select 1"
              .query[Int]
              .unique
              .transact(transactor)
              .attempt
              .map(_.isRight)

          val health = new HealthRoutes(identity, dbCheck = Some(dbReady))

          // Browser calls (localhost:3000) hit WorkspaceRoutes directly —
          // CORS must cover the whole API surface, not just health.
          import org.http4s.Method.*
          val corsPolicy =
            org.http4s.server.middleware.CORS.policy.withAllowOriginAll
              .withAllowCredentials(false)
              .withAllowMethodsIn(Set(GET, POST, PATCH, PUT, DELETE, OPTIONS))
              .withAllowHeadersIn(
                Set(
                  org.typelevel.ci.CIString("Authorization"),
                  org.typelevel.ci.CIString("Content-Type"),
                  org.typelevel.ci.CIString("Idempotency-Key")
                )
              )
          // Hardening: per-caller rate limiting + security headers (Phase 1.4).
          val hardenedRoutes: HttpRoutes[IO] =
            RateLimit(config.rateLimitPerMinute)(
              SecurityHeaders(workspaceRoutes.http)
            )

          val allRoutes: HttpRoutes[IO] =
            corsPolicy(health.http <+> hardenedRoutes)

          val routes =
            new RequestContext(logger)(new ErrorEnvelope(logger)(allRoutes))

          // JSON error envelope for unmatched paths too (plain Router would
          // return text). Unmatched paths bypass RequestContext, so a request
          // id is generated here when absent.
          val app: HttpApp[IO] = Kleisli { req =>
            val requestId = Option(ApiResponses.requestIdOf(req))
              .filter(_.nonEmpty)
              .getOrElse(java.util.UUID.randomUUID().toString)
            routes(req).getOrElseF(IO.pure(ApiResponses.notFound(requestId)))
          }

          applyLogLevel *>
            logger.info(
              s"arcadence-api ${BuildInfo.version} starting on port ${config.port}"
            ) *>
            EmberServerBuilder
              .default[IO]
              .withHost(host"0.0.0.0")
              // API_PORT is validated to [1, 65535] by ApiConfig; fallback unreachable.
              .withPort(Port.fromInt(config.port).getOrElse(port"8080"))
              .withHttpApp(app)
              .withShutdownTimeout(5.seconds)
              .build
              .use(_ =>
                logger.info(
                  s"arcadence-api ${BuildInfo.version} listening on port ${config.port}"
                ) *> IO.never
              )
              .as(ExitCode.Success)
        }
