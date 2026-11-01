package com.arcadence.core.integrations

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.time.Duration

/** External connectors (Phase 12.2). Each connector performs one narrow,
  * provider-documented call. External records are never blindly overwritten:
  * CRM contact pushes only create, and calendar events are new events — updates
  * would require an explicit external id the caller supplies.
  *
  * Base URLs are constructor-injected so tests can point connectors at a local
  * stub server.
  */
object Integrations:

  val kinds: Set[String] =
    Set(
      "slack",
      "google_calendar",
      "outlook_calendar",
      "hubspot",
      "webhook_out"
    )

  final case class ExternalRef(provider: String, externalId: String)

  private def postJson(
      client: Client[IO],
      url: String,
      body: Json,
      bearer: Option[String],
      timeout: Duration
  ): IO[Either[String, Json]] =
    val base = Request[IO](Method.POST, Uri.unsafeFromString(url))
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))
    val req = bearer.fold(base)(b =>
      base.putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $b"))
    )
    client
      .run(req)
      .use(res => res.bodyText.compile.string.map(t => (res.status.code, t)))
      .timeoutTo(
        scala.concurrent.duration.Duration.fromNanos(timeout.toNanos),
        IO.pure((-1, "timeout"))
      )
      .map {
        case (code, text) if code >= 200 && code < 300 =>
          io.circe.parser.parse(text) match
            case Right(json) => Right(json)
            case Left(e)     => Left(s"unparseable response: ${e.getMessage}")
        case (code, text) =>
          Left(s"HTTP $code: ${text.take(200)}")
      }

  /** Slack incoming-webhook message. */
  final class Slack(
      client: Client[IO],
      requestTimeout: Duration = Duration.ofSeconds(10)
  ):
    def postMessage(
        webhookUrl: String,
        text: String,
        channel: Option[String] = None
    ): IO[Either[String, Unit]] =
      val fields = scala.collection.mutable.ListBuffer.empty[(String, Json)]
      fields += (("text", Json.fromString(text)))
      channel.foreach(c => fields += (("channel", Json.fromString(c))))
      val body =
        Json.fromJsonObject(io.circe.JsonObject.fromIterable(fields.toSeq))
      // Slack answers 200 with a plain-text body - status is all we check.
      val base = Request[IO](Method.POST, Uri.unsafeFromString(webhookUrl))
        .withEntity(body)
        .withContentType(
          org.http4s.headers.`Content-Type`(MediaType.application.json)
        )
      client
        .run(base)
        .use(res => res.status.code.pure[IO])
        .timeoutTo(
          scala.concurrent.duration.Duration.fromNanos(requestTimeout.toNanos),
          IO.pure(-1)
        )
        .map(code =>
          if code >= 200 && code < 300 then Right(())
          else Left(s"HTTP ' + chr(36) + '{code}")
        )

  /** Google Calendar: create an event (never updates existing ones). */
  final class GoogleCalendar(
      client: Client[IO],
      baseUrl: String = "https://www.googleapis.com",
      requestTimeout: Duration = Duration.ofSeconds(15)
  ):
    def createEvent(
        bearer: String,
        summary: String,
        startIso: String,
        endIso: String,
        attendeeEmail: Option[String],
        description: Option[String] = None
    ): IO[Either[String, ExternalRef]] =
      val attendees = attendeeEmail.fold(Json.obj())(a =>
        Json.obj("attendees" -> Json.arr(Json.obj("email" -> a.asJson)))
      )
      val body = Json
        .obj(
          "summary" -> summary.asJson,
          "description" -> description.getOrElse("").asJson,
          "start" -> Json.obj("dateTime" -> startIso.asJson),
          "end" -> Json.obj("dateTime" -> endIso.asJson)
        )
        .deepMerge(attendees)
      postJson(
        client,
        s"$baseUrl/calendar/v3/calendars/primary/events",
        body,
        Some(bearer),
        requestTimeout
      )
        .map(
          _.map(json =>
            ExternalRef(
              "google_calendar",
              json.hcursor.get[String]("id").getOrElse("")
            )
          )
        )

  /** Outlook Calendar via Microsoft Graph: create an event. */
  final class OutlookCalendar(
      client: Client[IO],
      baseUrl: String = "https://graph.microsoft.com",
      requestTimeout: Duration = Duration.ofSeconds(15)
  ):
    def createEvent(
        bearer: String,
        subject: String,
        startIso: String,
        endIso: String,
        attendeeEmail: Option[String],
        description: Option[String] = None
    ): IO[Either[String, ExternalRef]] =
      val attendees = attendeeEmail.fold(Json.arr())(a =>
        Json.arr(
          Json.obj(
            "emailAddress" -> Json.obj("address" -> a.asJson),
            "type" -> "required".asJson
          )
        )
      )
      val body = Json.obj(
        "subject" -> subject.asJson,
        "body" -> Json.obj(
          "contentType" -> "text".asJson,
          "content" -> description.getOrElse("").asJson
        ),
        "start" -> Json
          .obj("dateTime" -> startIso.asJson, "timeZone" -> "UTC".asJson),
        "end" -> Json
          .obj("dateTime" -> endIso.asJson, "timeZone" -> "UTC".asJson),
        "attendees" -> attendees
      )
      postJson(
        client,
        s"$baseUrl/v1.0/me/events",
        body,
        Some(bearer),
        requestTimeout
      )
        .map(
          _.map(json =>
            ExternalRef(
              "outlook_calendar",
              json.hcursor.get[String]("id").getOrElse("")
            )
          )
        )

  /** HubSpot: create a contact (create-only; HubSpot dedupes by email and we
    * surface the resulting external id — no blind overwrites).
    */
  final class HubSpot(
      client: Client[IO],
      baseUrl: String = "https://api.hubapi.com",
      requestTimeout: Duration = Duration.ofSeconds(15)
  ):
    def createContact(
        privateAppToken: String,
        email: String,
        firstName: Option[String],
        lastName: Option[String]
    ): IO[Either[String, ExternalRef]] =
      val fields = scala.collection.mutable.ListBuffer.empty[(String, Json)]
      fields += (("email", Json.fromString(email)))
      firstName.foreach(v => fields += (("firstname", Json.fromString(v))))
      lastName.foreach(v => fields += (("lastname", Json.fromString(v))))
      val props =
        Json.fromJsonObject(io.circe.JsonObject.fromIterable(fields.toSeq))
      postJson(
        client,
        s"$baseUrl/crm/v3/objects/contacts",
        Json.obj("properties" -> props),
        Some(privateAppToken),
        requestTimeout
      )
        .map(
          _.map(json =>
            ExternalRef("hubspot", json.hcursor.get[String]("id").getOrElse(""))
          )
        )

  /** Positive-reply workflow trigger: notify every connected Slack workspace
    * hook. Best-effort — failures are logged, never fatal.
    */
  def notifyPositiveReply(
      slack: Slack,
      hooks: List[String],
      contactEmail: String,
      threadSubject: String
  ): IO[Int] =
    hooks.toList
      .traverse { url =>
        slack
          .postMessage(
            url,
            s":tada: Positive reply from *$contactEmail* on \"$threadSubject\""
          )
          .attempt
          .map(_.isRight)
      }
      .map(_.count(identity))
