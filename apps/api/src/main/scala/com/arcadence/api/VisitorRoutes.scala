package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.util.UUID

/** Website visitor intelligence (Phase 12.3). Tenants register a site, embed
  * the tracking script, and events land anonymously against the site key.
  * Aggregates only — Arcadence does not identify individual visitors from
  * anonymous traffic. Consent: the script only fires after the site sets
  * `window.__arcadenceConsent = true` (documented in the script).
  */
final class VisitorRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO]
) extends Http4sDsl[IO]:

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def requireEdit(
      auth: Option[String],
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), UUID]] =
    auth match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(e) =>
            IO.pure(
              Left((SttpStatus.Unauthorized, Envelope(e.code, e.message)))
            )
          case Right(user) =>
            authorizer.requirePermission(user, ws, Permission.EditContent).map {
              case Left(error) => Left(failed(error))
              case Right(m)    => Right(m.userId)
            }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private def requireView(
      auth: Option[String],
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    auth match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(e) =>
            IO.pure(
              Left((SttpStatus.Unauthorized, Envelope(e.code, e.message)))
            )
          case Right(user) =>
            authorizer
              .requirePermission(user, ws, Permission.ViewWorkspace)
              .map {
                case Left(error) => Left(failed(error))
                case Right(_)    => Right(())
              }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  // -- Tapir endpoints (workspace-scoped management + analytics) --------------

  private val wspath = path[UUID]("workspaceId")

  private val createSite = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "visitor-sites")
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[String]]("domain"))
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val listSites = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "visitor-sites")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[Json]])
    .errorOut(errorOut)

  private val analytics = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "visitor-analytics")
    .in(header[Option[String]]("Authorization"))
    .in(query[Option[Int]]("days"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private def createSiteLogic(
      ws: UUID,
      user: UUID,
      domain: Option[String]
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    val d = domain.getOrElse("").trim.toLowerCase
    if d.isEmpty || !d.contains(".") then
      IO.pure(
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope("validation_failed", "A valid domain is required.")
          )
        )
      )
    else
      val key = "arc_" + UUID.randomUUID().toString.replace("-", "").take(32)
      sql"""insert into public.site_keys (workspace_id, domain, site_key, created_by)
            values ($ws, $d, $key, $user) returning id, site_key"""
        .query[(UUID, String)]
        .unique
        .transact(xa)
        .map { case (id, siteKey) =>
          Right(
            Json.obj(
              "id" -> Json.fromString(id.toString),
              "domain" -> Json.fromString(d),
              "siteKey" -> Json.fromString(siteKey),
              "snippet" -> Json.fromString(
                s"""<script src="/api/v1/track.js" data-site="$siteKey" async></script>
                   |<script>window.__arcadenceConsent = true; /* set from your consent flow */</script>""".stripMargin
              )
            )
          )
        }

  private def listSitesLogic(
      ws: UUID
  ): IO[Either[(SttpStatus, Envelope), List[Json]]] =
    sql"""select id, domain, site_key, active, created_at::text from public.site_keys
          where workspace_id = $ws order by created_at desc"""
      .query[(UUID, String, String, Boolean, String)]
      .to[List]
      .transact(xa)
      .map(rows =>
        Right(
          rows.map { t =>
            Json.obj(
              "id" -> Json.fromString(t._1.toString),
              "domain" -> Json.fromString(t._2),
              "siteKey" -> Json.fromString(t._3),
              "active" -> Json.fromBoolean(t._4),
              "createdAt" -> Json.fromString(t._5)
            )
          }
        )
      )

  private def analyticsLogic(
      ws: UUID,
      days: Option[Int]
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    val n = math.max(1, math.min(90, days.getOrElse(7)))
    val q =
      for
        totals <-
          sql"""select
                  count(*) filter (where event_type = 'pageview'),
                  count(*) filter (where event_type = 'conversion'),
                  count(distinct session_id)
                from public.visitor_events
                where workspace_id = $ws and occurred_at >= now() - ($n * interval '1 day')"""
            .query[(Long, Long, Long)]
            .unique
        perDay <-
          sql"""select occurred_at::date::text,
                  count(*) filter (where event_type = 'pageview'),
                  count(*) filter (where event_type = 'conversion')
                from public.visitor_events
                where workspace_id = $ws and occurred_at >= now() - ($n * interval '1 day')
                group by 1 order by 1"""
            .query[(String, Long, Long)]
            .to[List]
        topPaths <-
          sql"""select path, count(*) from public.visitor_events
                where workspace_id = $ws and occurred_at >= now() - ($n * interval '1 day')
                group by path order by count(*) desc limit 10"""
            .query[(String, Long)]
            .to[List]
        referrers <-
          sql"""select coalesce(nullif(referrer, ''), 'direct'), count(*) from public.visitor_events
                where workspace_id = $ws and occurred_at >= now() - ($n * interval '1 day')
                group by 1 order by count(*) desc limit 10"""
            .query[(String, Long)]
            .to[List]
        campaigns <-
          sql"""select coalesce(nullif(utm_campaign, ''), '(none)'), count(*) from public.visitor_events
                where workspace_id = $ws and occurred_at >= now() - ($n * interval '1 day')
                group by 1 order by count(*) desc limit 10"""
            .query[(String, Long)]
            .to[List]
      yield
        def arr(pairs: List[(String, Long)]): Json =
          Json.fromValues(
            pairs.map(p =>
              Json.obj(
                "name" -> Json.fromString(p._1),
                "count" -> Json.fromLong(p._2)
              )
            )
          )
        Right(
          Json.obj(
            "days" -> Json.fromInt(n),
            "pageviews" -> Json.fromLong(totals._1),
            "conversions" -> Json.fromLong(totals._2),
            "sessions" -> Json.fromLong(totals._3),
            "perDay" -> Json.fromValues(
              perDay.map(d =>
                Json.obj(
                  "date" -> Json.fromString(d._1),
                  "pageviews" -> Json.fromLong(d._2),
                  "conversions" -> Json.fromLong(d._3)
                )
              )
            ),
            "topPaths" -> arr(topPaths),
            "referrers" -> arr(referrers),
            "utmCampaigns" -> arr(campaigns),
            "note" -> Json.fromString(
              "Aggregated, anonymous traffic only. Arcadence does not identify individual visitors from anonymous traffic."
            )
          )
        ): Either[(SttpStatus, Envelope), Json]
    q.transact(xa)

  // -- Public endpoints (HttpRoutes DSL): tracking script + collection -----

  private val trackingScript: String =
    """(function(){
      |  if (!window.__arcadenceConsent) return; // consent gate: sites opt in per policy
      |  var site = (document.currentScript && document.currentScript.getAttribute('data-site')) || '';
      |  if (!site) return;
      |  function send(type){
      |    var p = new URLSearchParams(window.location.search);
      |    fetch('/api/v1/track/events', {
      |      method: 'POST',
      |      headers: {'Content-Type': 'application/json'},
      |      keepalive: true,
      |      body: JSON.stringify({
      |        siteKey: site, type: type,
      |        path: window.location.pathname,
      |        referrer: document.referrer || '',
      |        utmSource: p.get('utm_source') || '',
      |        utmMedium: p.get('utm_medium') || '',
      |        utmCampaign: p.get('utm_campaign') || '',
      |        sessionId: ( sessionStorage.getItem('arc_sid')
      |          || (function(){ var s = Math.random().toString(36).slice(2); sessionStorage.setItem('arc_sid', s); return s; })() )
      |      })
      |    }).catch(function(){});
      |  }
      |  send('pageview');
      |  window.__arcadenceConversion = function(){ send('conversion'); };
      |})();""".stripMargin

  private val publicRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "v1" / "track.js" =>
      Ok(trackingScript).map(
        _.withContentType(
          org.http4s.headers.`Content-Type`(MediaType.text.javascript)
        )
      )
    case req @ POST -> Root / "api" / "v1" / "track" / "events" =>
      req.as[Json].flatMap { body =>
        val siteKey = body.hcursor.get[String]("siteKey").toOption.getOrElse("")
        val etype =
          body.hcursor.get[String]("type").toOption.getOrElse("pageview")
        if etype != "pageview" && etype != "conversion" then
          BadRequest(
            Json
              .obj(
                "code" -> Json.fromString("validation_failed"),
                "message" -> Json
                  .fromString("type must be pageview or conversion")
              )
              .noSpaces
          )
            .map(
              _.withContentType(
                org.http4s.headers.`Content-Type`(MediaType.application.json)
              )
            )
        else
          sql"""insert into public.visitor_events
                (workspace_id, site_key_id, event_type, path, referrer, utm_source, utm_medium, utm_campaign, session_id)
                select k.workspace_id, k.id, $etype,
                       ${str(body, "path", "/")}, ${str(body, "referrer", "")},
                       ${str(body, "utmSource", "")}, ${str(
              body,
              "utmMedium",
              ""
            )},
                       ${str(body, "utmCampaign", "")}, ${str(
              body,
              "sessionId",
              ""
            )}
                from public.site_keys k
                where k.site_key = $siteKey and k.active""".update.run
            .transact(xa)
            .flatMap { n =>
              if n == 1 then Ok(Json.obj("accepted" -> Json.fromBoolean(true)))
              else
                NotFound(
                  Json
                    .obj(
                      "code" -> Json.fromString("not_found"),
                      "message" -> Json
                        .fromString("Unknown or inactive site key.")
                    )
                    .noSpaces
                )
                  .map(
                    _.withContentType(
                      org.http4s.headers
                        .`Content-Type`(MediaType.application.json)
                    )
                  )
            }
      }
  }

  private def str(body: Json, field: String, default: String): String =
    body.hcursor.get[String](field).toOption.getOrElse(default).take(500)

  val http: HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      List[ServerEndpoint[Fs2Streams[IO], IO]](
        createSite.serverLogic((w, auth, domain) =>
          requireEdit(auth, w).flatMap {
            case Right(user) => createSiteLogic(w, user, domain)
            case l @ Left(_) =>
              IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
          }
        ),
        listSites.serverLogic((w, auth) =>
          requireView(auth, w).flatMap {
            case Right(_)    => listSitesLogic(w)
            case l @ Left(_) =>
              IO.pure(
                l.asInstanceOf[Either[(SttpStatus, Envelope), List[Json]]]
              )
          }
        ),
        analytics.serverLogic((w, auth, days) =>
          requireView(auth, w).flatMap {
            case Right(_)    => analyticsLogic(w, days)
            case l @ Left(_) =>
              IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
          }
        )
      )
    ) <+> publicRoutes
