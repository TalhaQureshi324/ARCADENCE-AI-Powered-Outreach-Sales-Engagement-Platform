package com.arcadence.core.founding

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.free.FC
import io.circe.Json

import java.time.OffsetDateTime
import java.util.UUID

/** Founding 10,000 program (Phase 14.2B). Allocation is transactional: the
  * program row is locked FOR UPDATE and the capacity counter is incremented
  * inside the claim transaction, so concurrent claims can never exceed the
  * cap. One membership per user (unique user_id). Claims are idempotent
  * (repeat claim returns AlreadyClaimed, no double allocation). Unverified
  * users are rejected before any allocation. Expiry is activation plus 12
  * calendar months; there is NEVER an automatic charge at expiry.
  */
object FoundingProgram:

  val slug           = "founding-10000"
  val capacity       = 10000
  val monthsComplimentary = 12

  sealed trait ClaimResult
  case class Claimed(position: Int, expiresAt: OffsetDateTime) extends ClaimResult
  case object AlreadyClaimed   extends ClaimResult
  case object ProgramFull      extends ClaimResult
  case object ProgramPaused    extends ClaimResult
  case object UserUnverified   extends ClaimResult

  /** Seed the single program row if absent (idempotent startup/bootstrap). */
  def seed: ConnectionIO[Unit] =
    sql"""insert into public.founding_program (slug, name, description, capacity, status)
          values ('$slug', 'Arcadence Founding 10,000',
                  'The first 10,000 verified users receive 12 months of complimentary Founding Access. No credit card. No automatic charges.',
                  $capacity, 'active')
          on conflict (slug) do nothing""".update.run.void

  def status(xa: Transactor[IO]): IO[Json] =
    sql"""select status, capacity, claimed_count, terms_version from public.founding_program
          where slug = ${slug}"""
      .query[(String, Int, Int, String)]
      .option
      .transact(xa)
      .map {
        case None => Json.obj("status" -> Json.fromString("draft"), "claimedCount" -> Json.fromInt(0), "capacity" -> Json.fromInt(capacity))
        case Some((status, cap, claimed, terms)) =>
          Json.obj(
            "slug"          -> Json.fromString(slug),
            "name"          -> Json.fromString("Arcadence Founding 10,000"),
            "description"   -> Json.fromString(
              "The first 10,000 verified users receive 12 months of complimentary Founding Access. No credit card. No automatic charges."
            ),
            "status"        -> Json.fromString(status),
            "capacity"      -> Json.fromInt(cap),
            "claimedCount"  -> Json.fromInt(claimed),
            "termsVersion"  -> Json.fromString(terms),
            "complimentaryMonths" -> Json.fromInt(monthsComplimentary)
          )
      }

  def membershipOf(userId: UUID, xa: Transactor[IO]): IO[Option[(Int, String, String, String)]] =
    sql"""select claim_position, status, activated_at::text, expires_at::text
          from public.founding_members
          where user_id = $userId and status = 'active'"""
      .query[(Int, String, String, String)]
      .option
      .transact(xa)

  def isUserVerified(userId: UUID, xa: Transactor[IO]): IO[Option[Boolean]] =
    sql"select email_confirmed_at is not null from auth.users where id = $userId"
      .query[Boolean]
      .option
      .transact(xa)

  /** Transactional claim. The FOR UPDATE lock serializes competing claims on
    * the single program row; every capacity/eligibility check happens inside
    * the lock, so the 10,000 cap can never be exceeded.
    */
  def claim(userId: UUID, verified: Boolean, xa: Transactor[IO]): IO[ClaimResult] =
    if !verified then IO.pure(UserUnverified)
    else
      val tx: ConnectionIO[ClaimResult] =
        (for
          programIdOpt <-
            sql"""select id, status, claimed_count, capacity from public.founding_program
                  where slug = ${slug} order by created_at limit 1 for update"""
              .query[(UUID, String, Int, Int)]
              .option
          result <- programIdOpt match
            case None => FC.pure(ProgramPaused)
            case Some((programId, status, claimed, rowCapacity)) =>
              val memberQ =
                sql"""select count(*) > 0 from public.founding_members
                      where user_id = $userId and status = 'active'"""
                  .query[Boolean].unique
              memberQ.flatMap { already =>
                if already then FC.pure(AlreadyClaimed)
                else if status == "paused" || status == "closed" then FC.pure(ProgramPaused)
                else if claimed >= rowCapacity then FC.pure(ProgramFull)
                else
                  val position = claimed + 1
                  sql"""insert into public.founding_members
                        (program_id, user_id, claim_position, status, expires_at, terms_version)
                        values ($programId, $userId, $position, 'active',
                                now() + interval '12 months', 'v1')
                        returning claim_position, to_char(expires_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MSOF')"""
                    .query[(Int, String)]
                    .unique
                    .flatMap { case (pos, exp) =>
                      sql"""update public.founding_program set claimed_count = $position
                            where id = $programId""".update.run.void *>
                        FC.pure(Claimed(pos, OffsetDateTime.parse(exp)))
                    }
              }
        yield result
      )
      tx.transact(xa).handleErrorWith { err =>
        // unique_violation on user_id (duplicate) or claim_position (lost race) -> honest retry/skip signals
        if err.getMessage.contains("duplicate key") then IO.pure(AlreadyClaimed)
        else IO.raiseError(err)
      }
