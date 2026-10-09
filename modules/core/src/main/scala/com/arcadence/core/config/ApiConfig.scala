package com.arcadence.core.config

import cats.syntax.all.*

final case class ApiConfig(port: Int, logLevel: String)

object ApiConfig:
  val Default: ApiConfig = ApiConfig(port = 8080, logLevel = "INFO")

  /** Reads the API process configuration from the process environment. Returns
    * a single aggregated error on failure.
    */
  def fromEnv(env: Map[String, String]): Either[String, ApiConfig] =
    (
      Parsers.port(env, "API_PORT", Default.port),
      Parsers.logLevel(env, "LOG_LEVEL", Default.logLevel)
    )
      .mapN(ApiConfig.apply)
      .toEither
      .left
      .map(errors => errors.toList.mkString("; "))
