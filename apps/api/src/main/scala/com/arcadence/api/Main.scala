package com.arcadence.api

import cats.effect.{ExitCode, IO, IOApp}
import com.arcadence.core.build.BuildInfo
import com.arcadence.core.config.ApiConfig
import com.arcadence.core.health.ServiceIdentity
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Router
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import scala.concurrent.duration.*

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    ApiConfig.fromEnv(sys.env) match
      case Left(error) =>
        IO.consoleForIO.errorln(
          s"arcadence-api: invalid configuration: $error"
        ) *> IO.pure(ExitCode.Error)

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

        val app = Router("/" -> HealthRoutes(identity).http).orNotFound

        applyLogLevel *>
          EmberServerBuilder
            .default[IO]
            .withHost(host"0.0.0.0")
            // API_PORT is validated to [1, 65535] by ApiConfig, so this fallback is unreachable.
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
