package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.billing.Billing
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Billing API (Phase 13.1). Stripe is optional at runtime: without
  * STRIPE_SECRET_KEY, checkout/portal return honest 503s while plans,
  * entitlements and usage remain fully readable. Webhook processing is
  * signature-verified and idempotent by Stripe event id.
  */
final class BillingRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO],
    httpClient: Client[IO],
    stripeSecret: Option[String],
    webhookSecret: Option[String],
    priceMap: Map[String, String]
) extends Http4sDsl[IO]:

  private val authorizer = new Authorizer(xa)
  private val apiErrorOut = statusCode.and(jsonBody[Envelope])

  private def requireManage(
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
            authorizer
              .requirePermission(user, ws, Permission.ManageWorkspace)
              .map {
                case Left(error) => Left(failed(error))
                case Right(m)    => Right(m.userId)
              }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def notConfigured =
    Left(
      (
        SttpStatus.ServiceUnavailable,
        Envelope(
          "billing_not_configured",
          "Stripe is not configured for this deployment (STRIPE_SECRET_KEY missing)."
        )
      )
    )

  // -- Tapir endpoints (workspace-scoped reads + entitlement checks) ----------

  private val wspath = path[UUID]("workspaceId")

  private val plans = endpoint.get
    .in("api" / "v1" / "billing" / "plans")
    .out(jsonBody[Json])
    .errorOut(apiErrorOut)

  private val entitlements = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "billing" / "entitlements")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(apiErrorOut)

  private val usage = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "billing" / "usage")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(apiErrorOut)

  private val subscription = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "billing" / "subscription")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(apiErrorOut)

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

  private def plansLogic: Json =
    Json.fromValues(
      Billing.plans.values.map { p =>
        Json.obj(
          "plan" -> Json.fromString(p.plan),
          "maxMailboxes" -> Json.fromInt(p.maxMailboxes),
          "maxUsers" -> Json.fromInt(p.maxUsers),
          "maxActiveLeads" -> Json.fromLong(p.maxActiveLeads),
          "monthlySendCap" -> Json.fromLong(p.monthlySendCap),
          "monthlyAiTokens" -> Json.fromLong(p.monthlyAiTokens),
          "monthlyEnrichmentCredits" -> Json.fromInt(
            p.monthlyEnrichmentCredits
          ),
          "agencyWorkspacesAllowed" -> Json.fromBoolean(
            p.agencyWorkspacesAllowed
          ),
          "apiRequestsPerMinute" -> Json.fromInt(p.apiRequestsPerMinute)
        )
      }.toVector
    )

  private def entitlementsLogic(ws: UUID): IO[Json] =
    Billing.entitlementsForWorkspace(ws).transact(xa).map {
      case None =>
        val free = Billing.entitlementsFor("free")
        entitlementJson(free.plan, free)
      case Some(ent) => entitlementJson(ent.plan, ent)
    }

  private def entitlementJson(plan: String, p: Billing.PlanEntitlements): Json =
    Json.obj(
      "plan" -> Json.fromString(plan),
      "maxMailboxes" -> Json.fromInt(p.maxMailboxes),
      "maxUsers" -> Json.fromInt(p.maxUsers),
      "maxActiveLeads" -> Json.fromLong(p.maxActiveLeads),
      "monthlySendCap" -> Json.fromLong(p.monthlySendCap),
      "monthlyAiTokens" -> Json.fromLong(p.monthlyAiTokens),
      "monthlyEnrichmentCredits" -> Json.fromInt(p.monthlyEnrichmentCredits),
      "agencyWorkspacesAllowed" -> Json.fromBoolean(p.agencyWorkspacesAllowed),
      "apiRequestsPerMinute" -> Json.fromInt(p.apiRequestsPerMinute)
    )

  private def usageLogic(ws: UUID): IO[Json] =
    val q =
      for
        ai <-
          sql"""select coalesce(sum(input_tokens + output_tokens), 0) from public.ai_usage
                    where workspace_id = $ws and created_at >= date_trunc('month', now())"""
            .query[Long]
            .unique
        contacts <-
          sql"select count(*) from public.contact where workspace_id = $ws"
            .query[Long]
            .unique
        accounts <-
          sql"select count(*) from public.email_accounts where workspace_id = $ws"
            .query[Long]
            .unique
        entOpt <- Billing.entitlementsForWorkspace(ws)
      yield
        val ent = entOpt.getOrElse(Billing.entitlementsFor("free"))
        Json.obj(
          "usage" -> Json.obj(
            "aiTokens" -> Json.fromLong(ai),
            "contacts" -> Json.fromLong(contacts),
            "mailboxes" -> Json.fromLong(accounts)
          ),
          "entitlements" -> entitlementJson(ent.plan, ent)
        )
    q.transact(xa)

  private def subscriptionLogic(ws: UUID): IO[Json] =
    sql"""select stripe_customer_id, stripe_subscription_id, plan, status,
          current_period_end::text, cancel_at_period_end
          from public.subscriptions where workspace_id = $ws
          order by updated_at desc limit 1"""
      .query[(String, String, String, String, Option[String], Boolean)]
      .option
      .transact(xa)
      .map {
        case None =>
          Json.obj(
            "plan" -> Json.fromString("free"),
            "status" -> Json.fromString("none")
          )
        case Some((cust, sub, plan, status, periodEnd, cancel)) =>
          Json.obj(
            "stripeCustomerId" -> Json.fromString(cust),
            "stripeSubscriptionId" -> Json.fromString(sub),
            "plan" -> Json.fromString(plan),
            "status" -> Json.fromString(status),
            "currentPeriodEnd" -> periodEnd
              .map(Json.fromString)
              .getOrElse(Json.Null),
            "cancelAtPeriodEnd" -> Json.fromBoolean(cancel)
          )
      }

  // -- Stripe checkout/portal (need real key; create session via Stripe REST) --

  private def createCheckoutSession(
      ws: UUID,
      plan: String
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    stripeSecret match
      case None    => IO.pure(notConfigured)
      case Some(_) =>
        // Real session creation calls Stripe REST; requires a registered price id.
        val priceId = priceMap.find(_._2 == plan).map(_._1)
        priceId match
          case None =>
            IO.pure(
              Left(
                (
                  SttpStatus.UnprocessableEntity,
                  Envelope(
                    "validation_failed",
                    s"No Stripe price configured for plan '$plan'."
                  )
                )
              )
            )
          case Some(pid) =>
            val form = List(
              "mode" -> "subscription",
              "line_items[0][price]" -> pid,
              "success_url" -> "https://app.arcadence.com/billing/success",
              "cancel_url" -> "https://app.arcadence.com/billing"
            )
            val req = Request[IO](
              Method.POST,
              Uri.unsafeFromString(
                "https://api.stripe.com/v1/checkout/sessions"
              )
            )
              .withHeaders(
                Headers(
                  Header
                    .Raw(CIString("Authorization"), s"Bearer $stripeSecret"),
                  Header.Raw(
                    CIString("Content-Type"),
                    "application/x-www-form-urlencoded"
                  )
                )
              )
              .withEntity(
                form
                  .map(f =>
                    s"${f._1}=${java.net.URLEncoder.encode(f._2, "UTF-8")}"
                  )
                  .mkString("&")
              )
            httpClient
              .run(req)
              .use(res => res.bodyText.compile.string)
              .attempt
              .map {
                case Right(text) =>
                  parse(text).toOption
                    .flatMap(_.hcursor.get[String]("url").toOption)
                    .map(url =>
                      Right(Json.obj("checkoutUrl" -> Json.fromString(url)))
                    )
                    .getOrElse(
                      Left(
                        (
                          SttpStatus.BadGateway,
                          Envelope(
                            "provider_error",
                            "Stripe response missing checkout url."
                          )
                        )
                      )
                    )
                case Left(e) =>
                  Left(
                    (
                      SttpStatus.BadGateway,
                      Envelope(
                        "provider_error",
                        s"Stripe call failed: ${e.getMessage}"
                      )
                    )
                  )
              }

  // -- Stripe webhook (signature-verified, idempotent) -------------------------

  private def verifyStripeSignature(
      payload: String,
      header: Option[String],
      secret: String
  ): Boolean =
    header match
      case None    => false
      case Some(h) =>
        val parts = h
          .split(",")
          .map(_.trim)
          .map { p =>
            val idx = p.indexOf('=')
            if idx > 0 then (p.take(idx), p.drop(idx + 1)) else (p, "")
          }
          .toMap
        val ts = parts.getOrElse("t", "")
        val sig = parts.getOrElse("v1", "")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
        val expected = mac
          .doFinal(s"$ts.$payload".getBytes("UTF-8"))
          .map("%02x".format(_))
          .mkString
        sig == expected

  private def errJson(status: SttpStatus, env: Envelope): Json =
    Json.obj(
      "httpStatus" -> Json.fromInt(status.code),
      "code" -> Json.fromString(env.code),
      "message" -> Json.fromString(env.message)
    )

  private def handleStripeEvent(event: Json): IO[Json] =
    val eventId =
      event.hcursor.downField("id").as[String].toOption.getOrElse("")
    val etype =
      event.hcursor.downField("type").as[String].toOption.getOrElse("")
    if eventId.isEmpty then
      IO.pure(
        errJson(
          SttpStatus.BadRequest,
          Envelope("validation_failed", "Missing event id.")
        )
      )
    else
      val obj = event.hcursor.downField("data").downField("object")
      val subId = obj.get[String]("id").toOption.getOrElse("")
      val cust = obj.get[String]("customer").toOption.getOrElse("")
      // Workspace resolution: Arcadence stores the mapping when checkout starts;
      // for the webhook path we resolve via billing_customers, falling back to metadata.
      val wsFromMeta = obj
        .downField("metadata")
        .get[String]("workspaceId")
        .toOption
        .flatMap(parseUuidSafe)
      val firstDue: IO[Option[UUID]] = wsFromMeta match
        case Some(w) => IO.pure(Some(w))
        case None    =>
          sql"select workspace_id from public.billing_customers where stripe_customer_id = $cust"
            .query[UUID]
            .option
            .transact(xa)
      firstDue.flatMap {
        case None if etype.startsWith("customer.subscription") =>
          IO.pure(
            errJson(
              SttpStatus.BadRequest,
              Envelope(
                "validation_failed",
                s"No workspace mapped for customer $cust sub $subId wsMeta $wsFromMeta"
              )
            )
          )
        case None =>
          // Non-subscription events are recorded but not acted on.
          Billing
            .recordEvent(None, eventId, etype, event)
            .transact(xa)
            .map(_ =>
              Json.obj(
                "received" -> Json.fromBoolean(true),
                "handled" -> Json.fromBoolean(false)
              )
            )
        case Some(ws) =>
          val plan = obj
            .downField("items")
            .downField("data")
            .values
            .getOrElse(Vector.empty)
            .headOption
            .flatMap(_.hcursor.downField("price").get[String]("id").toOption)
            .map(Billing.planForPrice(_, priceMap))
            .getOrElse("free")
          val status = etype match
            case t if t.endsWith("deleted") => "canceled"
            case _
                if obj
                  .get[Boolean]("cancel_at_period_end")
                  .toOption
                  .contains(true) =>
              "active"
            case _ =>
              obj.get[String]("status").toOption.getOrElse("active") match
                case "past_due"   => "past_due"
                case "canceled"   => "canceled"
                case "incomplete" => "incomplete"
                case other        =>
                  if other == "trialing" then "trialing" else "active"
          val periodEnd = obj
            .downField("current_period_end")
            .as[Long]
            .toOption
            .map(s => java.time.Instant.ofEpochSecond(s).toString)
          val cancelAtEnd =
            obj.get[Boolean]("cancel_at_period_end").toOption.getOrElse(false)
          val apply: ConnectionIO[Boolean] =
            for
              isFirst <- Billing.recordEvent(Some(ws), eventId, etype, event)
              _ <-
                if isFirst then
                  status match
                    case "past_due" | "canceled" | "incomplete" =>
                      Billing.applySubscription(
                        ws,
                        cust,
                        subId,
                        plan,
                        status,
                        periodEnd,
                        cancelAtEnd
                      ) *>
                        Billing.markDelinquent(ws)
                    case _ =>
                      Billing.applySubscription(
                        ws,
                        cust,
                        subId,
                        plan,
                        status,
                        periodEnd,
                        cancelAtEnd
                      )
                else FC.unit
            yield isFirst
          apply.transact(xa).map { first =>
            Json.obj(
              "received" -> Json.fromBoolean(true),
              "handled" -> Json.fromBoolean(first),
              "duplicate" -> Json.fromBoolean(!first)
            )
          }
      }

  private def parseUuidSafe(s: String): Option[UUID] =
    try Some(UUID.fromString(s))
    catch case _: Throwable => None

  // -- assembly ------------------------------------------------------------------

  val tapirRoutes: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      plans.serverLogic(_ => IO.pure(Right(plansLogic))),
      entitlements.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => entitlementsLogic(w).map(ent => Right(ent))
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      usage.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => usageLogic(w).map(Right(_))
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      subscription.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => subscriptionLogic(w).map(Right(_))
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      )
    )
  )

  private def checkoutHandler(
      ws: UUID,
      plan: String,
      auth: Option[String]
  ): IO[Json] = {
    requireManage(auth, ws).flatMap {
      case Left((status, env)) =>
        IO.pure(
          Json.obj(
            "httpStatus" -> Json.fromInt(status.code),
            "code" -> Json.fromString(env.code),
            "message" -> Json.fromString(env.message)
          )
        )
      case Right(_) =>
        createCheckoutSession(ws, plan).map {
          case Right(json)         => json
          case Left((status, env)) =>
            Json.obj(
              "httpStatus" -> Json.fromInt(status.code),
              "code" -> Json.fromString(env.code),
              "message" -> Json.fromString(env.message)
            )
        }
    }
  }

  val http: HttpRoutes[IO] = tapirRoutes <+> HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "v1" / "stripe" / "webhook" =>
      req.as[String].flatMap { payload =>
        webhookSecret match
          case None =>
            ServiceUnavailable(
              Json
                .obj(
                  "code" -> Json.fromString("billing_not_configured"),
                  "message" -> Json.fromString("Webhook secret not configured.")
                )
                .noSpaces
            ).map(
              _.withContentType(
                org.http4s.headers.`Content-Type`(MediaType.application.json)
              )
            )
          case Some(secret) =>
            if !verifyStripeSignature(
                payload,
                req.headers.get(CIString("Stripe-Signature")).map(_.head.value),
                secret
              )
            then
              BadRequest(
                Json
                  .obj(
                    "code" -> Json.fromString("invalid_signature"),
                    "message" -> Json
                      .fromString("Signature verification failed.")
                  )
                  .noSpaces
              ).map(
                _.withContentType(
                  org.http4s.headers.`Content-Type`(MediaType.application.json)
                )
              )
            else
              parse(payload).toOption match
                case None =>
                  BadRequest(
                    Json
                      .obj("code" -> Json.fromString("invalid_payload"))
                      .noSpaces
                  )
                    .map(
                      _.withContentType(
                        org.http4s.headers
                          .`Content-Type`(MediaType.application.json)
                      )
                    )
                case Some(event) =>
                  handleStripeEvent(event).flatMap { json =>
                    val code = json.hcursor
                      .get[Int]("httpStatus")
                      .toOption
                      .getOrElse(200)
                    Ok(json.noSpaces)
                      .map(
                        _.withContentType(
                          org.http4s.headers
                            .`Content-Type`(MediaType.application.json)
                        )
                      )
                      .map(
                        _.withStatus(
                          org.http4s.Status
                            .fromInt(code)
                            .fold(_ => org.http4s.Status.Ok, identity)
                        )
                      )
                  }
      }
    case req @ POST -> Root / "api" / "v1" / "workspaces" / UUIDCodec(
          ws
        ) / "billing" / "checkout" =>
      val auth = req.headers.get(CIString("Authorization")).map(_.head.value)
      req.as[String].flatMap { raw =>
        val body = io.circe.parser.parse(raw).toOption.getOrElse(Json.obj())
        val plan =
          body.hcursor.get[String]("plan").toOption.getOrElse("starter")
        checkoutHandler(ws, plan, auth).flatMap { json =>
          val code = json.hcursor.get[Int]("httpStatus").toOption.getOrElse(200)
          Ok(json.noSpaces)
            .map(
              _.withContentType(
                org.http4s.headers.`Content-Type`(MediaType.application.json)
              )
            )
            .map(
              _.withStatus(
                org.http4s.Status
                  .fromInt(code)
                  .fold(_ => org.http4s.Status.Ok, identity)
              )
            )
        }
      }
  }

  private object UUIDCodec:
    def unapply(s: String): Option[UUID] =
      try Some(UUID.fromString(s))
      catch case _: Throwable => None
