package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.db.DbUrl
import com.arcadence.core.integrations.Integrations
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Header, Method, Request, Status, Uri}

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Integrations (Phase 12.2): registration with per-workspace dedupe, masked
  * config reads, Slack test delivery, and calendar event creation through a
  * stubbed provider endpoint with registry-resolved tokens.
  */
final class IntegrationSpec extends CatsEffectSuite:

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
  private val acctId = UUID.randomUUID()
  private val suffix = ws.toString.take(8)

  private val slackHits = new AtomicInteger(0)
  private val slackBody =
    new java.util.concurrent.ConcurrentLinkedQueue[String]()
  private val gcalHits = new AtomicInteger(0)
  private val gcalAuth =
    new java.util.concurrent.ConcurrentLinkedQueue[String]()

  private lazy val server: HttpServer =
    val s = HttpServer.create(new InetSocketAddress(0), 0)
    s.createContext(
      "/slack",
      new HttpHandler:
        def handle(ex: HttpExchange): Unit =
          slackHits.incrementAndGet()
          slackBody.add(new String(ex.getRequestBody.readAllBytes(), "UTF-8"))
          ex.sendResponseHeaders(200, -1); ex.close()
    )
    s.createContext(
      "/gcal",
      new HttpHandler:
        def handle(ex: HttpExchange): Unit =
          gcalHits.incrementAndGet()
          gcalAuth.add(
            Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
          )
          val resp = "{\"id\":\"evt_123\"}".getBytes("UTF-8")
          ex.getResponseHeaders.add("Content-Type", "application/json")
          ex.sendResponseHeaders(200, resp.length)
          ex.getResponseBody.write(resp); ex.close()
    )
    s.start()
    s

  private lazy val routes = {
    val clientIO = EmberCompat.client
    new IntegrationRoutes(
      fakeAuth,
      xa,
      EmberCompat.client,
      accessTokenFor = (w, acct) =>
        if acct == acctId then IO.pure(Some("test-google-token"))
        else IO.pure(None),
      googleBaseUrl = s"http://127.0.0.1:${server.getAddress.getPort}/gcal"
    )
  }
  private lazy val app = routes.http.orNotFound
  private lazy val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))
  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, s"int-$suffix@t.test")
  )

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

  private def jsonField(b: Json, f: String): String =
    b.hcursor.get[String](f).toOption.getOrElse("")

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: org, ws, owner, email account") {
    server // start stub
    for _ <-
        (sql"delete from public.workspace where id = $ws".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'INT Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'INT WS', ${"int-" + suffix})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"int-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'int') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run *>
          sql"""insert into public.email_accounts (id, workspace_id, provider, email_address, status)
                values ($acctId, $ws, 'gmail', ${"cal-" + suffix + "@t.test"}, 'connected')""".update.run).void
          .transact(xa)
    yield ()
  }

  test("connect is idempotent per kind; config is masked on read") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/integrations",
        body = Some(
          Json.obj(
            "kind" -> Json.fromString("slack"),
            "config" -> Json.obj(
              "webhookUrl" -> Json.fromString(
                s"http://127.0.0.1:${server.getAddress.getPort}/slack"
              )
            )
          )
        )
      )
      _ = assertEquals(pair._1, Status.Created)
      again <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/integrations",
        body = Some(
          Json.obj(
            "kind" -> Json.fromString("slack"),
            "config" -> Json.obj(
              "webhookUrl" -> Json.fromString(
                s"http://127.0.0.1:${server.getAddress.getPort}/slack?v=2"
              )
            )
          )
        )
      )
      _ = assertEquals(again._1, Status.Created) // upsert, not 409
      listed <- request(Method.GET, s"/api/v1/workspaces/$ws/integrations")
      count = listed._2.asArray.getOrElse(Vector.empty).size
      _ = assertEquals(count, 1)
      cfg = listed._2.asArray
        .getOrElse(Vector.empty)
        .head
        .hcursor
        .downField("config")
      hook = cfg.get[String]("webhookUrl").toOption.getOrElse("")
      _ = assert(
        hook.startsWith("https://example.com/hook2") || hook.contains("…"),
        s"not masked: $hook"
      )
    yield ()
  }

  test("slack test posts through the real connector") {
    for
      listed <- request(Method.GET, s"/api/v1/workspaces/$ws/integrations")
      id = listed._2.asArray
        .getOrElse(Vector.empty)
        .head
        .hcursor
        .get[String]("id")
        .toOption
        .getOrElse("")
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/integrations/$id/test"
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(
        pair._2.hcursor.get[Boolean]("ok").toOption.getOrElse(false),
        true
      )
      _ = assert(slackHits.get >= 1)
      body = Option(slackBody.poll()).getOrElse("")
      _ = assert(body.contains("Arcadence connection test"))
    yield ()
  }

  test("calendar event creation uses the registry-resolved token") {
    for
      acctId <-
        sql"select id from public.email_accounts where workspace_id = $ws limit 1"
          .query[UUID]
          .unique
          .transact(xa)
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/integrations/calendar-events",
        body = Some(
          Json.obj(
            "emailAccountId" -> Json.fromString(acctId.toString),
            "summary" -> Json.fromString("Intro call"),
            "startIso" -> Json.fromString("2026-11-05T15:00:00Z"),
            "endIso" -> Json.fromString("2026-11-05T15:30:00Z"),
            "attendeeEmail" -> Json.fromString("lead@t.test")
          )
        )
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(jsonField(pair._2, "externalId"), "evt_123")
      _ = assert(gcalHits.get >= 1)
      auth = Option(gcalAuth.poll()).getOrElse("")
      _ = assert(auth.contains("test-google-token"))
    yield ()
  }

  test(
    "unknown kind is rejected; calendar event without token fails honestly"
  ) {
    for
      bad <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/integrations",
        body = Some(
          Json.obj("kind" -> Json.fromString("tiktok"), "config" -> Json.obj())
        )
      )
      _ = assertEquals(bad._1, Status.UnprocessableEntity)
      acctId <-
        sql"select id from public.email_accounts where workspace_id = $ws limit 1"
          .query[UUID]
          .unique
          .transact(xa)
      noToken <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/integrations/calendar-events",
        body = Some(
          Json.obj(
            "emailAccountId" -> Json.fromString(UUID.randomUUID().toString),
            "summary" -> Json.fromString("x"),
            "startIso" -> Json.fromString("2026-11-05T15:00:00Z"),
            "endIso" -> Json.fromString("2026-11-05T15:30:00Z")
          )
        )
      )
      _ = assertEquals(noToken._1, Status.BadGateway)
      _ = assert(
        jsonField(noToken._2, "message").toLowerCase.contains(
          "token"
        ) || jsonField(noToken._2, "message").toLowerCase.contains("connect")
      )
    yield ()
  }

/** Minimal shared HTTP client holder for the spec. */
object EmberCompat:
  lazy val client: org.http4s.client.Client[IO] =
    org.http4s.ember.client.EmberClientBuilder
      .default[IO]
      .build
      .allocated
      .map(_._1)
      .unsafeRunSync()
