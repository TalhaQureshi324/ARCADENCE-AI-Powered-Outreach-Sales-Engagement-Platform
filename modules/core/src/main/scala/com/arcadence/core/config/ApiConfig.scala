package com.arcadence.core.config

import cats.syntax.all.*

final case class ApiConfig(
    port: Int,
    logLevel: String,
    databaseUrl: String,
    jwtIssuer: String,
    jwtAudience: String,
    jwksUrl: String,
    rateLimitPerMinute: Int,
    credentialEncryptionKey: String
)

object ApiConfig:
  val Default: ApiConfig = ApiConfig(
    port = 8080,
    logLevel = "INFO",
    databaseUrl = "",
    jwtIssuer = "",
    jwtAudience = "",
    jwksUrl = "",
    rateLimitPerMinute = 120,
    credentialEncryptionKey = ""
  )

  /** Reads the API process configuration from the process environment. The
    * database, JWT-verification and credential-encryption settings are required
    * (fail fast, aggregated error): business endpoints refuse to start without
    * them.
    */
  def fromEnv(env: Map[String, String]): Either[String, ApiConfig] =
    (
      Parsers.port(env, "API_PORT", Default.port),
      Parsers.logLevel(env, "LOG_LEVEL", Default.logLevel),
      required(env, "DATABASE_URL"),
      required(env, "SUPABASE_JWT_ISSUER"),
      required(env, "SUPABASE_JWT_AUDIENCE"),
      required(env, "SUPABASE_JWKS_URL"),
      Parsers.port(env, "RATE_LIMIT_PER_MINUTE", 120),
      encryptionKey(env)
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

  /** EMAIL_ENCRYPTION_KEY must be base64 and decode to exactly 32 bytes
    * (AES-256); validated at startup so a broken key never reaches runtime.
    */
  private def encryptionKey(env: Map[String, String]): ValidatedNelString =
    required(env, "EMAIL_ENCRYPTION_KEY").andThen { raw =>
      com.arcadence.core.crypto.CredentialEncryptor.decodeKey(raw) match
        case Right(_)    => cats.data.Validated.validNel(raw)
        case Left(error) => cats.data.Validated.invalidNel(error)
    }

  private type ValidatedNelString = cats.data.ValidatedNel[String, String]
