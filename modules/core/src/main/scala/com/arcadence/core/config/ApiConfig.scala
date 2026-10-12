package com.arcadence.core.config

import cats.syntax.all.*

final case class ApiConfig(
    port: Int,
    logLevel: String,
    databaseUrl: String,
    jwtIssuer: String,
    jwtAudience: String,
    jwksUrl: String,
    rateLimitPerMinute: Int
)

object ApiConfig:
  val Default: ApiConfig = ApiConfig(
    port = 8080,
    logLevel = "INFO",
    databaseUrl = "",
    jwtIssuer = "",
    jwtAudience = "",
    jwksUrl = "",
    rateLimitPerMinute = 120
  )

  /** Reads the API process configuration from the process environment. The
    * database and JWT-verification settings are required from Phase 1.2 on:
    * business endpoints refuse to start without them (fail fast, aggregated
    * error).
    */
  def fromEnv(env: Map[String, String]): Either[String, ApiConfig] =
    (
      Parsers.port(env, "API_PORT", Default.port),
      Parsers.logLevel(env, "LOG_LEVEL", Default.logLevel),
      required(env, "DATABASE_URL"),
      required(env, "SUPABASE_JWT_ISSUER"),
      required(env, "SUPABASE_JWT_AUDIENCE"),
      required(env, "SUPABASE_JWKS_URL"),
      Parsers.port(env, "RATE_LIMIT_PER_MINUTE", 120)
    )
      .mapN(ApiConfig.apply)
      .toEither
      .left
      .map(errors => errors.toList.mkString("; "))

  private def required(
      env: Map[String, String],
      name: String
  ): ValidatedNelString =
    env.get(name).map(_.trim).filter(_.nonEmpty) match
      case Some(value) => cats.data.Validated.validNel(value)
      case None        => cats.data.Validated.invalidNel(s"$name is required")

  private type ValidatedNelString = cats.data.ValidatedNel[String, String]
