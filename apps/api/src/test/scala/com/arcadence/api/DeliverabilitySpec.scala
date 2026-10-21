package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.db.DbUrl
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import munit.CatsEffectSuite

import java.util.UUID

/** Deliverability Command Center (Phase 8): DNS diagnostics (provider-aware,
  * SPF 10-lookup limit, DKIM/DMARC), health thresholds with automatic
  * suspension, permission-gated placement tests that report honestly.
  */
final class DeliverabilitySpec extends CatsEffectSuite:

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

  private val org = UUID.randomUUID()
  private val ws = UUID.randomUUID()

  /** Controllable DNS: routes domains to canned TXT records. */
  private class StubResolver extends Deliverability.Resolver:
    val records =
      scala.collection.mutable.Map.empty[(String, String), List[String]]
    def apply(domain: String, recordType: String): IO[List[String]] =
      IO.pure(records.getOrElse((domain, recordType), Nil))

  private lazy val resolver = new StubResolver
  // Keep compiler happy: routes built below with real auth stub
  private val stubAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(token match
        case "admin" =>
          Right(AuthenticatedUser(UUID.randomUUID(), "admin@t.test"))
        case other => Left(AuthError.Invalid))

  test("SPF: missing, duplicate, +all and >10 lookups each produce findings") {
    val missing = Deliverability.checkSpf("x.test", Nil)
    assertEquals(missing.head.ok, false)

    val dup =
      Deliverability.checkSpf("x.test", List("v=spf1 ~all", "v=spf1 -all"))
    assertEquals(dup.head.ok, false)

    val plusAll = Deliverability.checkSpf("x.test", List("v=spf1 +all"))
    assert(plusAll.exists(_.message.contains("+all")))

    val many = "v=spf1 " + (1 to 11)
      .map(i => s"include:spf$i.example.com")
      .mkString(" ") + " ~all"
    val over = Deliverability.checkSpf("x.test", List(many))
    assert(over.exists(_.message.contains("10-lookup limit")))

    val good = Deliverability.checkSpf(
      "x.test",
      List("v=spf1 include:spf.example.com ~all")
    )
    assert(good.head.ok)
  }

  test(
    "DMARC: missing record and missing rua produce findings; p=none suggests tightening"
  ) {
    val missing = Deliverability.checkDmarc("x.test", Nil)
    assert(missing.exists(_.message.contains("No DMARC record")))

    val noRua = Deliverability.checkDmarc("x.test", List("v=DMARC1; p=none"))
    assert(noRua.exists(_.message.contains("rua")))
    assert(noRua.exists(_.message.contains("p=none")))
  }

  test("health: thresholds escalate transparently") {
    val healthy = Deliverability.evaluateHealth(
      accepted = 100,
      bounceHard = 2,
      authFailures = 0,
      suppressed = 0,
      scheduled = 100
    )
    assertEquals(healthy.status, "healthy")

    val warn = Deliverability.evaluateHealth(
      accepted = 100,
      bounceHard = 6,
      authFailures = 2,
      suppressed = 0,
      scheduled = 100
    )
    assertEquals(warn.status, "warning")

    val risk = Deliverability.evaluateHealth(
      accepted = 100,
      bounceHard = 15,
      authFailures = 6,
      suppressed = 20,
      scheduled = 100
    )
    assertEquals(risk.status, "risk")
    assert(risk.reasons.nonEmpty)
  }

  test("setup: workspace for persistence checks") {
    (sql"insert into public.organization (id, name) values ($org, 'Deliv Org')".update.run *>
      sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $org, 'Deliv WS', ${"dl-" + ws.toString.take(12)})".update.run).void
      .transact(xa)
      .map(_ => assert(true))
  }

  test("auto-pause: risk evaluation suspends connected account + emits alert") {
    for
      acct <-
        sql"""insert into public.email_accounts (workspace_id, provider, email_address, status)
              values ($ws, 'smtp_imap', ${"p-" + ws.toString.take(
            6
          ) + "@t.test"}, 'connected') returning id"""
          .query[UUID]
          .unique
          .transact(xa)
      risky = Deliverability.evaluateHealth(
        accepted = 100,
        bounceHard = 20,
        authFailures = 6,
        suppressed = 0,
        scheduled = 100
      )
      _ <- Deliverability.applyPause(xa, ws, acct, risky)
      status <-
        sql"select status::text from public.email_accounts where id = $acct"
          .query[String]
          .unique
          .transact(xa)
      events <-
        sql"select count(*) from public.event_outbox where aggregate_id = ${acct.toString} and event_type = 'health_alert'"
          .query[Long]
          .unique
          .transact(xa)
      _ <- IO {
        assertEquals(status, "suspended")
        assertEquals(events, 1L)
      }
    yield ()
  }
