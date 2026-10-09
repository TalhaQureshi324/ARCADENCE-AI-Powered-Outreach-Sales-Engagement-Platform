package com.arcadence.core.config

import cats.syntax.all.*

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

final case class WorkerConfig(tickInterval: FiniteDuration, logLevel: String)

object WorkerConfig:
  val Default: WorkerConfig =
    WorkerConfig(tickInterval = 30.seconds, logLevel = "INFO")

  /** Reads the worker process configuration from the process environment.
    * Returns a single aggregated error on failure.
    */
  def fromEnv(env: Map[String, String]): Either[String, WorkerConfig] =
    (
      Parsers.positiveMillis(
        env,
        "WORKER_TICK_INTERVAL_MS",
        Default.tickInterval.toMillis
      ),
      Parsers.logLevel(env, "LOG_LEVEL", Default.logLevel)
    )
      .mapN(WorkerConfig.apply)
      .toEither
      .left
      .map(errors => errors.toList.mkString("; "))
