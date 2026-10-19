package com.arcadence.worker

import cats.effect.{ExitCode, IO, IOApp}
import com.arcadence.core.build.BuildInfo
import com.arcadence.core.config.WorkerConfig
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    WorkerConfig.fromEnv(sys.env) match
      case Left(error) =>
        IO.consoleForIO.errorln(
          s"arcadence-worker: invalid configuration: $error"
        ) *> IO.pure(ExitCode.Error)

      case Right(config) =>
        given LoggerFactory[IO] = Slf4jFactory.create[IO]
        val logger = LoggerFactory[IO].getLogger

        val applyLogLevel: IO[Unit] = IO {
          val root = org.slf4j.LoggerFactory
            .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            .asInstanceOf[ch.qos.logback.classic.Logger]
          root.setLevel(ch.qos.logback.classic.Level.valueOf(config.logLevel))
        }

        val sendWorker =
          com.arcadence.core.db.DbUrl
            .parse(sys.env.getOrElse("DATABASE_URL", "")) match
            case Left(error) =>
              logger.error(s"worker needs DATABASE_URL: $error") *> IO(
                ExitCode.Error
              )
            case Right(jdbc) =>
              given doobie.Transactor[IO] =
                doobie.util.transactor.Transactor.fromDriverManager[IO](
                  "org.postgresql.Driver",
                  jdbc.jdbcUrl,
                  jdbc.user,
                  jdbc.password,
                  logHandler = None
                )
              val cfg = SendWorker.Config.fromEnv(sys.env)
              given org.typelevel.log4cats.Logger[IO] = logger
              SendWorker
                .stream(
                  new com.arcadence.core.jobs.JobQueue.Repo(
                    summon[doobie.Transactor[IO]]
                  ),
                  List(SendWorker.GenericHandler),
                  cfg
                )
                .compile
                .drain
                .as(ExitCode.Success)

        applyLogLevel *>
          logger.info(
            s"arcadence-worker ${BuildInfo.version} starting: heartbeat interval ${config.tickInterval.toMillis}ms"
          ) *>
          fs2
            .Stream(
              fs2.Stream
                .awakeEvery[IO](config.tickInterval)
                .zipWithIndex
                .evalMap { case (_, index) =>
                  logger.info(
                    Heartbeat(index + 1, config.tickInterval).describe
                  )
                },
              fs2.Stream.eval(sendWorker)
            )
            .parJoin(2)
            .compile
            .drain
            .as(ExitCode.Success)
