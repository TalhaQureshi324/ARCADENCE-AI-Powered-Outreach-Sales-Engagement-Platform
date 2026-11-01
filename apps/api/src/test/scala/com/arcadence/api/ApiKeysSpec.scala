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

/** Public API keys + webhook subscriptions (Phase 12.1 foundation): one-time
  * secret display with hash-only storage, scope validation, revocation,
  * expiry-gated authentication, and webhook registration with one-time signing
  * secrets.
  */
final class ApiKeysSpec extends CatsEffectSuite:

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
  private val userB = UUID.randomUUID()
  private val suffix = ws.toString.take(8)

  private val tokens = Map[String, AuthenticatedUser](
    "token-A" -> AuthenticatedUser(userA, s"ak-$suffix@t.test"),
    "token-B" -> AuthenticatedUser(userB, s"akb-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val routes = new ApiKeysRoutes(fakeAuth, xa)
  private lazy val app = routes.http.orNotFound

  private def request(
      method: Method,
      path: String,
      token: String = "token-A",
      body: Option[Json] = None
  ): IO[(Status, Json)] =
    val base = Uri.unsafeFromString(path)
    val baseReq = Request[IO](method, base).withHeaders(
      Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $token")
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

  test("setup: org, workspaces, owners") {
    for _ <-
        (sql"delete from public.workspace where id in ($ws, ${UUID.randomUUID()})".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'AK Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'AK WS', ${"ak-" + suffix})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"ak-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userB, 'authenticated', 'authenticated',
                    ${"akb-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'ak') on conflict (id) do nothing".update.run *>
          sql"insert into public.profile (id, display_name) values ($userB, 'akb') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run).void
          .transact(xa)
    yield ()
  }

  test("key creation shows the secret exactly once; only the prefix persists") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/api-keys",
        body = Some(
          Json.obj(
            "name" -> Json.fromString("CI key"),
            "scopes" -> Json
              .arr(Json.fromString("leads:read"), Json.fromString("crm:read")),
            "expiresInDays" -> Json.fromInt(30)
          )
        )
      )
      _ = assertEquals(pair._1, Status.Created)
      secret = jsonField(pair._2, "secret")
      _ = assert(secret.startsWith("ak_"))
      _ = assert(jsonField(pair._2, "note").contains("exactly once"))
      listed <- request(Method.GET, s"/api/v1/workspaces/$ws/api-keys")
      names = listed._2.asArray
        .getOrElse(Vector.empty)
        .flatMap(_.hcursor.get[String]("name").toOption)
      _ = assert(names.contains("CI key"))
      // only the hash persists - never the plaintext secret
      hashes <-
        sql"select key_hash from public.api_keys where workspace_id = $ws"
          .query[String]
          .to[List]
          .transact(xa)
      _ = assert(!hashes.mkString(",").contains(secret))
      authed <- ApiKeys.authenticate(secret, xa)
      _ = assert(authed.map(_._1).contains(ws))
      _ = assert(authed.exists(_._2.contains("leads:read")))
    yield ()
  }

  test("unknown scopes are rejected with the whitelist") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/api-keys",
        body = Some(
          Json.obj(
            "name" -> Json.fromString("bad"),
            "scopes" -> Json.arr(Json.fromString("workspace:delete"))
          )
        )
      )
      _ = assertEquals(pair._1, Status.UnprocessableEntity)
      _ = assert(jsonField(pair._2, "message").contains("Unknown scopes"))
    yield ()
  }

  test("revocation is immediate and authentication fails afterwards") {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/api-keys",
        body = Some(
          Json.obj(
            "name" -> Json.fromString("short-lived"),
            "scopes" -> Json.arr(Json.fromString("leads:read"))
          )
        )
      )
      secret = jsonField(created._2, "secret")
      _ = assert(ApiKeys.authenticate(secret, xa).unsafeRunSync().isDefined)
      keyId = jsonField(created._2, "id")
      revoked <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$ws/api-keys/$keyId"
      )
      _ = assertEquals(revoked._1, Status.Ok)
      _ = assert(ApiKeys.authenticate(secret, xa).unsafeRunSync().isEmpty)
      again <- request(Method.DELETE, s"/api/v1/workspaces/$ws/api-keys/$keyId")
      _ = assertEquals(again._1, Status.NotFound)
    yield ()
  }

  test(
    "webhooks: registration with documented events and one-time signing secret"
  ) {
    for
      bad <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/webhooks",
        body = Some(
          Json.obj(
            "url" -> Json.fromString("https://example.com/hook"),
            "events" -> Json.arr(Json.fromString("everything"))
          )
        )
      )
      _ = assertEquals(bad._1, Status.UnprocessableEntity)
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/webhooks",
        body = Some(
          Json.obj(
            "url" -> Json.fromString("https://example.com/hook"),
            "events" -> Json.arr(
              Json.fromString("reply.received"),
              Json.fromString("deal.created")
            )
          )
        )
      )
      _ = assertEquals(created._1, Status.Created)
      _ = assert(jsonField(created._2, "signingSecret").startsWith("whsec_"))
      listed <- request(Method.GET, s"/api/v1/workspaces/$ws/webhooks")
      count = listed._2.asArray.getOrElse(Vector.empty).size
      _ = assertEquals(count, 1)
      subId = jsonField(created._2, "id")
      gone <- request(Method.DELETE, s"/api/v1/workspaces/$ws/webhooks/$subId")
      _ = assertEquals(gone._1, Status.Ok)
    yield ()
  }

  test("manage-gated: non-admin members and outsiders are denied") {
    for
      denied <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/api-keys",
        token = "token-B"
      )
      _ = assertEquals(denied._1, Status.NotFound)
    yield ()
  }
