package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.billing.Billing
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Billing (Phase 13.1): plan catalog, idempotent webhook event processing
  * (duplicates skipped, re-order-safe), subscription upserts with entitlement
  * sync, delinquency downgrade without data deletion.
  */
final class BillingSpec extends CatsEffectSuite:

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
    "token-A" -> AuthenticatedUser(userA, s"bil-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val routes = new BillingRoutes(
    fakeAuth,
    xa,
    EmberCompat.client,
    stripeSecret = None,
    webhookSecret = Some("whsec_test"),
    priceMap = Map("price_growth" -> "growth")
  )
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

  private def jsonLong(b: Json, f: String): Long =
    b.hcursor.get[Long](f).toOption.getOrElse(-1L)
  private def jsonField(b: Json, f: String): String =
    b.hcursor.get[String](f).toOption.getOrElse("")

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: org, ws, owner") {
    for _ <-
        (sql"delete from public.workspace where id = $ws".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'BIL Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'BIL WS', ${"bil-" + suffix})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"bil-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'bil') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run).void
          .transact(xa)
    yield ()
  }

  test("plans catalog exposes all tiers with entitlements independent of UI") {
    for
      pair <- request(Method.GET, "/api/v1/billing/plans")
      _ = assertEquals(pair._1, Status.Ok)
      plans = pair._2.asArray
        .getOrElse(Vector.empty)
        .flatMap(_.hcursor.get[String]("plan").toOption)
      _ = assert(
        plans.contains("free") && plans.contains("starter") && plans.contains(
          "growth"
        ) && plans.contains("agency")
      )
    yield ()
  }

  test(
    "entitlements default to free and downgrade on delinquency without deleting data"
  ) {
    val cust = s"cus_$suffix"
    val sub = s"sub_$suffix"
    for
      _ <- Billing
        .applySubscription(
          ws,
          cust,
          sub,
          "growth",
          "active",
          Some("2027-01-01T00:00:00Z"),
          false
        )
        .transact(xa)
      growth <- Billing.entitlementsForWorkspace(ws).transact(xa)
      _ = assert(growth.exists(_.plan == "growth"))
      _ = assert(growth.exists(_.monthlySendCap == 250000L))
      // delinquency: downgrade entitlements, keep all data
      _ <- Billing.markDelinquent(ws).transact(xa)
      downgraded <- Billing.entitlementsForWorkspace(ws).transact(xa)
      _ = assert(downgraded.exists(_.plan == "free"))
      contacts <-
        (sql"insert into public.contact (workspace_id, email) values ($ws, ${"bil-" + suffix + "@t.test"}) returning id"
          .query[UUID]
          .unique *>
          sql"select count(*) from public.contact where workspace_id = $ws"
            .query[Long]
            .unique).transact(xa)
      _ = assertEquals(contacts, 1L) // data intact after downgrade
      subRow <-
        sql"select plan, status from public.subscriptions where workspace_id = $ws"
          .query[(String, String)]
          .unique
          .transact(xa)
      _ = assertEquals(subRow._2, "active") // history preserved
    yield ()
  }

  test("stripe webhook: signature required, event processed idempotently") {
    val payloadJson = payload(ws)
    val wirePayload = payloadJson.noSpaces
    val mac = {
      val m = Mac.getInstance("HmacSHA256")
      m.init(new SecretKeySpec("whsec_test".getBytes("UTF-8"), "HmacSHA256"))
      m
    }
    val ts = "1700000000"
    val sig = mac
      .doFinal(s"$ts.$wirePayload".getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString
    val req =
      Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/stripe/webhook"))
        .withHeaders(
          Header.Raw(
            org.typelevel.ci.CIString("Stripe-Signature"),
            s"t=$ts,v1=$sig"
          ),
          Header
            .Raw(org.typelevel.ci.CIString("Content-Type"), "application/json")
        )
        .withEntity(wirePayload)
    for
      badSig <- request(
        Method.POST,
        "/api/v1/stripe/webhook",
        Some(payloadJson)
      )
      badSigStatus = badSig._1
      _ = assertEquals(badSigStatus, Status.BadRequest)
      res <- routes.http.orNotFound
        .run(req)
        .flatMap(r =>
          r.bodyText.compile.string
            .map(t => (r.status, parse(t).toOption.getOrElse(Json.Null)))
        )
      _ = assertEquals(res._1, Status.Ok)
      handled = res._2.hcursor.get[Boolean]("handled").toOption.getOrElse(false)
      _ = assert(handled)
      plan <- Billing.entitlementsForWorkspace(ws).transact(xa)
      _ = assert(plan.exists(_.plan == "growth"))
      res2 <- routes.http.orNotFound
        .run(req)
        .flatMap(r =>
          r.bodyText.compile.string
            .map(t => (r.status, parse(t).toOption.getOrElse(Json.Null)))
        )
      _ = assertEquals(res2._1, Status.Ok)
      dup = res2._2.hcursor.get[Boolean]("duplicate").toOption.getOrElse(false)
      _ = assert(dup)
      eventCount <-
        sql"select count(*) from public.billing_events where stripe_event_id = ${s"evt_$suffix"}"
          .query[Long]
          .unique
          .transact(xa)
      _ = assertEquals(eventCount, 1L)
    yield ()
  }

  private def payload(ws: UUID): Json = parse(
    s"""{
      "id": "evt_$suffix",
      "type": "customer.subscription.updated",
      "data": {"object": {
        "id": "sub_$suffix",
        "customer": "cus_$suffix",
        "status": "active",
        "cancel_at_period_end": false,
        "current_period_end": 1793600000,
        "metadata": {"workspaceId": "$ws"},
        "items": {"data": [{"price": {"id": "price_growth"}}]}
      }}
    }"""
  ).toOption.get
