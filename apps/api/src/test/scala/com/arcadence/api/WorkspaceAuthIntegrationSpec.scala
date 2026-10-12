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

/** Cross-tenant authorization matrix (Phase 1.2 acceptance): User A's workspace
  * records are invisible and unmodifiable to User B at every verb; membership
  * is resolved from the database, never from headers.
  *
  * Runs against a real database — set TEST_DATABASE_URL (local stack:
  * postgresql://postgres:postgres@127.0.0.1:54322/postgres).
  */
final class WorkspaceAuthIntegrationSpec extends CatsEffectSuite:

  private val dbUrl = sys.env.getOrElse("TEST_DATABASE_URL", "")
  private val jdbc =
    DbUrl.parse(dbUrl).fold(_ => sys.error("bad TEST_DATABASE_URL"), identity)
  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    jdbc.jdbcUrl,
    jdbc.user,
    jdbc.password,
    logHandler = None
  )

  private val userA = UUID.randomUUID() // owner
  private val userB =
    UUID.randomUUID() // outsider → invited member → admin → removed
  private val emailA =
    s"it-1.2-a-${UUID.randomUUID().toString.take(8)}@arcadence.test"
  private val emailB =
    s"it-1.2-b-${UUID.randomUUID().toString.take(8)}@arcadence.test"

  private val tokens = Map(
    "token-A" -> AuthenticatedUser(userA, emailA),
    "token-B" -> AuthenticatedUser(userB, emailB)
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private val repos = new WorkspaceRepos(xa)
  private val app = new WorkspaceRoutes(
    authenticator = fakeAuth,
    repos = repos,
    authorizer = new Authorizer(xa),
    appOrigin = "http://localhost:3000"
  ).http.orNotFound

  private def seedUser(id: UUID, email: String): IO[Unit] =
    (sql"""
      insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
      values ('00000000-0000-0000-0000-000000000000', $id, 'authenticated', 'authenticated',
              $email, '', now(), now(), now())
    """.update.run *>
      sql"insert into public.profile (id, display_name) values ($id, ${email.take(8)}) on conflict (id) do nothing".update.run).void
      .transact(xa)

  private def request(
      method: Method,
      path: String,
      token: String,
      body: Option[Json] = None
  ): IO[(Status, Json)] =
    val base = Request[IO](method, Uri.unsafeFromString(path))
      .withHeaders(
        Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $token")
      )
    val req = body
      .fold(base)(json =>
        base
          .withEntity(json.noSpaces)
          .withContentType(
            org.http4s.headers
              .`Content-Type`(org.http4s.MediaType.application.json)
          )
      )
    app
      .run(req)
      .flatMap(res =>
        res.body.through(fs2.text.utf8.decode).compile.string.map { text =>
          (res.status, parse(text).toOption.getOrElse(Json.Null))
        }
      )

  private def jsonField(body: Json, field: String): String =
    body.hcursor.get[String](field).toOption.getOrElse("")

  private var workspaceId: UUID = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  override def beforeAll(): Unit =
    (seedUser(userA, emailA) *> seedUser(userB, emailB)).unsafeRunSync()
    super.beforeAll()

  override def afterAll(): Unit =
    // Cascade-friendly teardown: remove memberships/invitations/workspace, then users.
    (for
      _ <-
        sql"delete from public.workspace where organization_id in (select id from public.organization where name like '%(organization)' and id in (select organization_id from public.workspace w join public.workspace_member m on m.workspace_id = w.id where m.user_id = $userA))".update.run
      _ <-
        sql"delete from public.organization where name like '%(organization)' and id not in (select organization_id from public.workspace where deleted_at is null)".update.run
      _ <- sql"delete from auth.users where id in ($userA, $userB)".update.run
    yield ()).transact(xa).unsafeRunSync()
    super.afterAll()

  test("owner creates a workspace and becomes its owner member") {
    val (status, body) = request(
      Method.POST,
      "/api/v1/workspaces",
      "token-A",
      Some(Json.obj("name" -> Json.fromString("Integration WS")))
    ).unsafeRunSync()
    assertEquals(status, Status.Created)
    assertEquals(jsonField(body, "role"), "owner")
    workspaceId = UUID.fromString(jsonField(body, "id"))
  }

  test(
    "a non-member receives 404 (never 403) for every verb on someone else's workspace"
  ) {
    val gets = List(
      s"/api/v1/workspaces/$workspaceId",
      s"/api/v1/workspaces/$workspaceId/members",
      s"/api/v1/workspaces/$workspaceId/invitations"
    )
    gets.foreach { path =>
      val (status, body) = request(Method.GET, path, "token-B").unsafeRunSync()
      assertEquals(
        status,
        Status.NotFound,
        s"GET $path must be 404 for non-members"
      )
      assertEquals(jsonField(body, "code"), "workspace_not_found", s"GET $path")
    }
    val (patchStatus, _) = request(
      Method.PATCH,
      s"/api/v1/workspaces/$workspaceId",
      "token-B",
      Some(Json.obj("name" -> Json.fromString("Hijacked")))
    ).unsafeRunSync()
    assertEquals(
      patchStatus,
      Status.NotFound,
      "PATCH must be 404 for non-members"
    )

    val (inviteStatus, _) = request(
      Method.POST,
      s"/api/v1/workspaces/$workspaceId/invitations",
      "token-B",
      Some(
        Json.obj(
          "email" -> Json.fromString("outsider@example.com"),
          "role" -> Json.fromString("admin")
        )
      )
    ).unsafeRunSync()
    assertEquals(
      inviteStatus,
      Status.NotFound,
      "self-invitation into a foreign workspace must fail as 404"
    )

    val (memberStatus, _) = request(
      Method.PATCH,
      s"/api/v1/workspaces/$workspaceId/members/$userA",
      "token-B",
      Some(Json.obj("role" -> Json.fromString("viewer")))
    ).unsafeRunSync()
    assertEquals(
      memberStatus,
      Status.NotFound,
      "tampering with foreign members must fail as 404"
    )
  }

  test("an outsider's workspace list does not leak foreign workspaces") {
    val (status, body) =
      request(Method.GET, "/api/v1/workspaces", "token-B").unsafeRunSync()
    assertEquals(status, Status.Ok)
    val ids = body.asArray.map(
      _.map(_.hcursor.get[String]("id").toOption.getOrElse(""))
    )
    assert(
      !ids.exists(_.contains(workspaceId.toString)),
      "foreign workspace must not be listed"
    )
  }

  test(
    "invitation flow: invite → accept → member visible; member lacks member management"
  ) {
    val (created, inviteBody) = request(
      Method.POST,
      s"/api/v1/workspaces/$workspaceId/invitations",
      "token-A",
      Some(
        Json.obj(
          "email" -> Json.fromString(emailB),
          "role" -> Json.fromString("member")
        )
      )
    ).unsafeRunSync()
    assertEquals(created, Status.Created)
    val inviteUrl = jsonField(inviteBody, "inviteUrl")
    val token =
      UUID.fromString(inviteUrl.substring(inviteUrl.lastIndexOf('/') + 1))

    // Duplicate open invitation for the same email conflicts.
    val (dupStatus, _) = request(
      Method.POST,
      s"/api/v1/workspaces/$workspaceId/invitations",
      "token-A",
      Some(
        Json.obj(
          "email" -> Json.fromString(emailB),
          "role" -> Json.fromString("viewer")
        )
      )
    ).unsafeRunSync()
    assertEquals(dupStatus, Status.Conflict)

    // B accepts with their own token.
    val (accepted, acceptBody) =
      request(Method.POST, s"/api/v1/invitations/$token/accept", "token-B")
        .unsafeRunSync()
    assertEquals(accepted, Status.Ok)
    assertEquals(jsonField(acceptBody, "role"), "member")

    // B is now a member: can read, cannot manage members or workspace settings.
    val (memberGet, _) =
      request(Method.GET, s"/api/v1/workspaces/$workspaceId", "token-B")
        .unsafeRunSync()
    assertEquals(memberGet, Status.Ok)

    val (memberPatch, patchBody) = request(
      Method.PATCH,
      s"/api/v1/workspaces/$workspaceId",
      "token-B",
      Some(Json.obj("name" -> Json.fromString("Not Allowed")))
    ).unsafeRunSync()
    assertEquals(memberPatch, Status.Forbidden)
    assertEquals(jsonField(patchBody, "code"), "insufficient_role")

    val (memberRoleChange, _) = request(
      Method.PATCH,
      s"/api/v1/workspaces/$workspaceId/members/$userA",
      "token-B",
      Some(Json.obj("role" -> Json.fromString("viewer")))
    ).unsafeRunSync()
    assertEquals(memberRoleChange, Status.Forbidden)

    val (memberInvite, _) = request(
      Method.POST,
      s"/api/v1/workspaces/$workspaceId/invitations",
      "token-B",
      Some(
        Json.obj(
          "email" -> Json.fromString("x@example.com"),
          "role" -> Json.fromString("member")
        )
      )
    ).unsafeRunSync()
    assertEquals(memberInvite, Status.Forbidden)
  }

  test("owner can change roles; the owner role itself is immutable") {
    val (promoted, promotedBody) = request(
      Method.PATCH,
      s"/api/v1/workspaces/$workspaceId/members/$userB",
      "token-A",
      Some(Json.obj("role" -> Json.fromString("campaign_manager")))
    ).unsafeRunSync()
    assertEquals(promoted, Status.Ok)
    assertEquals(jsonField(promotedBody, "role"), "campaign_manager")

    val (ownerChange, ownerChangeBody) = request(
      Method.PATCH,
      s"/api/v1/workspaces/$workspaceId/members/$userA",
      "token-B",
      Some(Json.obj("role" -> Json.fromString("viewer")))
    ).unsafeRunSync()
    // B is now campaign_manager: lacks ManageMembers → 403.
    assertEquals(ownerChange, Status.Forbidden)
    assertEquals(jsonField(ownerChangeBody, "code"), "insufficient_role")

    // Promote B to admin, then attempt to demote the owner (admin has
    // ManageMembers but the owner row is immutable → not found via guard).
    val (adminPromotion, _) = request(
      Method.PATCH,
      s"/api/v1/workspaces/$workspaceId/members/$userB",
      "token-A",
      Some(Json.obj("role" -> Json.fromString("admin")))
    ).unsafeRunSync()
    assertEquals(adminPromotion, Status.Ok)

    val (ownerDemote, _) = request(
      Method.PATCH,
      s"/api/v1/workspaces/$workspaceId/members/$userA",
      "token-B",
      Some(Json.obj("role" -> Json.fromString("viewer")))
    ).unsafeRunSync()
    assertEquals(ownerDemote, Status.NotFound, "owner membership is immutable")

    val (ownerRemove, _) = request(
      Method.DELETE,
      s"/api/v1/workspaces/$workspaceId/members/$userA",
      "token-B"
    ).unsafeRunSync()
    assertEquals(ownerRemove, Status.NotFound, "owner cannot be removed")
  }

  test("removal revokes access immediately") {
    val (removed, _) = request(
      Method.DELETE,
      s"/api/v1/workspaces/$workspaceId/members/$userB",
      "token-A"
    ).unsafeRunSync()
    assertEquals(removed, Status.NoContent)

    val (afterRemoval, afterBody) =
      request(Method.GET, s"/api/v1/workspaces/$workspaceId", "token-B")
        .unsafeRunSync()
    assertEquals(afterRemoval, Status.NotFound)
    assertEquals(jsonField(afterBody, "code"), "workspace_not_found")
  }

  test("requests without a bearer token are unauthorized") {
    val base = Request[IO](
      Method.GET,
      Uri.unsafeFromString(s"/api/v1/workspaces/$workspaceId")
    )
    val (status, _) = app
      .run(base)
      .flatMap(res =>
        res.body
          .through(fs2.text.utf8.decode)
          .compile
          .string
          .map(text => (res.status, text))
      )
      .unsafeRunSync()
    assertEquals(status, Status.Unauthorized)
  }
