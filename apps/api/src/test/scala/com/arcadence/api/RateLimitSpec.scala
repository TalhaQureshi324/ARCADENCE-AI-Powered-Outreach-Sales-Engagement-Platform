package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.http4s.{Header, HttpRoutes, Method, Request, Response, Status, Uri}
import org.typelevel.ci.CIString

import scala.concurrent.duration.*

/** Phase 15.1 audit: rate limiter behavior — per-key isolation, window expiry,
  * and bounded cleanup when callers rotate unique Authorization values (the old
  * map could grow without bound).
  */
final class RateLimitSpec extends CatsEffectSuite:

  private def okRoute: HttpRoutes[IO] =
    HttpRoutes.of[IO] { _ => IO.pure(Response[IO](Status.Ok)) }

  private def reqWith(key: String): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString("/health")).withHeaders(
      Header.Raw(CIString("Authorization"), key)
    )

  private def run(routes: HttpRoutes[IO], key: String): IO[Status] =
    routes(reqWith(key))
      .getOrElseF(IO.pure(Response[IO](Status.NotFound)))
      .map(
        _.status
      )

  test("same key is limited after maxRequests; window expiry restores it") {
    val limited = RateLimit(2, windowMillis = 300.millis.toMillis)(okRoute)
    for
      r1 <- run(limited, "Bearer key-1")
      r2 <- run(limited, "Bearer key-1")
      r3 <- run(limited, "Bearer key-1")
      _ = assertEquals(r1, Status.Ok)
      _ = assertEquals(r2, Status.Ok)
      _ = assertEquals(r3, Status.TooManyRequests)
      _ <- IO.sleep(400.millis)
      r4 <- run(limited, "Bearer key-1")
      _ = assertEquals(r4, Status.Ok)
    yield ()
  }

  test("distinct keys have independent buckets") {
    val limited = RateLimit(1, windowMillis = 60_000L)(okRoute)
    for
      statuses <- (1 to 5).toList.traverse(i => run(limited, s"Bearer k-$i"))
      _ = assert(statuses.forall(_ == Status.Ok))
      sixth <- run(limited, "Bearer k-1")
      _ = assertEquals(sixth, Status.TooManyRequests)
    yield ()
  }

  test("rotating unique keys does not false-limit (bounded cleanup)") {
    val limited = RateLimit(120, windowMillis = 60_000L)(okRoute)
    for
      statuses <-
        (1 to 10_050).toList.traverse(i => run(limited, s"Bearer flood-$i"))
      _ = assert(statuses.forall(_ == Status.Ok))
      // A previously-seen key still works after the cleanup pass ran.
      again <- run(limited, "Bearer flood-1")
      _ = assertEquals(again, Status.Ok)
    yield ()
  }
