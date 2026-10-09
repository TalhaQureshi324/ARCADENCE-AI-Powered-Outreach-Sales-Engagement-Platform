package com.arcadence.api

import cats.effect.IO
import com.arcadence.core.build.BuildInfo
import com.arcadence.core.health.ServiceIdentity
import fs2.text
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Method, Request, Response, Status, Uri}

final class HealthRoutesSpec extends CatsEffectSuite:

  private val identity = ServiceIdentity("arcadence-api", BuildInfo.version)
  private val service = HealthRoutes(identity).http.orNotFound

  private def get(path: String): IO[Response[IO]] =
    service.run(Request[IO](Method.GET, Uri.unsafeFromString(path)))

  private def bodyOf(response: Response[IO]): IO[String] =
    response.body.through(text.utf8.decode).compile.string

  test("GET /health returns 200 with an up payload") {
    get("/health").flatMap(bodyOf).map { body =>
      val json = parse(body).toOption.get
      assertEquals(
        json.hcursor.get[String]("service").toOption.get,
        "arcadence-api"
      )
      assertEquals(json.hcursor.get[String]("status").toOption.get, "up")
      assertEquals(
        json.hcursor.get[String]("version").toOption.get,
        BuildInfo.version
      )
    }
  }

  test("GET /ready returns 200 with process readiness") {
    get("/ready").flatMap(bodyOf).map { body =>
      val cursor = parse(body).toOption.get.hcursor
      assertEquals(cursor.get[String]("status").toOption.get, "ready")
      assertEquals(
        cursor.downField("checks").get[String]("process").toOption.get,
        "ok"
      )
    }
  }

  test("unknown paths return 404") {
    get("/nope").map(res => assertEquals(res.status, Status.NotFound))
  }
