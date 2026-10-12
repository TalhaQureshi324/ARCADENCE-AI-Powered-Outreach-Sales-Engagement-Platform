package com.arcadence.api

import cats.effect.IO
import org.http4s.{Header, HttpRoutes, Response}
import org.typelevel.ci.CIString

/** Baseline security headers on every API response (docs/SECURITY.md):
  * responses are JSON, never framed, never cached by browsers or CDNs.
  */
object SecurityHeaders:

  private val headers: List[Header.Raw] = List(
    Header.Raw(CIString("X-Content-Type-Options"), "nosniff"),
    Header.Raw(CIString("X-Frame-Options"), "DENY"),
    Header.Raw(CIString("Referrer-Policy"), "same-origin"),
    Header.Raw(CIString("Cache-Control"), "no-store"),
    Header.Raw(
      CIString("Permissions-Policy"),
      "camera=(), microphone=(), geolocation=()"
    )
  )

  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes[IO]: req =>
      routes(req).map(res => headers.foldLeft(res)((r, h) => r.putHeaders(h)))
