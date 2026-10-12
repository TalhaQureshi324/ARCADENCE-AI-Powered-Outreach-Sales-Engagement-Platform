package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.Response
import org.http4s.{Header, HttpRoutes, Method, Request}

import scala.concurrent.duration.*

final class HardeningSpec extends CatsEffectSuite:

  private val okRoutes =
    HttpRoutes[IO](req => cats.data.OptionT.liftF(IO.pure(Response())))

  // -- Rate limiting --------------------------------------------------------

  test("rate limiter allows requests within the window") {
    val limiter = new RateLimiter(maxRequests = 3, windowMillis = 60_000)
    val results = (1L to 3L).map(limiter.allow("caller-1", _))
    assertEquals(results.toList, List(true, true, true))
  }

  test("rate limiter blocks requests beyond the limit in the window") {
    val limiter = new RateLimiter(maxRequests = 2, windowMillis = 60_000)
    assertEquals(limiter.allow("caller-2", 0L), true)
    assertEquals(limiter.allow("caller-2", 1L), true)
    assertEquals(limiter.allow("caller-2", 2L), false)
  }

  test("rate limiter admits again once the window slides") {
    val limiter = new RateLimiter(maxRequests = 2, windowMillis = 1_000)
    assertEquals(limiter.allow("caller-3", 0L), true)
    assertEquals(limiter.allow("caller-3", 100L), true)
    assertEquals(limiter.allow("caller-3", 200L), false)
    assertEquals(limiter.allow("caller-3", 1_500L), true)
  }

  test("rate limiter tracks callers independently") {
    val limiter = new RateLimiter(maxRequests = 1, windowMillis = 60_000)
    assertEquals(limiter.allow("caller-A", 0L), true)
    assertEquals(limiter.allow("caller-B", 1L), true)
    assertEquals(limiter.allow("caller-A", 2L), false)
  }

  test("rate-limited middleware answers 429 with the error envelope") {
    val limited = RateLimit(maxRequests = 1, windowMillis = 60_000)(okRoutes)
    val first = limited
      .run(Request[IO](Method.GET, uri"/api/v1/workspaces"))
      .value
      .unsafeRunSync()
    val second = limited
      .run(Request[IO](Method.GET, uri"/api/v1/workspaces"))
      .value
      .unsafeRunSync()

    assertEquals(first.map(_.status), Some(org.http4s.Status.Ok))
    val res = second.getOrElse(fail("expected a response"))
    assertEquals(res.status, org.http4s.Status.TooManyRequests)
    val body =
      res.body.through(fs2.text.utf8.decode).compile.string.unsafeRunSync()
    assert(body.contains("rate_limited"))
  }

  // -- Security headers -----------------------------------------------------

  test("responses carry the security headers") {
    val hardened = SecurityHeaders(okRoutes)
    val res = hardened
      .run(Request[IO](Method.GET, uri"/api/v1/anything"))
      .value
      .unsafeRunSync()
      .getOrElse(fail("expected a response"))

    val get = (name: String) =>
      res.headers.get(org.typelevel.ci.CIString(name)).map(_.head.value)

    assertEquals(get("X-Content-Type-Options"), Some("nosniff"))
    assertEquals(get("X-Frame-Options"), Some("DENY"))
    assertEquals(get("Referrer-Policy"), Some("same-origin"))
    assertEquals(get("Cache-Control"), Some("no-store"))
    assert(get("Permissions-Policy").exists(_.contains("camera=()")))
  }
