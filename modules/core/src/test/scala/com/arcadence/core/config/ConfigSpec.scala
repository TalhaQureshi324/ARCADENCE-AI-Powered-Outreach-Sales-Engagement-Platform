package com.arcadence.core.config

import munit.FunSuite

final class ConfigSpec extends FunSuite:

  test("ApiConfig.fromEnv returns defaults for an empty environment") {
    assertEquals(ApiConfig.fromEnv(Map.empty), Right(ApiConfig.Default))
  }

  test("ApiConfig.fromEnv accepts valid overrides") {
    val result =
      ApiConfig.fromEnv(Map("API_PORT" -> "9000", "LOG_LEVEL" -> "debug"))
    assertEquals(result, Right(ApiConfig(port = 9000, logLevel = "DEBUG")))
  }

  test("ApiConfig.fromEnv treats blank values as unset") {
    val result = ApiConfig.fromEnv(Map("API_PORT" -> "   ", "LOG_LEVEL" -> ""))
    assertEquals(result, Right(ApiConfig.Default))
  }

  test("ApiConfig.fromEnv rejects out-of-range ports") {
    val result = ApiConfig.fromEnv(Map("API_PORT" -> "70000"))
    assert(result.isLeft)
    assert(result.left.exists(_.contains("API_PORT")))
  }

  test("ApiConfig.fromEnv rejects unknown log levels") {
    val result = ApiConfig.fromEnv(Map("LOG_LEVEL" -> "LOUD"))
    assert(result.left.exists(_.contains("LOG_LEVEL")))
  }

  test("ApiConfig.fromEnv aggregates multiple failures into one error") {
    val result =
      ApiConfig.fromEnv(Map("API_PORT" -> "abc", "LOG_LEVEL" -> "LOUD"))
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
