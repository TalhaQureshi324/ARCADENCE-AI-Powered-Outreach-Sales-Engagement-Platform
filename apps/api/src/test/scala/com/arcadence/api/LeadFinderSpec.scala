package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.db.DbUrl
import com.arcadence.core.leads.LeadProvider
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.implicits.*
import org.http4s.{Header, Method, Request, Status, Uri}

import java.util.UUID

/** Lead discovery and enrichment (Phase 10): sandbox search with credit
  * reserve→release→charge, provider-failure refunds, per-workspace reservation
  * serialization (no overdraft under concurrency), add-to-list with
  * dedupe/suppression/licensing enforcement, preview-only waterfall enrichment,
  * and tenant isolation.
  */
final class LeadFinderSpec extends CatsEffectSuite:

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
  private val ws2 = UUID.randomUUID()
  private val ws3 = UUID.randomUUID()
  private val userA = UUID.randomUUID()
  private val userB = UUID.randomUUID()
  private val userC = UUID.randomUUID()
  private val suffix = ws.toString.take(8)

  private val tokens = Map[String, AuthenticatedUser](
    "token-A" -> AuthenticatedUser(userA, s"lfa-$suffix@t.test"),
    "token-B" -> AuthenticatedUser(userB, s"lfb-$suffix@t.test"),
    "token-C" -> AuthenticatedUser(userC, s"lfc-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  /** Provider whose search always times out — used to verify the failed search
    * path refunds the reservation.
    */
  private val flakyProvider = new LeadProvider.Provider:
    val name = "flaky"
    val supportedFilters = Set.empty[String]
    def search(
        filters: LeadProvider.SearchFilters,
        page: Int
    ): IO[Either[LeadProvider.ProviderError, LeadProvider.Page]] =
      IO.pure(Left(LeadProvider.Timeout("test timeout after 30s")))
    def enrich(email: String, domain: Option[String]): IO[
      Either[LeadProvider.ProviderError, Option[LeadProvider.DiscoveredContact]]
    ] =
      IO.pure(Left(LeadProvider.Unavailable("test")))

  private lazy val routes = new LeadFinderRoutes(
    fakeAuth,
    xa,
    extraProviders = Map(flakyProvider.name -> flakyProvider)
  )
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

  private def jsonField(body: Json, field: String): String =
    body.hcursor.get[String](field).toOption.getOrElse("")

  private def jsonInt(body: Json, field: String): Int =
    body.hcursor.get[Int](field).toOption.getOrElse(-1)

  private def balance(): IO[Int] =
    request(Method.GET, s"/api/v1/workspaces/$ws/leadfinder/credits")
      .map(body => jsonInt(body._2, "balance"))

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: workspaces, owners, credit grants, lead list") {
    for
      _ <-
        (sql"delete from public.workspace where id in ($ws, $ws2, $ws3)".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'LF Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'LF WS', ${"lf-" + suffix})".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws2, $orgId, 'LF WS2', ${"lf2-" + suffix})".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws3, $orgId, 'LF WS3', ${"lf3-" + suffix})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"lfa-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userB, 'authenticated', 'authenticated',
                    ${"lfb-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userC, 'authenticated', 'authenticated',
                    ${"lfc-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'lfa') on conflict (id) do nothing".update.run *>
          sql"insert into public.profile (id, display_name) values ($userB, 'lfb') on conflict (id) do nothing".update.run *>
          sql"insert into public.profile (id, display_name) values ($userC, 'lfc') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws2, $userB, 'owner')".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws3, $userC, 'owner')".update.run *>
          sql"""insert into public.credit_transactions (workspace_id, delta, kind, reason)
                values ($ws, 20, 'grant', 'test grant'), ($ws3, 7, 'grant', 'test grant small')""".update.run *>
          // The starter-credits trigger (20261027120000) grants every fresh
          // workspace 50 credits; drain ws2 and ws3 so the scenarios below run
          // against deterministic balances (0 and 7).
          sql"""insert into public.credit_transactions (workspace_id, delta, kind, reason)
                values ($ws2, -50, 'charge', 'test drain'), ($ws3, -50, 'charge', 'test drain')""".update.run *>
          sql"insert into public.lead_list (workspace_id, name) values ($ws, 'LF List')".update.run *>
          sql"""insert into public.contact (workspace_id, email, first_name)
                values ($ws, ${"lf-existing-" + suffix + "@t.test"}, 'Exist')""".update.run).void
          .transact(xa)
      got <- balance()
    yield assertEquals(got, 70)
  }

  test("estimate reports per-result pricing") {
    for pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/leadfinder/estimate?maxResults=50"
      )
    yield
      assertEquals(pair._1, Status.Ok)
      assertEquals(jsonInt(pair._2, "estimatedCredits"), 50)
  }

  private var firstSearchId: UUID = UUID.randomUUID()
  private var listId: UUID = UUID.randomUUID()

  test("sandbox search charges only delivered results and labels provenance") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/leadfinder/search",
        body = Some(Json.obj("filters" -> Json.obj()))
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(jsonField(pair._2, "status"), "done")
      _ = assertEquals(jsonInt(pair._2, "resultsCount"), 3)
      _ = assertEquals(jsonInt(pair._2, "creditsCharged"), 3)
      sid = UUID.fromString(jsonField(pair._2, "id"))
      _ = firstSearchId = sid
      bal <- balance()
      _ = assertEquals(bal, 67) // 70 - 3 charged (5 reserved, 5 released)
      res <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/leadfinder/searches/$sid/results"
      )
      _ = assertEquals(res._1, Status.Ok)
      list = res._2.asArray.getOrElse(Vector.empty)
      _ = assertEquals(list.size, 3)
      _ = assertEquals(jsonField(list.head, "permittedUse"), "sandbox_only")
      _ = assertEquals(jsonField(list.head, "emailVerification"), "verified")
      cred <- request(Method.GET, s"/api/v1/workspaces/$ws/leadfinder/credits")
      kinds: Vector[String] = cred._2.hcursor
        .downField("history")
        .values
        .getOrElse(Vector.empty)
        .toVector
        .flatMap(_.hcursor.get[String]("kind").toOption)
      _ = assert(kinds.contains("reserve"))
      _ = assert(kinds.contains("refund"))
      _ = assert(kinds.contains("charge"))
    yield ()
  }

  test("provider timeout marks the search failed and refunds the reservation") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/leadfinder/search",
        body = Some(
          Json.obj(
            "filters" -> Json.obj(),
            "provider" -> Json.fromString("flaky")
          )
        )
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(jsonField(pair._2, "status"), "failed")
      _ = assert(jsonField(pair._2, "error").contains("timeout"))
      bal <- balance()
      _ = assertEquals(bal, 67)
    yield ()
  }

  test("unknown provider is rejected with a licensing explanation") {
    for pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/leadfinder/search",
        body = Some(
          Json.obj(
            "filters" -> Json.obj(),
            "provider" -> Json.fromString("apollo")
          )
        )
      )
    yield
      assertEquals(pair._1, Status.UnprocessableEntity)
      assertEquals(jsonField(pair._2, "code"), "validation_failed")
  }

  test("insufficient credits blocks the search before the provider runs") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws2/leadfinder/search",
        token = "token-B",
        body = Some(Json.obj("filters" -> Json.obj()))
      )
      _ = assertEquals(pair._1, Status.PaymentRequired)
      _ = assertEquals(jsonField(pair._2, "code"), "insufficient_credits")
      none <-
        sql"select count(*) from public.search_requests where workspace_id = $ws2"
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(none, 0)
      // The failed search wrote nothing to the ledger beyond the drain entry.
      reserves <-
        sql"select count(*) from public.credit_transactions where workspace_id = $ws2 and kind = 'reserve'"
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(reserves, 0)
      drained <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws2/leadfinder/credits",
        token = "token-B"
      )
      _ = assertEquals(jsonInt(drained._2, "balance"), 0)
    yield ()
  }

  test("concurrent reservations serialize — overdraft is impossible") {
    def go: IO[(Status, Json)] = request(
      Method.POST,
      s"/api/v1/workspaces/$ws3/leadfinder/search",
      token = "token-C",
      body = Some(Json.obj("filters" -> Json.obj()))
    )
    for
      results <- IO.both(go, go)
      (s1, b1) = results._1
      (s2, b2) = results._2
      statuses = List(s1, s2)
      _ = assert(
        statuses.count(_ == Status.Ok) == 1,
        s"expected exactly one success: $statuses"
      )
      _ = assert(
        statuses.count(_ == Status.PaymentRequired) == 1,
        s"expected exactly one 402: $statuses"
      )
      okBody = if s1 == Status.Ok then b1 else b2
      _ = assertEquals(jsonField(okBody, "status"), "done")
      bal <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws3/leadfinder/credits",
        token = "token-C"
      )
      _ = assertEquals(jsonInt(bal._2, "balance"), 4) // 7 - 3 charged
    yield ()
  }

  test(
    "add-to-list enforces licensing: sandbox-only contacts are never added"
  ) {
    for
      lists <-
        sql"select id from public.lead_list where workspace_id = $ws order by created_at limit 1"
          .query[UUID]
          .unique
          .transact(xa)
      _ = listId = lists
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/leadfinder/add-to-list",
        body = Some(
          Json.obj(
            "searchRequestId" -> Json.fromString(firstSearchId.toString),
            "listId" -> Json.fromString(listId.toString)
          )
        )
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(jsonInt(pair._2, "added"), 0)
      _ = assertEquals(
        pair._2.hcursor
          .get[Boolean]("sandboxOnlyBlocked")
          .toOption
          .getOrElse(true),
        true
      )
      members <-
        sql"select count(*) from public.list_membership where list_id = $lists"
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(members, 0)
    yield ()
  }

  test(
    "add-to-list dedupes existing contacts, respects suppression, re-run is idempotent"
  ) {
    val licEmail = s"lf-lic-$suffix@t.test"
    val dupEmail = s"lf-existing-$suffix@t.test"
    val suppEmail = s"lf-sup-$suffix@t.test"
    for
      srId <-
        sql"""insert into public.search_requests (workspace_id, requested_by, provider, status, results_count)
              values ($ws, $userA, 'sandbox', 'done', 3) returning id"""
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        (sql"""insert into public.discovered_contacts
              (workspace_id, search_request_id, provider, provider_contact_id, email, email_verification, permitted_use)
              values ($ws, $srId, 'sandbox', ${"pc-lic-" + suffix}, $licEmail, 'verified', 'licensed')""".update.run *>
          sql"""insert into public.discovered_contacts
              (workspace_id, search_request_id, provider, provider_contact_id, email, email_verification, permitted_use)
              values ($ws, $srId, 'sandbox', ${"pc-dup-" + suffix}, $dupEmail, 'verified', 'licensed')""".update.run *>
          sql"""insert into public.discovered_contacts
              (workspace_id, search_request_id, provider, provider_contact_id, email, email_verification, permitted_use)
              values ($ws, $srId, 'sandbox', ${"pc-sup-" + suffix}, $suppEmail, 'verified', 'licensed')""".update.run *>
          sql"""insert into public.suppression (workspace_id, email, reason)
                values ($ws, $suppEmail, 'manual')""".update.run).void
          .transact(xa)
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/leadfinder/add-to-list",
        body = Some(
          Json.obj(
            "searchRequestId" -> Json.fromString(srId.toString),
            "listId" -> Json.fromString(listId.toString)
          )
        )
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(jsonInt(pair._2, "added"), 1)
      _ = assertEquals(jsonInt(pair._2, "skippedDuplicates"), 1)
      _ = assertEquals(jsonInt(pair._2, "skippedSuppressed"), 1)
      // re-run adds nothing new
      again <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/leadfinder/add-to-list",
        body = Some(
          Json.obj(
            "searchRequestId" -> Json.fromString(srId.toString),
            "listId" -> Json.fromString(listId.toString)
          )
        )
      )
      _ = assertEquals(jsonInt(again._2, "added"), 0)
      members <-
        sql"""select count(*) from public.list_membership m
              join public.contact c on c.id = m.contact_id
              where m.list_id = $listId and c.email = $licEmail"""
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(members, 1)
      prov <-
        sql"""select count(*) from public.source_provenance where source = 'leadfinder'"""
          .query[Int]
          .unique
          .transact(xa)
      _ = assert(prov >= 1)
    yield ()
  }

  test(
    "enrich is a preview waterfall — provenance recorded, contact untouched"
  ) {
    val cEmail = "sandra.sandbox@example-sandbox.test"
    for
      cid <-
        sql"""insert into public.contact (workspace_id, email, job_title)
              values ($ws, $cEmail, 'Existing Title') returning id"""
          .query[UUID]
          .unique
          .transact(xa)
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/leadfinder/enrich/$cid"
      )
      _ = assertEquals(pair._1, Status.Ok)
      _ = assertEquals(jsonField(pair._2, "provider"), "sandbox")
      _ = assertEquals(
        pair._2.hcursor
          .downField("fields")
          .get[String]("jobTitle")
          .toOption
          .getOrElse(""),
        "Head of Growth"
      )
      title <-
        sql"select job_title from public.contact where id = $cid"
          .query[Option[String]]
          .unique
          .transact(xa)
      _ = assertEquals(title, Some("Existing Title"))
      prov <-
        sql"""select count(*) from public.provenance_records
              where contact_id = $cid and source = 'sandbox'"""
          .query[Int]
          .unique
          .transact(xa)
      _ = assert(prov >= 1)
      missing <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/leadfinder/enrich/${UUID.randomUUID()}"
      )
      _ = assertEquals(missing._1, Status.NotFound)
    yield ()
  }

  test(
    "tenant isolation: another workspace's owner cannot read search results"
  ) {
    for pair <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/leadfinder/searches/$firstSearchId/results",
        token = "token-B"
      )
    yield
      assertEquals(pair._1, Status.NotFound)
      assertEquals(jsonField(pair._2, "code"), "workspace_not_found")
  }
