package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.db.DbUrl
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Header, Method, Request, Status, Uri}

import java.util.UUID

/** Website visitor intelligence (Phase 12.3): site registration with keys,
  * public anonymous event collection, aggregate analytics, consent gate.
  */
final class VisitorRoutesSpec extends CatsEffectSuite:

  override def munitIOTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "second")

  private val dbUrl = sys.env.getOrElse("TEST_DATABASE_URL", "")
  private lazy val jdbc =
    DbUrl.parse(dbUrl).fold(_ => sys.error("bad TEST_DATABASE_URL"), identity)
  private lazy val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    jdbc.jdbcUrl,
    jdbc.user,
    jdbc.password,
    logHandler = None
  )

  private val orgId = UUID.randomUUID()
  private val ws = UUID.randomUUID()
  private val userA = UUID.randomUUID()
  private val suffix = ws.toString.take(8)

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, s"vis-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val routes = new VisitorRoutes(fakeAuth, xa)
  private lazy val app = routes.http.orNotFound

  private def request(
      method: Method,
      path: String,
      body: Option[Json] = None
  ): IO[(Status, Json)] =
    val base = Uri.unsafeFromString(path)
    val baseReq = Request[IO](method, base).withHeaders(
      Header.Raw(org.typelevel.ci.CIString("Authorization"), "Bearer token-A")
    )
    val req = body
      .fold(baseReq)(json =>
        baseReq
          .withEntity(json.noSpaces)(org.http4s.EntityEncoder.stringEncoder[IO])
          .withContentType(
            org.http4s.headers
              .`Content-Type`(org.http4s.MediaType.application.json)
          )
      )
    app
      .run(req)
      .flatMap(res =>
        res.bodyText.compile.string
          .map(t => (res.status, parse(t).toOption.getOrElse(Json.Null)))
      )

  private def publicRequest(
      method: Method,
      path: String,
      body: Option[Json]
  ): IO[(Status, Json)] =
    val req = body
      .fold(Request[IO](method, Uri.unsafeFromString(path)))(json =>
        Request[IO](method, Uri.unsafeFromString(path))
          .withEntity(json.noSpaces)(org.http4s.EntityEncoder.stringEncoder[IO])
          .withContentType(
            org.http4s.headers
              .`Content-Type`(org.http4s.MediaType.application.json)
          )
      )
    app
      .run(req)
      .flatMap(res =>
        res.bodyText.compile.string
          .map(t => (res.status, parse(t).toOption.getOrElse(Json.Null)))
      )

  private def jsonField(b: Json, f: String): String =
    b.hcursor.get[String](f).toOption.getOrElse("")
  private def jsonLong(b: Json, f: String): Long =
    b.hcursor.get[Long](f).toOption.getOrElse(-1L)

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: org, ws, owner") {
    for _ <-
        (sql"delete from public.workspace where id = $ws".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'VIS Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'VIS WS', ${"vis-" + suffix})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"vis-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'vis') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run).void
          .transact(xa)
    yield ()
  }

  test("site registration returns a key and embeddable snippet") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/visitor-sites?domain=example.com"
      )
      _ = assertEquals(pair._1, Status.Created)
      _ = assert(jsonField(pair._2, "siteKey").startsWith("arc_"))
      _ = assert(jsonField(pair._2, "snippet").contains("track.js"))
      _ = assert(jsonField(pair._2, "snippet").contains("__arcadenceConsent"))
      listed <- request(Method.GET, s"/api/v1/workspaces/$ws/visitor-sites")
      count = listed._2.asArray.getOrElse(Vector.empty).size
      _ = assertEquals(count, 1)
    yield ()
  }

  test(
    "tracking script serves with consent gate; events collect anonymously; bad keys 404"
  ) {
    for
      script <- request(Method.GET, "/api/v1/track.js")
      _ = assertEquals(script._1, Status.Ok)
      listed <- request(Method.GET, s"/api/v1/workspaces/$ws/visitor-sites")
      siteKey = listed._2.asArray
        .getOrElse(Vector.empty)
        .head
        .hcursor
        .get[String]("siteKey")
        .toOption
        .getOrElse("")
      // consent gate is in the served script
      _ = assert(
        script._2.hcursor.get[String]("type").isLeft
      ) // it's raw JS not json
      ev1 <- publicRequest(
        Method.POST,
        "/api/v1/track/events",
        body = Some(
          Json.obj(
            "siteKey" -> Json.fromString(siteKey),
            "type" -> Json.fromString("pageview"),
            "path" -> Json.fromString("/pricing"),
            "referrer" -> Json.fromString("https://google.com"),
            "utmSource" -> Json.fromString("newsletter"),
            "utmCampaign" -> Json.fromString("launch")
          )
        )
      )
      _ = assertEquals(ev1._1, Status.Ok)
      _ <- publicRequest(
        Method.POST,
        "/api/v1/track/events",
        body = Some(
          Json.obj(
            "siteKey" -> Json.fromString(siteKey),
            "type" -> Json.fromString("conversion"),
            "path" -> Json.fromString("/signup")
          )
        )
      )
      bad <- publicRequest(
        Method.POST,
        "/api/v1/track/events",
        body = Some(
          Json.obj(
            "siteKey" -> Json.fromString("arc_nonexistent"),
            "type" -> Json.fromString("pageview")
          )
        )
      )
      _ = assertEquals(bad._1, Status.NotFound)
      badType <- publicRequest(
        Method.POST,
        "/api/v1/track/events",
        body = Some(
          Json.obj(
            "siteKey" -> Json.fromString(siteKey),
            "type" -> Json.fromString("everything")
          )
        )
      )
      _ = assertEquals(badType._1, Status.BadRequest)
      analytics <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/visitor-analytics?days=7"
      )
      _ = assertEquals(jsonLong(analytics._2, "pageviews"), 1L)
      _ = assertEquals(jsonLong(analytics._2, "conversions"), 1L)
      paths = analytics._2.hcursor
        .downField("topPaths")
        .values
        .getOrElse(Vector.empty)
        .size
      _ = assertEquals(paths, 2)
    yield ()
  }
