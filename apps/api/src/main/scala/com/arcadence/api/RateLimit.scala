package com.arcadence.api

import cats.data.OptionT
import cats.effect.IO
import cats.syntax.all.*
import org.http4s.{HttpRoutes, Request, Response, Status}

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** Sliding-window per-key rate limiter (in-memory; per API instance). Keys are
  * caller identities (the raw Authorization header); requests without one fall
  * into a shared "anonymous" bucket.
  *
  * Phase 1 scope: protects against crude hammering of a single instance.
  * Distributed limiting arrives with the deployment phase (noted in
  * docs/API.md).
  */
final class RateLimiter(maxRequests: Int, windowMillis: Long):

  private val windows =
    new ConcurrentHashMap[String, java.util.ArrayDeque[Long]]()

  /** Records the attempt and reports whether the caller is within budget. */
  def allow(key: String, nowMillis: Long): Boolean =
    if maxRequests <= 0 then true
    else
      val deque =
        windows.computeIfAbsent(key, _ => new java.util.ArrayDeque[Long]())
      val allowed = deque.synchronized {
        while
          val oldest = Option(deque.peekFirst())
          oldest.exists(nowMillis - _ >= windowMillis)
        do deque.pollFirst()
        if deque.size < maxRequests then
          deque.addLast(nowMillis)
          true
        else false
      }
      if windows.size > 10_000 then
        // Opportunistic cleanup so idle keys don't accumulate forever.
        windows.asScala.foreachEntry((k, d) =>
          if d.isEmpty then windows.remove(k)
        )
      allowed

object RateLimit:

  private val AnonymousKey = "anonymous"

  private def keyOf(req: Request[IO]): String =
    req.headers
      .get(org.typelevel.ci.CIString("Authorization"))
      .map(_.head.value)
      .getOrElse(AnonymousKey)

  def apply(maxRequests: Int, windowMillis: Long = 60_000L)(
      routes: HttpRoutes[IO]
  ): HttpRoutes[IO] =
    val limiter = new RateLimiter(maxRequests, windowMillis)
    val tooMany: IO[Response[IO]] = IO(
      Response[IO](Status.TooManyRequests)
        .withEntity(
          """{"error":{"code":"rate_limited","message":"Too many requests — slow down and retry shortly."}}"""
        )
        .withContentType(
          org.http4s.headers
            .`Content-Type`(org.http4s.MediaType.application.json)
        )
    )

    HttpRoutes[IO]: (req: Request[IO]) =>
      OptionT(
        if limiter.allow(keyOf(req), System.currentTimeMillis()) then
          routes(req).value
        else tooMany.map(Some(_))
      )
