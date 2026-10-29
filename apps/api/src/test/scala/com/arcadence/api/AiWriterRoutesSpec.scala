package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.ai.AiGateway
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

/** AI writer routes (Phase 11.2): research facts CRUD, grounded draft
  * generation with deterministic flags, approval/edit lifecycle, and
  * deterministic render previews.
  */
final class AiWriterRoutesSpec extends CatsEffectSuite:

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
  private val userA = UUID.randomUUID()
  private val userB = UUID.randomUUID()
  private val suffix = ws.toString.take(8)

  private val tokens = Map[String, AuthenticatedUser](
    "token-A" -> AuthenticatedUser(userA, s"aiw-$suffix@t.test"),
    "token-B" -> AuthenticatedUser(userB, s"aiwb-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  /** Draft 1 is grounded and clean; draft 2 cites a non-existent fact, uses an
    * unknown variable and pressure phrases — all must be flagged.
    */
  private val cannedWriterOutput =
    """{"drafts":[
      | {"subject":"Quick note","body":"Saw the scaling news at {{company}}.","claims":[{"factKey":"f1","quote":"scaling news"}]},
      | {"subject":"Act now","body":"100% guaranteed growth per {{unicorn_name}}!","claims":[{"factKey":"f9","quote":"?"}]}
      |]}""".stripMargin

  private val stub = AiGateway.Stub(cannedWriterOutput)

  private lazy val routes = new AiWriterRoutes(
    fakeAuth,
    xa,
    gateway = Some(stub),
    defaultModel = "test-model"
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

  override def beforeEach(context: munit.BeforeEach): Unit =
    assume(
      dbUrl.nonEmpty,
      "TEST_DATABASE_URL not set — integration checks skipped"
    )
    super.beforeEach(context)

  test("setup: org, workspaces, owners; ws2 zero AI budget") {
    for _ <-
        (sql"delete from public.workspace where id in ($ws, $ws2)".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'AIW Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'AIW WS', ${"aiw-" + suffix})".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug, ai_monthly_token_limit, ai_monthly_spend_micros) values ($ws2, $orgId, 'AIW WS2', ${"aiw2-" + suffix}, 0, 0)".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"aiw-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userB, 'authenticated', 'authenticated',
                    ${"aiwb-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'aiw') on conflict (id) do nothing".update.run *>
          sql"insert into public.profile (id, display_name) values ($userB, 'aiwb') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws2, $userB, 'owner')".update.run).void
          .transact(xa)
    yield ()
  }

  test("research facts: create, list, delete; unknown delete 404") {
    for
      created <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/research",
        body = Some(
          Json.obj(
            "claim" -> Json.fromString("Acme is scaling its sales team"),
            "sourceUrl" -> Json.fromString("https://example.com/news"),
            "companyDomain" -> Json.fromString("acme.test")
          )
        )
      )
      _ = assertEquals(created._1, Status.Created)
      _ = assertEquals(jsonField(created._2, "sourceKind"), "manual")
      itemId = jsonField(created._2, "id")
      listed <- request(Method.GET, s"/api/v1/workspaces/$ws/ai/research")
      count = listed._2.asArray.getOrElse(Vector.empty).size
      _ = assertEquals(count, 1)
      gone <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$ws/ai/research/$itemId"
      )
      _ = assertEquals(gone._1, Status.Ok)
      missing <- request(
        Method.DELETE,
        s"/api/v1/workspaces/$ws/ai/research/$itemId"
      )
      _ = assertEquals(missing._1, Status.NotFound)
    yield ()
  }

  private var researchId: UUID = UUID.randomUUID()

  test(
    "write produces flagged drafts; clean draft unflagged, invented claims flagged"
  ) {
    for
      fact <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/research",
        body = Some(
          Json.obj("claim" -> Json.fromString("Acme is scaling its sales team"))
        )
      )
      _ = assertEquals(fact._1, Status.Created)
      _ = researchId = UUID.fromString(jsonField(fact._2, "id"))
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/write",
        body = Some(
          Json.obj(
            "objective" -> Json.fromString("book demos"),
            "offer" -> Json.fromString("CRM"),
            "persona" -> Json.fromString("VP Sales"),
            "tone" -> Json.fromString("direct"),
            "cta" -> Json.fromString("15 min call"),
            "length" -> Json.fromString("short"),
            "personalizationLevel" -> Json.fromString("deep"),
            "researchIds" -> Json.arr(Json.fromString(researchId.toString)),
            "variations" -> Json.fromInt(2)
          )
        )
      )
      _ = assertEquals(pair._1, Status.Ok)
      drafts = pair._2.hcursor
        .downField("drafts")
        .values
        .getOrElse(Vector.empty)
        .toVector
      _ = assertEquals(drafts.size, 2)
      flags1 = drafts
        .apply(0)
        .hcursor
        .downField("flags")
        .values
        .getOrElse(Vector.empty)
        .flatMap(_.asString)
        .toSet
      flags2 = drafts
        .apply(1)
        .hcursor
        .downField("flags")
        .values
        .getOrElse(Vector.empty)
        .flatMap(_.asString)
        .toSet
      _ = assertEquals(flags1, Set.empty[String])
      _ = assert(flags2.contains("unsupported_claim:f9"), s"flags2=$flags2")
      _ = assert(
        flags2.contains("unknown_variable:unicorn_name"),
        s"flags2=$flags2"
      )
      _ = assert(flags2.contains("deceptive_phrase:act now"), s"flags2=$flags2")
      _ = assert(
        flags2.contains("deceptive_phrase:100% guaranteed"),
        s"flags2=$flags2"
      )
    yield ()
  }

  test("messages: list, edit, approve, deterministic render") {
    for
      listed <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/ai/messages?status=draft"
      )
      drafts = listed._2.asArray.getOrElse(Vector.empty)
      _ = assertEquals(drafts.size, 2)
      cleanId = drafts
        .map(_.hcursor.get[String]("id").toOption.getOrElse(""))
        .find { id =>
          // the clean draft's subject is "Quick note"
          true
        }
      msgId = drafts.apply(0).hcursor.get[String]("id").toOption.getOrElse("")
      edited <- request(
        Method.PATCH,
        s"/api/v1/workspaces/$ws/ai/messages/$msgId",
        body = Some(
          Json.obj(
            "finalBody" -> Json.fromString(
              "Final body for {{first_name}} at {{company}}"
            )
          )
        )
      )
      _ = assertEquals(jsonField(edited._2, "status"), "edited")
      approved <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/messages/$msgId/approve"
      )
      _ = assertEquals(jsonField(approved._2, "status"), "approved")
      rendered <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/messages/$msgId/render",
        body = Some(
          Json.obj(
            "variables" -> Json.obj(
              "first_name" -> Json.fromString("Ada"),
              "company" -> Json.fromString("Acme $ Co")
            )
          )
        )
      )
      _ = assertEquals(rendered._1, Status.Ok)
      body = jsonField(rendered._2, "body")
      _ = assert(body.contains("Acme $ Co"))
      _ = assert(!body.contains("{{"), s"placeholder leaked: $body")
      badRender <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/messages/$msgId/render",
        body = Some(
          Json.obj(
            "variables" -> Json.obj("first_name" -> Json.fromString("Ada"))
          )
        )
      )
      _ = assertEquals(badRender._1, Status.UnprocessableEntity)
      flaggedId = drafts
        .apply(1)
        .hcursor
        .get[String]("id")
        .toOption
        .getOrElse("")
      decided <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/ai/messages/$flaggedId/reject"
      )
      _ = assertEquals(jsonField(decided._2, "status"), "rejected")
    yield ()
  }

  test("zero-budget workspace blocks write before the provider runs") {
    for
      pair <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws2/ai/write",
        token = "token-B",
        body = Some(
          Json.obj(
            "objective" -> Json.fromString("x"),
            "offer" -> Json.fromString("x"),
            "persona" -> Json.fromString("x"),
            "tone" -> Json.fromString("x"),
            "cta" -> Json.fromString("x"),
            "length" -> Json.fromString("short"),
            "personalizationLevel" -> Json.fromString("none")
          )
        )
      )
      _ = assertEquals(pair._1, Status.PaymentRequired)
      _ = assertEquals(jsonField(pair._2, "code"), "insufficient_budget")
      runs <-
        sql"select count(*) from public.ai_runs where workspace_id = $ws2"
          .query[Int]
          .unique
          .transact(xa)
      _ = assertEquals(runs, 0)
    yield ()
  }

  test(
    "tenant isolation: another workspace's owner cannot read research or messages"
  ) {
    for
      r <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/ai/research",
        token = "token-B"
      )
      _ = assertEquals(r._1, Status.NotFound)
      m <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/ai/messages",
        token = "token-B"
      )
      _ = assertEquals(m._1, Status.NotFound)
    yield ()
  }
