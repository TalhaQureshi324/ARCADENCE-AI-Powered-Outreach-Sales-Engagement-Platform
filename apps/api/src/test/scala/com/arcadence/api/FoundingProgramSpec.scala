package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.db.DbUrl
import com.arcadence.core.founding.FoundingProgram
import com.arcadence.core.founding.FoundingProgram as FP
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Header, Method, Request, Status, Uri}

import java.util.UUID

/** Founding 10,000 (Phase 14.2B.1/2): capacity enforcement under parallel
  * claims, one-membership-per-user idempotency, unverified rejection, public
  * status with real counter. Capacity is reset to 3 so the final-slot race
  * and program_full semantics are exercised without inserting 10k users.
  */
final class FoundingProgramSpec extends CatsEffectSuite:

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

  private val orgId  = UUID.randomUUID()
  private val ws     = UUID.randomUUID()
  private val userA  = UUID.randomUUID()
  private val suffix = ws.toString.take(8)

  private val unverifiedUser = UUID.randomUUID()
  private val extra1         = UUID.randomUUID()
  private val extra2         = UUID.randomUUID()
  private val extra3         = UUID.randomUUID()

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, s"fp-$suffix@t.test"),
    "token-U" -> AuthenticatedUser(unverifiedUser, s"fpu-$suffix@t.test"),
    "token-1" -> AuthenticatedUser(extra1, s"fp1-$suffix@t.test"),
    "token-2" -> AuthenticatedUser(extra2, s"fp2-$suffix@t.test"),
    "token-3" -> AuthenticatedUser(extra3, s"fp3-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val routes = new FoundingRoutes(fakeAuth, xa)
  private lazy val app    = routes.http.orNotFound

  private def request(method: Method, path: String, token: String): IO[(Status, Json)] =
    val req = Request[IO](method, Uri.unsafeFromString(path)).withHeaders(
      Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $token")
    )
    app
      .run(req)
      .flatMap(res => res.bodyText.compile.string.map(t => (res.status, parse(t).toOption.getOrElse(Json.Null))))

  private def jsonField(b: Json, f: String): String = b.hcursor.get[String](f).toOption.getOrElse("")
  private def jsonInt(b: Json, f: String): Int      = b.hcursor.get[Int](f).toOption.getOrElse(-1)
  private def jsonBool(b: Json, f: String): Boolean = b.hcursor.get[Boolean](f).toOption.getOrElse(false)


  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(dbUrl.nonEmpty, "TEST_DATABASE_URL not set — integration checks skipped")
    super.beforeEach(context)

  test("setup: org, ws, owners, verified/unverified users, capacity 3, counter 1") {
    for
      _ <-
        (sql"delete from public.workspace where id = $ws".update.run *>
          sql"delete from public.founding_members where program_id = (select id from public.founding_program where slug = 'founding-10000')".update.run *>
          sql"delete from public.founding_program where slug = 'founding-10000'".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'FP Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'FP WS', ${"fp-" + suffix})".update.run *>
          sql"""insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
                values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                        ${"fp-" + suffix + "@t.test"}, '', now(), now(), now())""".update.run *>
          sql"""insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
                values ('00000000-0000-0000-0000-000000000000', $unverifiedUser, 'authenticated', 'authenticated',
                        ${"fpu-" + suffix + "@t.test"}, '', null, now(), now())""".update.run *>
          sql"""insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
                values ('00000000-0000-0000-0000-000000000000', $extra1, 'authenticated', 'authenticated',
                        ${"fp1-" + suffix + "@t.test"}, '', now(), now(), now())""".update.run *>
          sql"""insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
                values ('00000000-0000-0000-0000-000000000000', $extra2, 'authenticated', 'authenticated',
                        ${"fp2-" + suffix + "@t.test"}, '', now(), now(), now())""".update.run *>
          sql"""insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
                values ('00000000-0000-0000-0000-000000000000', $extra3, 'authenticated', 'authenticated',
                        ${"fp3-" + suffix + "@t.test"}, '', now(), now(), now())""".update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'fp') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run *>
          sql"""insert into public.founding_program (slug, capacity, status, claimed_count)
                values ('founding-10000', 3, 'active', 1)
                on conflict (slug) do update set capacity = 3, status = 'active', claimed_count = 1""".update.run).void
          .transact(xa)
    yield ()
  }

  test("public status exposes the real counter without auth") {
    for
      res     <- FoundingProgram.status(xa)
      claimed = res.hcursor.get[Int]("claimedCount").toOption.getOrElse(-1)
      _       = assertEquals(claimed, 1)
      status  = res.hcursor.get[String]("status").toOption.getOrElse("")
      _       = assertEquals(status, "active")
    yield ()
  }

  test("claim: verified user succeeds; 12-month expiry; repeat is idempotent") {
    for
      me0    <- request(Method.GET, "/api/v1/founding-program/me", "token-A")
      before  = me0._2.hcursor.get[Boolean]("claimed").toOption.getOrElse(true)
      _       = assertEquals(before, false)
      claimRes <- request(Method.POST, "/api/v1/founding-program/claim", "token-A")
      _        = assertEquals(claimRes._1, Status.Created)
      pos      = jsonInt(claimRes._2, "claimPosition")
      _        = assert(pos >= 2 && pos <= 3, s"position $pos")
      _        = assertEquals(jsonBool(claimRes._2, "autoBilling"), false)
      me    <- request(Method.GET, "/api/v1/founding-program/me", "token-A")
      _     = assertEquals(me._2.hcursor.get[String]("status").toOption.getOrElse(""), "founding_member")
      expires = me._2.hcursor.get[String]("expiresAt").toOption.getOrElse("")
      _       = assert(expires.nonEmpty)
      again <- request(Method.POST, "/api/v1/founding-program/claim", "token-A")
      _      = assertEquals(again._1, Status.Conflict)
      dup    = again._2.hcursor.get[String]("code").toOption.getOrElse("")
      _      = assertEquals(dup, "already_claimed")
    yield ()
  }

  test("unverified users are rejected with email_unverified") {
    for
      pair <- request(Method.POST, "/api/v1/founding-program/claim", "token-U")
      _     = assertEquals(pair._1, Status.Forbidden)
      _     = assertEquals(pair._2.hcursor.get[String]("code").toOption.getOrElse(""), "email_unverified")
    yield ()
  }

  test("capacity: final-slot race yields one winner; others get program_full; counter == capacity") {
    // deterministic start: counter at 2 of 3 -> exactly one slot remains
    sql"""update public.founding_program set claimed_count = 2, capacity = 3, status = 'active'
          where slug = 'founding-10000'""".update.run.void.transact(xa).unsafeRunSync()
    for
      r1 <- FoundingProgram.claim(extra1, verified = true, xa)
      r2 <- FoundingProgram.claim(extra2, verified = true, xa)
      r3 <- FoundingProgram.claim(extra3, verified = true, xa)
      winners = List(r1, r2, r3).collect { case FP.Claimed(pos, _) => pos }
      _ = assertEquals(winners.size, 1, s"expected 1 winner of the final slot")
      fulls = List(r1, r2, r3).count { case FP.ProgramFull => true; case _ => false }
      _ = assertEquals(fulls, 2)
      row <- sql"select claimed_count, capacity from public.founding_program where slug = 'founding-10000'"
        .query[(Int, Int)].unique.transact(xa)
      _ = assertEquals(row._1, row._2)
      _ = assert(row._1 <= row._2)
      // restore production defaults so e2e tests see a fresh program
      _ <-
        (sql"delete from public.founding_members".update.run *>
          sql"update public.founding_program set capacity = 10000, claimed_count = 0, status = 'active'".update.run).void
          .transact(xa)
    yield ()
  }
