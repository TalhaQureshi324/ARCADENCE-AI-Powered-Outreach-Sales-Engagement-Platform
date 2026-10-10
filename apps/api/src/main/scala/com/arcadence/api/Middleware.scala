package com.arcadence.api

import cats.data.OptionT
import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import org.http4s.{Header, HttpRoutes, MediaType, Response, Status}
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger

import java.util.UUID

/** Uniform machine-readable error responses (docs/API.md §2). */
object ApiResponses:

  val RequestIdHeader = CIString("X-Request-Id")

  def requestIdOf(req: org.http4s.Request[IO]): String =
    req.headers.get(RequestIdHeader).map(_.head.value).getOrElse("")

  private def json(
      body: Json,
      status: Status,
      requestId: String
  ): Response[IO] =
    Response[IO](status)
      .withEntity(body.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))
      .putHeaders(Header.Raw(RequestIdHeader, requestId))

  def error(
      code: String,
      message: String,
      status: Status,
      requestId: String
  ): Response[IO] =
    json(
      Json.obj(
        "error" -> Json.obj(
          "code" -> Json.fromString(code),
          "message" -> Json.fromString(message),
          "requestId" -> Json.fromString(requestId)
        )
      ),
      status,
      requestId
    )

  def notFound(requestId: String): Response[IO] =
    error(
      "not_found",
      "The requested resource does not exist.",
      Status.NotFound,
      requestId
    )

  def internal(requestId: String): Response[IO] =
    error(
      "internal_error",
      "An unexpected error occurred. Quote the requestId when contacting support.",
      Status.InternalServerError,
      requestId
    )
end ApiResponses

/** Assigns (or forwards) an X-Request-Id, stamps it onto the request for inner
  * layers, and writes one structured access-log line per request.
  */
final class RequestContext(logger: Logger[IO]):
  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes[IO]: req =>
      val incoming =
        req.headers.get(ApiResponses.RequestIdHeader).map(_.head.value)
      val requestId = incoming.getOrElse(UUID.randomUUID().toString)
      // Stamp so inner middleware (error envelope) sees the same id even when it was generated here.
      val stamped = incoming.fold(
        req.putHeaders(Header.Raw(ApiResponses.RequestIdHeader, requestId))
      )(_ => req)
      val startedAt = IO.monotonic

      routes(stamped).semiflatMap { response =>
        (startedAt, IO.realTime)
          .mapN((start, end) => (end - start).toMillis -> response)
          .flatMap { case (durationMs, response) =>
            logger
              .info(
                s"requestId=$requestId method=${req.method.name} path=${req.uri.path.renderString} status=${response.status.code} durationMs=$durationMs"
              )
              .as(
                response.putHeaders(
                  Header.Raw(ApiResponses.RequestIdHeader, requestId)
                )
              )
          }
      }

/** Catches unexpected failures at the edge and converts them to the error
  * envelope; never leaks internals.
  */
final class ErrorEnvelope(logger: Logger[IO]):
  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    HttpRoutes[IO]: req =>
      val requestId = ApiResponses.requestIdOf(req)
      OptionT(
        routes(req).value.attempt.flatMap {
          case Left(error) =>
            logger
              .error(error)(
                s"requestId=$requestId unhandled error on ${req.method.name} ${req.uri.path.renderString}"
              )
              .as(Some(ApiResponses.internal(requestId)))
          case Right(response) => IO.pure(response)
        }
      )
