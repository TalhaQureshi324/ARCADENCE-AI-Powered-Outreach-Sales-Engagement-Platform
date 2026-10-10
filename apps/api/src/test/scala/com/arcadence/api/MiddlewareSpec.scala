package com.arcadence.api

import cats.data.OptionT
import cats.effect.IO
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{HttpRoutes, Method, Request, Response}
import org.typelevel.log4cats.noop.NoOpFactory

final class MiddlewareSpec extends CatsEffectSuite:

  private def stack(routes: HttpRoutes[IO]) =
    new RequestContext(NoOpFactory[IO].getLogger)(
      new ErrorEnvelope(NoOpFactory[IO].getLogger)(routes)
    )

  private def body(res: Response[IO]): IO[String] = res.bodyText.compile.string

  test("unhandled route failures become the internal error envelope with 500") {
    val failing: HttpRoutes[IO] =
      HttpRoutes[IO](req =>
        OptionT.liftF(IO.raiseError(new RuntimeException("boom")))
      )
    stack(failing)(Request[IO](Method.GET, uri"/anything")).value.flatMap {
      case Some(res) =>
        body(res).map { b =>
          assertEquals(res.status.code, 500)
          val cursor = parse(b).toOption.get.hcursor.downField("error")
          assertEquals(
            cursor.get[String]("code").toOption.get,
            "internal_error"
          )
          assert(cursor.get[String]("requestId").toOption.get.nonEmpty)
        }
      case None => IO(fail("expected a response"))
    }
  }

  test(
    "X-Request-Id is forwarded through the stack and echoed on the response"
  ) {
    val ok: HttpRoutes[IO] =
      HttpRoutes[IO](req => OptionT.liftF(IO.pure(Response[IO]())))
    val req = Request[IO](Method.GET, uri"/ok")
      .putHeaders(
        org.http4s.Header.Raw(ApiResponses.RequestIdHeader, "test-id-123")
      )
    stack(ok)(req).value.flatMap {
      case Some(res) =>
        IO {
          assertEquals(
            res.headers.get(ApiResponses.RequestIdHeader).map(_.head.value),
            Some("test-id-123")
          )
        }
      case None => IO(fail("expected a response"))
    }
  }

  test("a missing X-Request-Id is generated and present on the response") {
    val ok: HttpRoutes[IO] =
      HttpRoutes[IO](req => OptionT.liftF(IO.pure(Response[IO]())))
    stack(ok)(Request[IO](Method.GET, uri"/ok")).value.flatMap {
      case Some(res) =>
        IO {
          val id =
            res.headers.get(ApiResponses.RequestIdHeader).map(_.head.value)
          assert(
            id.exists(_.nonEmpty),
            "generated request id must be non-empty"
          )
        }
      case None => IO(fail("expected a response"))
    }
  }

  test("notFound envelope carries the request id and correct code") {
    val res = ApiResponses.notFound("req-42")
    assertEquals(res.status.code, 404)
    assertEquals(
      res.headers.get(ApiResponses.RequestIdHeader).map(_.head.value),
      Some("req-42")
    )
    body(res).map { b =>
      val json = parse(b).toOption.get
      assertEquals(
        json.hcursor.downField("error").get[String]("code").toOption.get,
        "not_found"
      )
    }
  }
