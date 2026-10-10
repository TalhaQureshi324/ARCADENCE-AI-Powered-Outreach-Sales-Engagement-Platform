package com.arcadence.core.db

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie.implicits.*
import doobie.util.transactor.Transactor
import munit.FunSuite

import scala.util.Try

/** Integration connectivity + tenancy-policy verification (Phase 0.3
  * acceptance).
  *
  * Skipped entirely unless TEST_DATABASE_URL is set, so plain `sbt test` stays
  * hermetic in CI. Run manually with:
  *
  * TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:54322/postgres
  * sbt core/test
  *
  * Set TEST_DB_EXPECT_SCHEMA=false for connectivity-only runs against a
  * database that has not had migrations applied yet (e.g. staging before
  * `db push`).
  */
final class DbConnectivitySpec extends FunSuite:

  private val url = sys.env.getOrElse("TEST_DATABASE_URL", "")
  private val expectSchema =
    sys.env.getOrElse("TEST_DB_EXPECT_SCHEMA", "true") == "true"

  // Parse Supabase/JDBC-style URLs and pass credentials as explicit driver
  // properties: empty-string user/password overrides corrupt URL-embedded
  // credentials in the PostgreSQL driver (host parses as "user:pass@host").
  private val UrlRe =
    "^(?:jdbc:)?postgresql://(?:([^:@/]*)(?::([^@/]*))?@)?([^:/]+)(?::(\\d+))?/([^?]+)".r

  // Lazy: must not throw during construction when TEST_DATABASE_URL is unset
  // (the suite is skipped entirely in that case).
  private lazy val (dbUser, dbPassword, jdbcUrl) = url match
    case UrlRe(user, password, host, port, db) =>
      (
        Option(user).getOrElse("postgres"),
        Option(password).getOrElse("postgres"),
        s"jdbc:postgresql://$host:${Option(port).getOrElse("5432")}/$db"
      )
    case _ =>
      throw new IllegalArgumentException(s"Unparseable TEST_DATABASE_URL: $url")

  private lazy val xa = Transactor.fromDriverManager[IO](
    driver = "org.postgresql.Driver",
    url = jdbcUrl,
    user = dbUser,
    password = dbPassword,
    logHandler = None
  )

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      url.nonEmpty,
      "TEST_DATABASE_URL not set — database integration checks skipped"
    )
    super.beforeEach(context)

  test("connects and reports PostgreSQL server version") {
    val probe = sql"select 1".query[Int].unique.transact(xa).unsafeRunSync()
    val version = sql"select current_setting('server_version')"
      .query[String]
      .unique
      .transact(xa)
      .unsafeRunSync()
    assertEquals(probe, 1)
    assert(version.nonEmpty, "server version must be non-empty")
  }

  test("foundational tables exist") {
    assume(expectSchema, "schema checks disabled for this target")
    val tables = List(
      "profile",
      "organization",
      "workspace",
      "workspace_member",
      "invitation",
      "job_queue",
      "audit_log",
      "webhook_endpoint",
      "webhook_delivery"
    )
    val found = sql"""
      select table_name from information_schema.tables
      where table_schema = 'public'
    """.query[String].to[List].transact(xa).unsafeRunSync()
    tables.foreach { t =>
      assert(found.contains(t), s"expected table public.$t to exist")
    }
  }

  test("RLS is enabled on every tenant table") {
    assume(expectSchema, "schema checks disabled for this target")
    val tenantTables = List(
      "profile",
      "organization",
      "workspace",
      "workspace_member",
      "invitation",
      "audit_log",
      "webhook_endpoint",
      "webhook_delivery"
    )
    val rlsEnabled = sql"""
      select c.relname, c.relrowsecurity
      from pg_class c join pg_namespace n on n.oid = c.relnamespace
      where n.nspname = 'public' and c.relkind = 'r'
    """.query[(String, Boolean)].to[List].transact(xa).unsafeRunSync().toMap
    tenantTables.foreach { t =>
      assertEquals(
        rlsEnabled.get(t),
        Some(true),
        s"RLS must be enabled on public.$t"
      )
    }
  }

  test("tenancy helper functions exist") {
    assume(expectSchema, "schema checks disabled for this target")
    val expected = List(
      "current_user_id",
      "current_user_email",
      "is_workspace_member",
      "has_workspace_role"
    )
    val found = sql"""
      select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace
      where n.nspname = 'public'
    """.query[String].to[List].transact(xa).unsafeRunSync()
    expected.foreach { f =>
      assert(found.contains(f), s"expected function public.$f to exist")
    }
  }

  test("enum catalog matches design") {
    assume(expectSchema, "schema checks disabled for this target")
    val expected = List(
      "workspace_role",
      "email_provider",
      "connection_status",
      "contact_verification",
      "contact_state",
      "field_type",
      "suppression_reason",
      "suppression_source",
      "campaign_status",
      "step_type",
      "enrollment_status",
      "action_type",
      "action_status",
      "message_direction",
      "message_status",
      "conversation_status",
      "email_event_type",
      "activity_type",
      "ai_kind",
      "ai_status",
      "subscription_status"
    )
    val found = sql"""
      select typname from pg_type t join pg_namespace n on n.oid = t.typnamespace
      where n.nspname = 'public' and t.typtype = 'e'
    """.query[String].to[List].transact(xa).unsafeRunSync()
    expected.foreach { e =>
      assert(found.contains(e), s"expected enum type public.$e to exist")
    }
  }

  test("private storage buckets exist in Supabase environments") {
    assume(expectSchema, "schema checks disabled for this target")
    val storageSchemaExists = sql"""
      select count(*) from information_schema.schemata where schema_name = 'storage'
    """.query[Int].unique.transact(xa).unsafeRunSync()
    assume(
      storageSchemaExists > 0,
      "no storage schema — not a Supabase database"
    )
    val buckets = sql"""
      select id, public from storage.buckets
    """.query[(String, Boolean)].to[List].transact(xa).unsafeRunSync().toMap
    List("imports", "exports", "attachments").foreach { b =>
      assertEquals(
        buckets.get(b),
        Some(false),
        s"bucket $b must exist and be private"
      )
    }
  }
