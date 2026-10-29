package com.arcadence.core.ai

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite

import java.time.Duration

/** Gateway unit tests (Phase 11.1): bounded retries, structured output
  * validation, redaction, and the OpenAI-compatible response parser.
  */
final class AiGatewaySpec extends FunSuite:

  test("stub gateway returns completion with usage and records call count") {
    val gw = AiGateway.Stub("hello world")
    val res = gw
      .complete(
        "m1",
        List(AiGateway.ChatMessage("user", "hi")),
        64,
        jsonMode = false
      )
      .unsafeRunSync()
    val c = res.fold(e => fail(s"expected completion, got $e"), identity)
    assertEquals(c.text, "hello world")
    assertEquals(c.usage.inputTokens, 11L)
    assertEquals(c.usage.outputTokens, 7L)
    assertEquals(gw.calls, 1)
  }

  test("retries transient failures with backoff and succeeds") {
    val base = AiGateway.Stub(
      "ok",
      scriptedFailures =
        List(AiGateway.Unavailable("down"), AiGateway.RateLimited(1))
    )
    val gw = AiGateway.retried(base, maxRetries = 2, baseDelayMs = 10)
    val res = gw.complete("m", Nil, 32, jsonMode = false).unsafeRunSync()
    assert(res.isRight)
    assertEquals(base.calls, 3)
  }

  test("does not retry authorization or bad-request failures") {
    val base = AiGateway.Stub(
      "never",
      scriptedFailures = List(AiGateway.Unauthorized("bad key"))
    )
    val gw = AiGateway.retried(base, maxRetries = 3, baseDelayMs = 10)
    val res = gw.complete("m", Nil, 32, jsonMode = false).unsafeRunSync()
    assert(res.isLeft)
    assertEquals(base.calls, 1)
  }

  test("gives up after max retries and returns the last transient error") {
    val base = AiGateway.Stub(
      "never",
      scriptedFailures = List(
        AiGateway.Timeout("t"),
        AiGateway.Timeout("t"),
        AiGateway.Timeout("t")
      )
    )
    val gw = AiGateway.retried(base, maxRetries = 2, baseDelayMs = 5)
    val res = gw.complete("m", Nil, 32, jsonMode = false).unsafeRunSync()
    assertEquals(res.fold(e => e, _ => fail("expected failure")).detail, "t")
    assertEquals(base.calls, 3)
  }

  test("structured output validates JSON schema; invalid output is an error") {
    final case class Out(ok: Boolean, note: String)
    given io.circe.Decoder[Out] = (c: io.circe.HCursor) =>
      for
        ok <- c.get[Boolean]("ok")
        note <- c.get[String]("note")
      yield Out(ok, note)

    val good = AiGateway.Completion(
      """{"ok":true,"note":"fine"}""",
      AiGateway.Usage(1, 1),
      "m",
      "stop"
    )
    assertEquals(
      AiGateway.structured[Out](good).map(_._1).toOption,
      Some(Out(true, "fine"))
    )

    val notJson =
      AiGateway.Completion("just prose", AiGateway.Usage(1, 1), "m", "stop")
    assert(
      AiGateway
        .structured[Out](notJson)
        .swap
        .toOption
        .exists(_.isInstanceOf[AiGateway.BadRequest])
    )

    val wrongSchema = AiGateway.Completion(
      """{"wrong":1}""",
      AiGateway.Usage(1, 1),
      "m",
      "stop"
    )
    assert(AiGateway.structured[Out](wrongSchema).isLeft)
  }

  test(
    "redaction strips emails, api keys and bearer tokens; preview truncates"
  ) {
    val raw =
      "reach ada@example.com now sk-abcdefghijklmnop1234 Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9xxxx"
    val red = AiGateway.Redact(raw)
    assert(!red.contains("ada@example.com"))
    assert(!red.contains("sk-abcdefghijklmnop1234"))
    assert(!red.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9xxxx"))
    assert(red.contains("[email]"))
    assert(red.contains("[secret]"))

    val long = "lorem ipsum dolor sit amet " * 60
    val prev = AiGateway.Redact.preview(long, max = 100)
    assert(prev.length < 130)
    assert(prev.endsWith("[truncated]"))
  }

  test("estimateCostMicros is conservative and monotonic") {
    val cheap = AiGateway.estimateCostMicros(AiGateway.Usage(1_000_000, 0))
    val pricier =
      AiGateway.estimateCostMicros(AiGateway.Usage(1_000_000, 1_000_000))
    assertEquals(cheap, 150L)
    assert(pricier > cheap)
  }

  test("OpenAiCompatible parses chat-completions responses") {
    // Parser behavior exercised through the stub-free path: a canned HTTP body.
    val body =
      """{"choices":[{"message":{"content":"hi there"},"finish_reason":"stop"}],
        |"usage":{"prompt_tokens":5,"completion_tokens":2}}""".stripMargin
    val gw = new AiGateway.OpenAiCompatible(
      null,
      org.http4s.Uri.unsafeFromString("http://localhost:1"),
      "sk-test",
      Duration.ofSeconds(1)
    )
    val parsed =
      gw.getClass.getDeclaredMethod(
        "parseGatewayResponse",
        classOf[String],
        classOf[String]
      )
    parsed.setAccessible(true)
    val out = parsed
      .invoke(gw, "m", body)
      .asInstanceOf[Either[AiGateway.GatewayError, AiGateway.Completion]]
    val c = out.fold(e => fail(s"parse failed: $e"), identity)
    assertEquals(c.text, "hi there")
    assertEquals(c.usage, AiGateway.Usage(5, 2))
    assertEquals(c.finishReason, "stop")
  }
