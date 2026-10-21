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

/** Agency management (Phase 9.3/9.4): parent-org admin sees consolidated
  * reporting across client workspaces; non-agency users and unrelated clients
  * get 404; isolation preserved (aggregates only, no content).
  */
final class AgencySpec extends CatsEffectSuite:

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

  private val agencyOrg = UUID.randomUUID()
  private val agencyWs = UUID.randomUUID()
  private val agencyUser = UUID.randomUUID()
  private val clientOrg = UUID.randomUUID()
  private val clientWs = UUID.randomUUID()
  private val clientWs2 = UUID.randomUUID()
  private val outsider = UUID.randomUUID()

  private val tokens = Map(
    "agency-admin" -> AuthenticatedUser(agencyUser, "agency@t.test"),
    "outsider" -> AuthenticatedUser(outsider, "out@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private lazy val routes = new AgencyRoutes(fakeAuth, xa).http.orNotFound

  private def request(
      method: Method,
      path: String,
      token: String
  ): IO[(Status, Json)] =
    val base = Uri.unsafeFromString(path)
    val req = Request[IO](method, base).withHeaders(
      Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $token")
    )
    routes
      .run(req)
      .flatMap(res =>
        res.bodyText.compile.string
          .map(t => (res.status, parse(t).toOption.getOrElse(Json.Null)))
      )

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: agency org/ws + two client workspaces under one child org") {
    for
      _ <-
        (sql"delete from public.workspace where name in ('Agency WS', 'Client A', 'Client B')".update.run *>
          sql"delete from public.organization where name in ('Agency Org', 'Client Org')".update.run).void
          .transact(xa)
      _ <-
        (sql"insert into public.organization (id, name) values ($agencyOrg, 'Agency Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($agencyWs, $agencyOrg, 'Agency WS', ${"ag-" + agencyWs.toString.take(12)})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $agencyUser, 'authenticated', 'authenticated',
                    ${s"agency-" + agencyUser.toString.take(
              8
            ) + "@t.test"}, '', now(), now(), now()) on conflict (id) do nothing
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($agencyUser, 'agency') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($agencyWs, $agencyUser, 'owner')".update.run *>
          sql"insert into public.organization (id, name, parent_organization_id) values ($clientOrg, 'Client Org', $agencyOrg)".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($clientWs, $clientOrg, 'Client A', ${"ca-" + clientWs.toString.take(12)})".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($clientWs2, $clientOrg, 'Client B', ${"cb-" + clientWs2.toString.take(12)})".update.run).void
          .transact(xa)
      _ <-
        sql"insert into public.contact (workspace_id, email, state) values ($clientWs, 'a@client.test', 'active')".update.run
          .transact(xa)
    yield ()
  }

  test("agency admin: client workspace list + consolidated reporting") {
    for
      clients <- request(
        Method.GET,
        s"/api/v1/agency/$agencyWs/clients",
        "agency-admin"
      )
      names = clients._2.asArray
        .getOrElse(Vector.empty)
        .flatMap(o => o.hcursor.get[String]("name").toOption)
      report <- request(
        Method.GET,
        s"/api/v1/agency/$agencyWs/reporting",
        "agency-admin"
      )
      _ <- IO {
        assertEquals(clients._1, Status.Ok)
        assertEquals(names.sorted.toList, List("Client A", "Client B"))
        assertEquals(report._1, Status.Ok)
        val leads = report._2.hcursor.get[Int]("emailsSubmitted").toOption
        assert(leads.isDefined)
      }
    yield ()
  }

  test("isolation: outsider and client-scoped access get 404, never data") {
    for
      outsiderClients <- request(
        Method.GET,
        s"/api/v1/agency/$agencyWs/clients",
        "outsider"
      )
      outsiderReport <- request(
        Method.GET,
        s"/api/v1/agency/$agencyWs/reporting",
        "outsider"
      )
      clientWsAsAgency <- request(
        Method.GET,
        s"/api/v1/agency/$clientWs/clients",
        "agency-admin"
      )
      _ <- IO {
        assertEquals(outsiderClients._1, Status.NotFound)
        assertEquals(outsiderReport._1, Status.NotFound)
        // A client workspace is not itself an agency workspace.
        assertEquals(clientWsAsAgency._1, Status.NotFound)
      }
    yield ()
  }
