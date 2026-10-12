package com.arcadence.core.config

import munit.FunSuite

final class ConfigSpec extends FunSuite:

  private val validEnv = Map(
    "API_PORT" -> "9000",
    "LOG_LEVEL" -> "debug",
    "DATABASE_URL" -> "postgresql://postgres:secret@db.example.com:5432/app",
    "SUPABASE_JWT_ISSUER" -> "https://ref.supabase.co/auth/v1",
    "SUPABASE_JWT_AUDIENCE" -> "ref",
    "SUPABASE_JWKS_URL" -> "https://ref.supabase.co/auth/v1/.well-known/jwks.json"
  )

  test("ApiConfig.fromEnv requires database and JWT settings") {
    val result = ApiConfig.fromEnv(Map.empty)
    assert(result.isLeft)
    val error = result.left.getOrElse("")
    List(
      "DATABASE_URL",
      "SUPABASE_JWT_ISSUER",
      "SUPABASE_JWT_AUDIENCE",
      "SUPABASE_JWKS_URL"
    )
      .foreach(name =>
        assert(error.contains(name), s"error must mention $name")
      )
  }

  test(
    "ApiConfig.fromEnv accepts a complete environment with defaults for port and log level"
  ) {
    val minimal = validEnv - "API_PORT" - "LOG_LEVEL"
    val result = ApiConfig.fromEnv(minimal)
    assertEquals(
      result,
      Right(
        ApiConfig.Default.copy(
          databaseUrl = validEnv("DATABASE_URL"),
          jwtIssuer = validEnv("SUPABASE_JWT_ISSUER"),
          jwtAudience = validEnv("SUPABASE_JWT_AUDIENCE"),
          jwksUrl = validEnv("SUPABASE_JWKS_URL")
        )
      )
    )
  }

  test("ApiConfig.fromEnv accepts valid overrides") {
    val result = ApiConfig.fromEnv(validEnv)
    assertEquals(
      result,
      Right(
        ApiConfig(
          port = 9000,
          logLevel = "DEBUG",
          databaseUrl = validEnv("DATABASE_URL"),
          jwtIssuer = validEnv("SUPABASE_JWT_ISSUER"),
          jwtAudience = validEnv("SUPABASE_JWT_AUDIENCE"),
          jwksUrl = validEnv("SUPABASE_JWKS_URL")
        )
      )
    )
  }

  test("ApiConfig.fromEnv treats blank values as unset") {
    val result = ApiConfig.fromEnv(validEnv + ("API_PORT" -> "   "))
    assert(result.right.exists(_.port == ApiConfig.Default.port))
  }

  test("ApiConfig.fromEnv rejects out-of-range ports") {
    val result = ApiConfig.fromEnv(validEnv + ("API_PORT" -> "70000"))
    assert(result.left.exists(_.contains("API_PORT")))
  }

  test("ApiConfig.fromEnv rejects unknown log levels") {
    val result = ApiConfig.fromEnv(validEnv + ("LOG_LEVEL" -> "LOUD"))
    assert(result.left.exists(_.contains("LOG_LEVEL")))
  }

  test("ApiConfig.fromEnv aggregates multiple failures into one error") {
    val result =
      ApiConfig.fromEnv(validEnv + ("API_PORT" -> "abc", "LOG_LEVEL" -> "LOUD"))
    assert(
      result.left.exists(msg =>
        msg.contains("API_PORT") && msg.contains("LOG_LEVEL")
      )
    )
  }

  test("WorkerConfig.fromEnv returns defaults for an empty environment") {
    assertEquals(WorkerConfig.fromEnv(Map.empty), Right(WorkerConfig.Default))
  }

  test("WorkerConfig.fromEnv accepts a valid interval") {
    val result = WorkerConfig.fromEnv(Map("WORKER_TICK_INTERVAL_MS" -> "5000"))
    assert(result.isRight)
    assertEquals(result.map(_.tickInterval.toMillis), Right(5000L))
  }

  test("WorkerConfig.fromEnv rejects non-positive intervals") {
    val result = WorkerConfig.fromEnv(Map("WORKER_TICK_INTERVAL_MS" -> "0"))
    assert(result.left.exists(_.contains("WORKER_TICK_INTERVAL_MS")))
  }
