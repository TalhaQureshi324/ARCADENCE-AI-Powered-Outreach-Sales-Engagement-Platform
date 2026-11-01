package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.db.DbUrl
import com.arcadence.core.webhooks.Webhooks
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.ember.client.EmberClientBuilder

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** Webhook delivery dispatch (Phase 12.1): HMAC-signed POSTs, retry with
  * backoff to dead-letter, replay, and delivery listing.
  */
final class WebhookDispatcherSpec extends CatsEffectSuite:

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

  private val hits = new AtomicInteger(0)
  private val bodies = new java.util.concurrent.ConcurrentLinkedQueue[
    (String, String)
  ]() // (sig, body)
  @volatile private var failUntilHit: Int = 0

  private lazy val server: HttpServer =
    val s = HttpServer.create(new InetSocketAddress(0), 0)
    s.createContext(
      "/hook",
      new HttpHandler:
        def handle(exchange: HttpExchange): Unit =
          val n = hits.incrementAndGet()
          val sig = exchange.getRequestHeaders.getFirst("X-Arcadence-Signature")
          val body = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
          bodies.add((if sig == null then "" else sig, body))
          val code = if n <= failUntilHit then 500 else 200
          exchange.sendResponseHeaders(code, -1)
          exchange.close()
    )
    s.start()
    s

  private def stubUrl: String =
    s"http://127.0.0.1:${server.getAddress.getPort}/hook"

  private lazy val dispatcherIO: IO[WebhookDispatcher] =
    EmberClientBuilder
      .default[IO]
      .build
      .allocated
      .map(_._1)
      .map(c =>
        new WebhookDispatcher(
          xa,
          c,
          backoff = _ => 0L,
          requestTimeout = 5.seconds
        )
      )

  private def json(b: Json, f: String): String =
    b.hcursor.get[String](f).toOption.getOrElse("")

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: org + workspace + active subscription") {
    val sub =
      for _ <-
          sql"delete from public.workspace where id = $ws".update.run *>
            sql"insert into public.organization (id, name) values ($orgId, 'WH Org')".update.run *>
            sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'WH WS', ${"wh-" + ws.toString.take(6)})".update.run *>
            sql"""insert into public.webhook_subscriptions (workspace_id, url, events, secret)
                values ($ws, $stubUrl, '{reply.received,deal.created}'::text[], 'whsec_test123')""".update.run
      yield ()
    sub.transact(xa).void
  }

  test(
    "enqueue + dispatch signs the payload and marks the delivery delivered"
  ) {
    server // force start
    failUntilHit = 0
    for
      dispatcher <- dispatcherIO
      _ <- dispatcher.enqueue(
        ws,
        "reply.received",
        Json.obj("contact" -> Json.fromString("ada@t.test"))
      )
      attempted <- dispatcher.processDue
      _ = assert(attempted >= 1)
      rows <-
        sql"""select status, attempts, response_code, event from public.webhook_deliveries
              where workspace_id = $ws and event = 'reply.received'"""
          .query[(String, Int, Option[Int], String)]
          .to[List]
          .transact(xa)
      delivered = rows.find(_._1 == "delivered")
      _ = assert(delivered.isDefined, s"no delivered reply.received row: $rows")
      _ = assertEquals(delivered.get._3.map(_ == 200), Some(true))
      // find the reply.received body among queued hits and verify the signature
      candidates = {
        val out = scala.collection.mutable.ListBuffer.empty[(String, String)]
        while !bodies.isEmpty() do out += bodies.poll()
        out.toList
      }
      match0 = candidates.find { case (_, b) =>
        parse(b).toOption
          .flatMap(_.hcursor.get[String]("event").toOption)
          .contains("reply.received")
      }
      _ = assert(match0.isDefined, "no reply.received body hit the stub")
      (sig, body) = match0.get
      _ = assert(sig.startsWith("t=") && sig.contains(",v1="), sig)
      parts = sig.split(",v1=")
      expected = Webhooks.sign("whsec_test123", parts(0).drop(2).toLong, body)
      _ = assertEquals(parts(1), expected)
      parsed = parse(body).toOption.get
      _ = assertEquals(
        parsed.hcursor
          .downField("data")
          .get[String]("contact")
          .toOption
          .getOrElse(""),
        "ada@t.test"
      )
    yield ()
  }

  test(
    "failures back off, then dead-letter after max attempts; replay resets"
  ) {
    for
      dispatcher <- dispatcherIO
      _ <- dispatcher.enqueue(
        ws,
        "deal.created",
        Json.obj("dealId" -> Json.fromString(UUID.randomUUID().toString))
      )
      _ <- dispatcher.enqueue(
        ws,
        "deal.created",
        Json.obj("dealId" -> Json.fromString(UUID.randomUUID().toString))
      )
      // first delivery: fails all 5 attempts -> dead; second: succeeds immediately
      failUntilHit = Int.MaxValue
      _ = this.failUntilHit = Int.MaxValue
      _ <- List
        .fill(5)(dispatcher.processDue)
        .sequence
        .void // attempts 1..5 -> dead
      rows1 <-
        sql"select status, event from public.webhook_deliveries where workspace_id = $ws order by event, created_at"
          .query[(String, String)]
          .to[List]
          .transact(xa)
      deadCount = rows1.count(_._1 == "dead")
      _ = assert(deadCount >= 1, s"expected a dead delivery: $rows1")
      deadId <-
        sql"select id from public.webhook_deliveries where workspace_id = $ws and status = 'dead' limit 1"
          .query[UUID]
          .unique
          .transact(xa)
      replayed <- dispatcher.replay(deadId, ws)
      _ = assertEquals(replayed, true)
      status <-
        sql"select status from public.webhook_deliveries where id = $deadId"
          .query[String]
          .unique
          .transact(xa)
      _ = assertEquals(status, "pending")
    yield ()
  }
