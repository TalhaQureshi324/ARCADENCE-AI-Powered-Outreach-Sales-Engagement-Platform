package com.arcadence.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.arcadence.core.channels.Channels
import com.arcadence.core.channels.Channels.SmsAdapter
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
import java.util.concurrent.atomic.AtomicInteger

/** Multichannel (Phase 12.5): separate SMS/call consent from email, consent
  * checked before any SMS send, provider-optional, CRM interaction recording
  * for phone/meeting activity.
  */
final class ChannelRoutesSpec extends CatsEffectSuite:

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
    "token-A" -> AuthenticatedUser(userA, s"ch-$suffix@t.test")
  )
  private val fakeAuth = new Authenticator:
    def verify(token: String): IO[Either[AuthError, AuthenticatedUser]] =
      IO.pure(tokens.get(token).toRight(AuthError.Invalid))

  private val smsAttempts = new AtomicInteger(0)
  private val smsAdapter = new Channels.SmsAdapter:
    def send(
        fromNumber: String,
        toNumber: String,
        text: String
    ): IO[Channels.ChannelResult] =
      IO { smsAttempts.incrementAndGet() }.flatMap { _ =>
        if toNumber.contains("blocked") then
          IO.pure(Channels.Blocked("number blocked by provider"))
        else IO.pure(Channels.Sent("sm-123"))
      }

  private lazy val routes =
    new ChannelRoutes(fakeAuth, xa, smsAdapter = Some(smsAdapter))
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

  private var contactId: UUID = UUID.randomUUID()
  private var phoneContactId: UUID = UUID.randomUUID()

  test("setup: org, ws, owner, contacts (one with phone, one without)") {
    for
      _ <-
        (sql"delete from public.workspace where id = $ws".update.run *>
          sql"insert into public.organization (id, name) values ($orgId, 'CH Org')".update.run *>
          sql"insert into public.workspace (id, organization_id, name, slug) values ($ws, $orgId, 'CH WS', ${"ch-" + suffix})".update.run *>
          sql"""
            insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
            values ('00000000-0000-0000-0000-000000000000', $userA, 'authenticated', 'authenticated',
                    ${"ch-" + suffix + "@t.test"}, '', now(), now(), now())
          """.update.run *>
          sql"insert into public.profile (id, display_name) values ($userA, 'ch') on conflict (id) do nothing".update.run *>
          sql"insert into public.workspace_member (workspace_id, user_id, role) values ($ws, $userA, 'owner')".update.run *>
          sql"""insert into public.contact (workspace_id, email, phone)
                values ($ws, ${"ph-" + suffix + "@t.test"}, '+15550001111') returning id"""
            .query[UUID]
            .unique *>
          sql"""insert into public.contact (workspace_id, email)
                values ($ws, ${"nophone-" + suffix + "@t.test"}) returning id"""
            .query[UUID]
            .unique).void
          .transact(xa)
      ids <-
        (
          sql"select id from public.contact where workspace_id = $ws and email = ${"ph-" + suffix + "@t.test"}"
            .query[UUID]
            .unique,
          sql"select id from public.contact where workspace_id = $ws and email = ${"nophone-" + suffix + "@t.test"}"
            .query[UUID]
            .unique
        ).tupled.transact(xa)
      _ = phoneContactId = ids._1
      _ = contactId = ids._2
    yield ()
  }

  test(
    "SMS without consent is blocked; consent is per-channel and independent of email"
  ) {
    for
      blocked <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/channels/sms",
        body = Some(
          Json.obj(
            "contactId" -> Json.fromString(phoneContactId.toString),
            "fromNumber" -> Json.fromString("+15550000000"),
            "text" -> Json.fromString("hi")
          )
        )
      )
      _ = assertEquals(blocked._1, Status.Forbidden)
      _ = assertEquals(jsonField(blocked._2, "code"), "sms_consent_missing")
      _ = assertEquals(smsAttempts.get, 0)
      // opt in ONLY sms; call consent stays unknown
      optIn <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/contacts/$phoneContactId/consent/sms",
        body = Some(Json.obj("consent" -> Json.fromString("opted_in")))
      )
      _ = assertEquals(jsonField(optIn._2, "smsConsent"), "opted_in")
      _ = assertEquals(jsonField(optIn._2, "callConsent"), "unknown")
      sent <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/channels/sms",
        body = Some(
          Json.obj(
            "contactId" -> Json.fromString(phoneContactId.toString),
            "fromNumber" -> Json.fromString("+15550000000"),
            "text" -> Json.fromString("hi")
          )
        )
      )
      _ = assertEquals(sent._1, Status.Ok)
      _ = assertEquals(jsonField(sent._2, "providerMessageId"), "sm-123")
      // contact without phone fails honestly
      noPhone <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/channels/sms",
        body = Some(
          Json.obj(
            "contactId" -> Json.fromString(contactId.toString),
            "fromNumber" -> Json.fromString("+15550000000"),
            "text" -> Json.fromString("hi")
          )
        )
      )
      _ = assertEquals(noPhone._1, Status.UnprocessableEntity)
    yield ()
  }

  test("call consent gate and interaction recording") {
    for
      logBlocked <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/channel-interactions",
        body = Some(
          Json.obj(
            "contactId" -> Json.fromString(phoneContactId.toString),
            "channel" -> Json.fromString("call"),
            "direction" -> Json.fromString("outbound"),
            "summary" -> Json.fromString("Discovery call - went well")
          )
        )
      )
      _ = assertEquals(logBlocked._1, Status.Created)
      optOut <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/contacts/$phoneContactId/consent/call",
        body = Some(Json.obj("consent" -> Json.fromString("opted_out")))
      )
      _ = assertEquals(jsonField(optOut._2, "callConsent"), "opted_out")
      interactions <- request(
        Method.GET,
        s"/api/v1/workspaces/$ws/contacts/$phoneContactId/interactions"
      )
      count = interactions._2.asArray.getOrElse(Vector.empty).size
      _ = assertEquals(count, 1)
      _ = assertEquals(
        jsonField(interactions._2.asArray.get.head, "channel"),
        "call"
      )
      bad <- request(
        Method.POST,
        s"/api/v1/workspaces/$ws/channel-interactions",
        body = Some(
          Json.obj(
            "contactId" -> Json.fromString(phoneContactId.toString),
            "channel" -> Json.fromString("fax"),
            "direction" -> Json.fromString("outbound"),
            "summary" -> Json.fromString("x")
          )
        )
      )
      _ = assertEquals(bad._1, Status.UnprocessableEntity)
    yield ()
  }

  test("no SMS provider configured fails honestly with 503") {
    val noAdapterRoutes =
      new ChannelRoutes(fakeAuth, xa, smsAdapter = None).http.orNotFound
    val req = Request[IO](
      Method.POST,
      Uri.unsafeFromString(s"/api/v1/workspaces/$ws/channels/sms")
    )
      .withHeaders(
        Header.Raw(org.typelevel.ci.CIString("Authorization"), "Bearer token-A")
      )
      .withEntity(
        Json
          .obj(
            "contactId" -> Json.fromString(phoneContactId.toString),
            "fromNumber" -> Json.fromString("+1"),
            "text" -> Json.fromString("x")
          )
          .noSpaces
      )(org.http4s.EntityEncoder.stringEncoder[IO])
      .withContentType(
        org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)
      )
    for
      res <- noAdapterRoutes
        .run(req)
        .flatMap(r =>
          r.bodyText.compile.string
            .map(t => (r.status, parse(t).toOption.getOrElse(Json.Null)))
        )
      _ = assertEquals(res._1, Status.ServiceUnavailable)
      _ = assertEquals(jsonField(res._2, "code"), "sms_not_configured")
    yield ()
  }
