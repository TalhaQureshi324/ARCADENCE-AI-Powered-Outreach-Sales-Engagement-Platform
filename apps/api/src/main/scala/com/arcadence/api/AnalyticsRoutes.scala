package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.time.LocalDate
import java.util.UUID

/** Advanced analytics (Phase 7.2/7.3): summary KPIs, daily time-series,
  * per-campaign and per-mailbox comparisons, CSV export and anomaly warnings.
  * All values derive from campaign_daily_stats / campaign_event.
  */
final class AnalyticsRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO]
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def requireView(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    authorization match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(e) =>
            IO.pure(
              Left((SttpStatus.Unauthorized, Envelope(e.code, e.message)))
            )
          case Right(user) =>
            authorizer
              .requirePermission(user, workspaceId, Permission.ViewWorkspace)
              .map {
                case Left(error) => Left(failed(error))
                case Right(_)    => Right(())
              }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private def parseDate(s: String, fallback: LocalDate): LocalDate =
    scala.util.Try(LocalDate.parse(s)).getOrElse(fallback)

  // -- endpoints ------------------------------------------------------------------

  private val summary = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "analytics" / "summary"
    )
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("from"))
    .in(query[Option[String]]("to"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val timeseries = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "analytics" / "timeseries"
    )
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("from"))
    .in(query[Option[String]]("to"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val campaignTable = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "analytics" / "campaigns"
    )
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("from"))
    .in(query[Option[String]]("to"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val mailboxPerf = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "analytics" / "mailboxes"
    )
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("from"))
    .in(query[Option[String]]("to"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val csvExport = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "analytics" / "export.csv"
    )
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("from"))
    .in(query[Option[String]]("to"))
    .out(byteArrayBody)
    .errorOut(errorOut)

  private val anomalies = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "analytics" / "anomalies"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  // -- logic -----------------------------------------------------------------------

  private def range(
      from: Option[String],
      to: Option[String]
  ): (LocalDate, LocalDate) =
    val today = LocalDate.now()
    (
      parseDate(
        from.getOrElse(today.minusDays(30).toString),
        today.minusDays(30)
      ),
      parseDate(to.getOrElse(today.toString), today)
    )

  private def summaryLogic(
      auth: Option[String],
      ws: UUID,
      from: Option[String],
      to: Option[String]
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    requireView(auth, ws).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        val (f, t) = range(from, to)
        sql"""
          select coalesce(sum(send_scheduled),0), coalesce(sum(accepted),0),
                 coalesce(sum(provider_rejected),0), coalesce(sum(bounce_hard),0),
                 coalesce(sum(bounce_soft),0), coalesce(sum(replies),0),
                 coalesce(sum(reply_classified),0), coalesce(sum(meeting_booked),0),
                 coalesce(sum(unsubscribe),0), coalesce(sum(unique_leads_contacted),0)
          from public.campaign_daily_stats where workspace_id = $ws and day between $f and $t
        """
          .query[(Int, Int, Int, Int, Int, Int, Int, Int, Int, Int)]
          .unique
          .transact(xa)
          .map { case (sched, acc, rej, bh, bs, rep, pos, mtg, unsub, leads) =>
            val positive = pos
            Right(
              Json.obj(
                "emailsSubmitted" -> Json.fromInt(sched),
                "uniqueLeadsContacted" -> Json.fromInt(leads),
                "accepted" -> Json.fromInt(acc),
                "providerRejected" -> Json.fromInt(rej),
                "bounceHard" -> Json.fromInt(bh),
                "bounceSoft" -> Json.fromInt(bs),
                "replies" -> Json.fromInt(rep),
                "positiveReplies" -> Json.fromInt(positive),
                "unsubscribes" -> Json.fromInt(unsub),
                "meetingsBooked" -> Json.fromInt(mtg),
                "from" -> Json.fromString(f.toString),
                "to" -> Json.fromString(t.toString)
              )
            )
          }
    }

  private def timeseriesLogic(
      auth: Option[String],
      ws: UUID,
      from: Option[String],
      to: Option[String]
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    requireView(auth, ws).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        val (f, t) = range(from, to)
        sql"""
          select day, send_scheduled, accepted, bounce_hard, bounce_soft, replies, meeting_booked
          from public.campaign_daily_stats
          where workspace_id = $ws and day between $f and $t order by day
        """
          .query[(LocalDate, Int, Int, Int, Int, Int, Int)]
          .to[List]
          .transact(xa)
          .map(rows =>
            Right(
              Json.arr(
                rows.map(r =>
                  Json.obj(
                    "day" -> Json.fromString(r._1.toString),
                    "sends" -> Json.fromInt(r._2),
                    "accepted" -> Json.fromInt(r._3),
                    "bounceHard" -> Json.fromInt(r._4),
                    "bounceSoft" -> Json.fromInt(r._5),
                    "replies" -> Json.fromInt(r._6),
                    "meetings" -> Json.fromInt(r._7)
                  )
                )*
              )
            )
          )
    }

  private def campaignTableLogic(
      auth: Option[String],
      ws: UUID,
      from: Option[String],
      to: Option[String]
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    requireView(auth, ws).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        val (f, t) = range(from, to)
        sql"""
          select s.campaign_id, coalesce(c.name, '(deleted)'),
            sum(s.send_scheduled), sum(s.accepted), sum(s.bounce_hard), sum(s.bounce_soft),
            sum(s.replies), sum(s.reply_classified), sum(s.meeting_booked)
          from public.campaign_daily_stats s
          left join public.campaign c on c.id = s.campaign_id
          where s.workspace_id = $ws and s.day between $f and $t
          group by s.campaign_id, c.name order by sum(s.send_scheduled) desc
        """
          .query[(UUID, String, Int, Int, Int, Int, Int, Int, Int)]
          .to[List]
          .transact(xa)
          .map(rows =>
            Right(Json.fromValues(rows.map { r =>
              Json.obj(
                "campaignId" -> Json.fromString(r._1.toString),
                "name" -> Json.fromString(r._2),
                "sends" -> Json.fromInt(r._3),
                "accepted" -> Json.fromInt(r._4),
                "bounceHard" -> Json.fromInt(r._5),
                "bounceSoft" -> Json.fromInt(r._6),
                "replies" -> Json.fromInt(r._7),
                "positiveReplies" -> Json.fromInt(r._8),
                "meetings" -> Json.fromInt(r._9)
              )
            }))
          )
    }

  private def mailboxLogic(
      auth: Option[String],
      ws: UUID,
      from: Option[String],
      to: Option[String]
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    requireView(auth, ws).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        val (f, t) = range(from, to)
        sql"""
          select coalesce(ea.email_address, sa.email_account_id::text),
            count(*) filter (where ce.event_type = 'send_scheduled'),
            count(*) filter (where ce.event_type = 'accepted'),
            count(*) filter (where ce.event_type = 'bounce_hard'),
            count(*) filter (where ce.event_type = 'bounce_soft')
          from public.campaign_event ce
          left join public.email_accounts ea on ea.id = ce.email_account_id
          left join public.scheduled_action sa on sa.id = ce.enrollment_id
          where ce.workspace_id = $ws and ce.email_account_id is not null
            and ce.occurred_at::date between $f and $t
          group by 1 order by 2 desc
        """
          .query[(String, Int, Int, Int, Int)]
          .to[List]
          .transact(xa)
          .map(rows =>
            Right(Json.fromValues(rows.map { r =>
              Json.obj(
                "mailbox" -> Json.fromString(r._1),
                "sends" -> Json.fromInt(r._2),
                "accepted" -> Json.fromInt(r._3),
                "bounceHard" -> Json.fromInt(r._4),
                "bounceSoft" -> Json.fromInt(r._5)
              )
            }))
          )
    }

  private def csvLogic(
      auth: Option[String],
      ws: UUID,
      from: Option[String],
      to: Option[String]
  ): IO[Either[(SttpStatus, Envelope), Array[Byte]]] =
    requireView(auth, ws).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        val (f, t) = range(from, to)
        campaignTableLogic(auth, ws, from, to).map {
          case Left(env) => Left(env)
          case Right(js) =>
            val rows =
              js.asArray.getOrElse(Vector.empty).flatMap(_.asObject.toVector)
            val header =
              "campaign_id,name,sends,accepted,bounce_hard,bounce_soft,replies,positive_replies,meetings"
            val body = rows
              .map { o =>
                List(
                  "campaignId",
                  "name",
                  "sends",
                  "accepted",
                  "bounceHard",
                  "bounceSoft",
                  "replies",
                  "positiveReplies",
                  "meetings"
                )
                  .map(k =>
                    o(k)
                      .map(v => v.asString.getOrElse(v.noSpaces))
                      .getOrElse("")
                  )
                  .mkString(",")
              }
              .mkString("\n")
            Right((header + "\n" + body).getBytes("UTF-8"))
        }
    }

  private def anomaliesLogic(
      auth: Option[String],
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    requireView(auth, ws).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        sql"""
          select day, send_scheduled, accepted, bounce_hard, replies
          from public.campaign_daily_stats where workspace_id = $ws order by day
        """
          .query[(LocalDate, Int, Int, Int, Int)]
          .to[List]
          .transact(xa)
          .map { rows =>
            val warnings = List.newBuilder[Json]
            val n = rows.size
            if n >= 4 then
              val half = n / 2
              val (prev, curr) = rows.splitAt(half)
              def sumOf(
                  xs: List[(LocalDate, Int, Int, Int, Int)],
                  i: Int
              ): Int =
                xs.map { r =>
                  i match
                    case 1 => r._2
                    case 2 => r._3
                    case 3 => r._4
                    case 5 => r._5
                    case _ => 0
                }.sum
              def pct(currV: Int, prevV: Int): Option[Double] =
                if prevV == 0 then None
                else Some((currV - prevV) * 100.0 / prevV)
              List(
                (
                  "bounce rate",
                  3.0 / math.max(1, sumOf(curr, 2)),
                  3.0 / math.max(1, sumOf(prev, 2))
                ),
                (
                  "reply rate",
                  sumOf(curr, 5).toDouble / math.max(1, sumOf(curr, 2)),
                  sumOf(prev, 5).toDouble / math.max(1, sumOf(prev, 2))
                )
              ).foreach { case (label, cur, pre) =>
                if pre > 0 then
                  val delta = (cur - pre) / pre
                  if math.abs(delta) >= 0.5 then
                    warnings += Json.obj(
                      "type" -> Json.fromString(s"$label shift"),
                      "change" -> Json.fromDoubleOrNull(delta * 100),
                      "detail" -> Json.fromString(
                        f"$label moved ${delta * 100}%.0f%% vs the first half of the window"
                      )
                    )
              }
              val failedSends = sumOf(prev, 1) - sumOf(prev, 2)
              if sumOf(prev, 1) > 0 && failedSends.toDouble / sumOf(
                  prev,
                  1
                ) >= 0.2
              then
                warnings += Json.obj(
                  "type" -> Json.fromString("failed sends"),
                  "detail" -> Json.fromString(
                    s"${failedSends} of ${sumOf(prev, 1)} sends were not accepted by the provider"
                  )
                )
            Right(Json.obj("warnings" -> warnings.result().asJson))
          }
    }

  // -- assembly --------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      summary.serverLogic((ws, auth, f, t) => summaryLogic(auth, ws, f, t)),
      timeseries.serverLogic((ws, auth, f, t) =>
        timeseriesLogic(auth, ws, f, t)
      ),
      campaignTable.serverLogic((ws, auth, f, t) =>
        campaignTableLogic(auth, ws, f, t)
      ),
      mailboxPerf.serverLogic((ws, auth, f, t) => mailboxLogic(auth, ws, f, t)),
      csvExport.serverLogic((ws, auth, f, t) => csvLogic(auth, ws, f, t)),
      anomalies.serverLogic((ws, auth) => anomaliesLogic(auth, ws))
    )
  )
