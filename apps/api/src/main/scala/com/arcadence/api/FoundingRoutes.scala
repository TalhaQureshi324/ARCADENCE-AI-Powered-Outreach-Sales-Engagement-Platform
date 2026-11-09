package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.core.billing.Billing
import com.arcadence.core.founding.FoundingProgram
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.util.UUID

/** Founding 10,000 program API (Phase 14.2B.2).
  *
  * GET /founding-program public: real status + publishable terms GET
  * /founding-program/me auth: caller's membership or not-claimed POST
  * /founding-program/claim auth: transactional allocation (201) GET
  * /me/entitlements auth: FREE_LAUNCH vs FOUNDING_ACCESS profile PATCH
  * /me/announcement-preferences auth: dismiss/snooze/email opt-in
  *
  * No credits are debited and no payment is triggered by a claim. Capacity
  * comes from the locked DB counter — never from an untrusted frontend value.
  */
final class FoundingRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO]
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def requireAuth(
      auth: Option[String]
  ): IO[Either[(SttpStatus, Envelope), AuthenticatedUser]] =
    auth match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).map {
          case Left(_)     => Left(failed(ApiError.Unauthorized))
          case Right(user) => Right(user)
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private val publicStatus = endpoint.get
    .in("api" / "v1" / "founding-program")
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val myMembership = endpoint.get
    .in("api" / "v1" / "founding-program" / "me")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val claim = endpoint.post
    .in("api" / "v1" / "founding-program" / "claim")
    .in(header[Option[String]]("Authorization"))
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val myEntitlements = endpoint.get
    .in("api" / "v1" / "me" / "entitlements")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val announcementPrefs = endpoint.patch
    .in("api" / "v1" / "me" / "announcement-preferences")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[Json])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private def parseJsonb(s: String): Json =
    io.circe.parser.parse(s).getOrElse(Json.Null)

  private def statusLogic: IO[Json] = FoundingProgram.status(xa)

  private def membershipLogic(
      user: AuthenticatedUser
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    FoundingProgram.membershipOf(user.id, xa).map {
      case None =>
        Right(
          Json.obj(
            "claimed" -> Json.fromBoolean(false),
            "status" -> Json.fromString("not_a_founding_member")
          )
        )
      case Some((pos, membershipState, activated, expires)) =>
        Right(
          Json.obj(
            "claimed" -> Json.fromBoolean(true),
            "status" -> Json.fromString("founding_member"),
            "membershipState" -> Json.fromString(membershipState),
            "claimPosition" -> Json.fromInt(pos),
            "activatedAt" -> Json.fromString(activated),
            "expiresAt" -> Json.fromString(expires),
            "complimentaryMonths" -> Json
              .fromInt(FoundingProgram.monthsComplimentary),
            "autoBilling" -> Json.fromBoolean(false)
          )
        )
    }

  private def claimLogic(
      user: AuthenticatedUser
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    FoundingProgram.isUserVerified(user.id, xa).flatMap {
      case Some(false) | None =>
        IO.pure(
          Left(
            (
              SttpStatus.Forbidden,
              Envelope(
                "email_unverified",
                "Verify your email address before claiming Founding Access."
              )
            )
          )
        )
      case Some(true) =>
        FoundingProgram.claim(user.id, verified = true, xa).map {
          case FoundingProgram.Claimed(pos, exp) =>
            Right(
              Json.obj(
                "claimed" -> Json.fromBoolean(true),
                "claimPosition" -> Json.fromInt(pos),
                "expiresAt" -> Json.fromString(exp.toString),
                "complimentaryMonths" -> Json
                  .fromInt(FoundingProgram.monthsComplimentary),
                "autoBilling" -> Json.fromBoolean(false),
                "note" -> Json.fromString(
                  "Founding Access confirmed. 12 months complimentary. No credit card on file and no automatic charges at expiry."
                )
              )
            )
          case FoundingProgram.AlreadyClaimed =>
            Left(
              (
                SttpStatus.Conflict,
                Envelope(
                  "already_claimed",
                  "This account already holds a Founding membership."
                )
              )
            )
          case FoundingProgram.ProgramFull =>
            Left(
              (
                SttpStatus.Conflict,
                Envelope(
                  "program_full",
                  "The Founding 10,000 is fully allocated. The free launch tier remains available."
                )
              )
            )
          case FoundingProgram.ProgramPaused =>
            Left(
              (
                SttpStatus.Conflict,
                Envelope("program_paused", "Claiming is temporarily paused.")
              )
            )
          case FoundingProgram.UserUnverified =>
            Left(
              (
                SttpStatus.Forbidden,
                Envelope("email_unverified", "Verify your email address first.")
              )
            )
        }
    }

  private def myEntitlementsLogic(
      user: AuthenticatedUser
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    FoundingProgram.membershipOf(user.id, xa).map {
      case Some(_) =>
        Right(
          Json.obj(
            "profile" -> Json.fromString("FOUNDING_ACCESS"),
            "included" -> Json.obj(
              "workspaces" -> Json.fromString(
                "agency-tier workspace allowances during the complimentary period"
              ),
              "aiTokens" -> Json
                .fromString("fair-use monthly allowance (terms v1)"),
              "enrichment" -> Json.fromString(
                "licensed-provider credits where available; sandbox-labeled otherwise"
              )
            ),
            "autoBilling" -> Json.fromBoolean(false),
            "note" -> Json.fromString(
              "No automatic charges at expiry. Post-expiry access follows the published free-launch policy."
            )
          )
        )
      case None =>
        Right(
          Json.obj(
            "profile" -> Json.fromString("FREE_LAUNCH"),
            "included" -> Json.obj(
              "workspaces" -> Json.fromString("free-launch limits apply"),
              "note" -> Json.fromString(
                "Paid plans are not offered during the free launch period."
              )
            ),
            "autoBilling" -> Json.fromBoolean(false)
          )
        )
    }

  private def prefsLogic(
      user: AuthenticatedUser,
      body: Json
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    val dismissed = body.hcursor.get[Boolean]("dismissed").toOption
    val snoozeH = body.hcursor.get[Int]("snoozeHours").toOption
    val emailOptIn = body.hcursor.get[Boolean]("emailUpdatesOptIn").toOption
    val sets = List(
      dismissed.map {
        case true  => fr"offer_dismissed_at = now()"
        case false => fr"offer_dismissed_at = null"
      },
      snoozeH.map(h => fr"snoozed_until = now() + ($h * interval '1 hour')"),
      emailOptIn.map(o => fr"email_updates_opt_in = $o"),
      Some(fr"updated_at = now()")
    ).flatten
    (for
      _ <-
        sql"""insert into public.user_announcement_prefs (user_id) values (${user.id})
              on conflict (user_id) do nothing""".update.run
      _ <-
        (fr"update public.user_announcement_prefs set" ++ sets.intercalate(
          fr","
        ) ++
          fr"where user_id = ${user.id}").update.run
      row <-
        sql"""select offer_dismissed_at::text, snoozed_until::text, email_updates_opt_in
              from public.user_announcement_prefs where user_id = ${user.id}"""
          .query[(Option[String], Option[String], Boolean)]
          .unique
    yield Right(
      Json.obj(
        "offerDismissedAt" -> row._1.map(Json.fromString).getOrElse(Json.Null),
        "snoozedUntil" -> row._2.map(Json.fromString).getOrElse(Json.Null),
        "emailUpdatesOptIn" -> Json.fromBoolean(row._3)
      )
    ): Either[(SttpStatus, Envelope), Json]).transact(xa)

  private def requireViewForMe[A](
      auth: Option[String],
      run: AuthenticatedUser => IO[A]
  ): IO[A] =
    auth match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(_)  => IO.raiseError(new UnauthorizedSignal)
          case Right(u) => run(u)
        }
      case _ => IO.raiseError(new UnauthorizedSignal)

  private class UnauthorizedSignal extends RuntimeException

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      publicStatus.serverLogic(_ => statusLogic.map(Right(_))),
      myMembership.serverLogic((auth) =>
        requireViewForMe(auth, user => membershipLogic(user)).handleError {
          case _: UnauthorizedSignal => Left(failed(ApiError.Unauthorized))
          case e                     => throw e
        }
      ),
      claim.serverLogic((auth) =>
        requireViewForMe(auth, user => claimLogic(user)).handleError {
          case _: UnauthorizedSignal => Left(failed(ApiError.Unauthorized))
          case e                     => throw e
        }
      ),
      myEntitlements.serverLogic((auth) =>
        requireViewForMe(auth, user => myEntitlementsLogic(user)).handleError {
          case _: UnauthorizedSignal => Left(failed(ApiError.Unauthorized))
          case e                     => throw e
        }
      ),
      announcementPrefs.serverLogic((auth, req) =>
        requireViewForMe(auth, user => prefsLogic(user, req)).handleError {
          case _: UnauthorizedSignal => Left(failed(ApiError.Unauthorized))
          case e                     => throw e
        }
      )
    )
  )
