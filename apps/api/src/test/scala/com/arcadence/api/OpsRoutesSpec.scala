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

/** Managed domain inventory (13.2) + data export / privacy workflows (13.3).
  * Domain status honestly distinguishes manual setup from provisioning. Export
  * aggregates workspace data; status transitions are whitelist-gated.
  */
final class OpsRoutesSpec extends CatsEffectSuite:

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
    "token-A" -> AuthenticatedUser(userA, s"ops-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val routes = new OpsRoutes(fakeAuth, xa)
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

  private def jsonField(b: Json, f: String): String =
    b.hcursor.get[String](f).toOption.getOrElse("")

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: org, ws, owner, contact + campaign for export") {
    for _ <-
        (sql"delete from public.workspace where id = $ws".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'OPS Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'OPS WS', ${"ops-" + suffix})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"ops-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'ops') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run *>
          sql"""insert into public.contact (workspace_id, email, first_name)
                values ($ws, ${"ops-" + suffix + "@t.test"}, 'Ada')""".update.run *>
          sql"""insert into public.campaign (workspace_id, name, status) values ($ws, 'OPS campaign', 'draft')""".update.run).void
          .transact(xa)
    yield ()
  }

  test("domains: register, upsert dedupe, status whitelist, lifecycle") {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/domains",
        body = Some(
          Json.obj(
            "domain" -> Json.fromString("Example.COM"),
            "registrar" -> Json.fromString("Namecheap"),
            "renewalDate" -> Json.fromString("2027-06-01")
          )
        )
      )
      _ = assertEquals(created._1, Status.Created)
      _ = assertEquals(jsonField(created._2, "status"), "manual_setup")
      _ = assertEquals(jsonField(created._2, "domain"), "example.com")
      again <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/domains",
        body = Some(Json.obj("domain" -> Json.fromString("example.com")))
      )
      _ = assertEquals(again._1, Status.Created) // upsert dedupe
      listed <- request(Method.GET, s"/api/v1/workspaces/$ws/domains")
      count = listed._2.asArray.getOrElse(Vector.empty).size
      _ = assertEquals(count, 1)
      domainId = jsonField(created._2, "id")
      active <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$ws/domains/$domainId?status=active"
      )
      _ = assertEquals(jsonField(active._2, "status"), "active")
      badStatus <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$ws/domains/$domainId?status=everything"
      )
      _ = assertEquals(badStatus._1, Status.UnprocessableEntity)
    yield ()
  }

  test("data export returns workspace records and logs the privacy request") {
    for
      pair <- request(Method.GET, s"/api/v1/workspaces/$ws/data-export")
      _ = assertEquals(pair._1, Status.Ok)
      contacts = pair._2.hcursor
        .downField("contacts")
        .values
        .getOrElse(Vector.empty)
        .size
      _ = assertEquals(contacts, 1)
      campaigns = pair._2.hcursor
        .downField("campaigns")
        .values
        .getOrElse(Vector.empty)
        .size
      _ = assertEquals(campaigns, 1)
      requests <-
        sql"select count(*) from public.privacy_requests where workspace_id = $ws and request_type = 'export' and status = 'completed'"
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(requests, 1)
    yield ()
  }
