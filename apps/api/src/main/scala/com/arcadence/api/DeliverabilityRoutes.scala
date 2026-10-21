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

import java.time.OffsetDateTime
import java.util.UUID

object DeliverabilityDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class CheckDomainRequest(
      domain: String,
      provider: String,
      dkimSelectors: List[String] = Nil
  )
  object CheckDomainRequest:
    given Encoder[CheckDomainRequest] = deriveEncoder
    given Decoder[CheckDomainRequest] = deriveDecoder
    given Schema[CheckDomainRequest] = Schema.derived

  final case class DnsResultDto(
      domain: String,
      overall: String,
      findings: List[Json],
      checkedAt: String
  )
  object DnsResultDto:
    given Encoder[DnsResultDto] = deriveEncoder
    given Decoder[DnsResultDto] = deriveDecoder
    given Schema[DnsResultDto] = Schema.derived

  final case class CreatePlacementTestRequest(
      emailAccountId: UUID,
      sampleSize: Int,
      seedAddresses: List[String]
  )
  object CreatePlacementTestRequest:
    given Encoder[CreatePlacementTestRequest] = deriveEncoder
    given Decoder[CreatePlacementTestRequest] = deriveDecoder
    given Schema[CreatePlacementTestRequest] = Schema.derived

  final case class PlacementTestDto(
      id: UUID,
      emailAccountId: UUID,
      sampleSize: Int,
      status: String,
      statusReason: Option[String],
      results: Json,
      createdAt: String,
      completedAt: Option[String]
  )
  object PlacementTestDto:
    given Encoder[PlacementTestDto] = deriveEncoder
    given Decoder[PlacementTestDto] = deriveDecoder
    given Schema[PlacementTestDto] = Schema.derived

/** Deliverability Command Center API (Phase 8): DNS auth diagnostics with
  * history, sender-health snapshots with automatic pause, permission-gated
  * placement tests. All queries workspace-scoped.
  */
final class DeliverabilityRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO],
    resolver: Deliverability.Resolver
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def requirePermission(
      authorization: Option[String],
      workspaceId: UUID,
      permission: Permission
  ): IO[Either[(SttpStatus, Envelope), AuthenticatedUser]] =
    authorization match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(e) =>
            IO.pure(
              Left((SttpStatus.Unauthorized, Envelope(e.code, e.message)))
            )
          case Right(user) =>
            authorizer.requirePermission(user, workspaceId, permission).map {
              case Left(error) => Left(failed(error))
              case Right(_)    => Right(user)
            }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  // -- endpoints ------------------------------------------------------------------

  private val checkDomain = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "deliverability" / "domains" / "check"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[DeliverabilityDtos.CheckDomainRequest])
    .out(jsonBody[DeliverabilityDtos.DnsResultDto])
    .errorOut(errorOut)

  private val domainHistory = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "deliverability" / "domains" / path[String]("domain") / "history"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val healthAccount = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "deliverability" / "accounts" / path[UUID]("accountId") / "health"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val createPlacementTest = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "deliverability" / "placement-tests"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[DeliverabilityDtos.CreatePlacementTestRequest])
    .out(statusCode(SttpStatus.Created))
    .out(jsonBody[DeliverabilityDtos.PlacementTestDto])
    .errorOut(errorOut)

  private val listPlacementTests = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "deliverability" / "placement-tests"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[DeliverabilityDtos.PlacementTestDto]])
    .errorOut(errorOut)

  // -- logic ------------------------------------------------------------------------

  private def checkDomainLogic(
      auth: Option[String],
      ws: UUID,
      req: DeliverabilityDtos.CheckDomainRequest
  ): IO[Either[(SttpStatus, Envelope), DeliverabilityDtos.DnsResultDto]] =
    requirePermission(auth, ws, Permission.ManageEmailAccounts).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        val domain = req.domain.trim.toLowerCase.stripPrefix("@")
        for
          txt <- resolver(domain, "TXT")
          dkimTxt <- req.dkimSelectors.traverse(sel =>
            resolver(s"${sel.trim}._domainkey.$domain", "TXT").map(sel -> _)
          )
          dmarcTxt <- resolver(s"_dmarc.$domain", "TXT")
          spfFindings = Deliverability.checkSpf(domain, txt) ++
            Deliverability.checkProviderSpfInclude(txt, req.provider)
          dkimFindings = Deliverability.checkDkim(
            domain,
            req.dkimSelectors,
            dkimTxt.toMap
          )
          dmarcFindings = Deliverability.checkDmarc(domain, dmarcTxt)
          all = spfFindings ++ dkimFindings ++ dmarcFindings
          overall =
            if all.exists(!_.ok) then
              (if all.exists(f => !f.ok && f.message.contains("No ")) then
                 "fail"
               else "warn"
            )
            else "pass"
          overall2 = if all.exists(f => !f.ok) then overall else "pass"
          findingsJson = all.map(f =>
            Json.obj(
              "record" -> Json.fromString(f.record),
              "ok" -> Json.fromBoolean(f.ok),
              "message" -> Json.fromString(f.message),
              "remediation" -> Json.fromString(f.remediation)
            )
          )
          _ <-
            sql"""insert into public.dns_check_results (workspace_id, domain, record_type, passed, findings)
                  values ($ws, $domain, 'spf', ${all.forall(
                _.ok || all.forall(f => f.record != "spf")
              )},
                          ${Json
                .arr(findingsJson*)
                .noSpaces}::jsonb)""".update.run.void.transact(xa)
          _ <-
            sql"""insert into public.domain_health_history (workspace_id, domain, status, reasons)
                  values ($ws, $domain, ${overall2}, ${Json
                .arr(findingsJson*)
                .noSpaces}::jsonb)""".update.run.void.transact(xa)
        yield Right(
          DeliverabilityDtos.DnsResultDto(
            domain,
            overall2,
            findingsJson,
            java.time.OffsetDateTime.now().toString
          )
        )
    }

  private def domainHistoryLogic(
      auth: Option[String],
      ws: UUID,
      domain: String
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    requirePermission(auth, ws, Permission.ViewWorkspace).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        sql"""select checked_at, status, reasons::text from public.domain_health_history
              where workspace_id = $ws and domain = ${domain.toLowerCase} order by checked_at desc limit 30"""
          .query[(OffsetDateTime, String, String)]
          .to[List]
          .transact(xa)
          .map(rows =>
            Right(
              Json.obj(
                "history" -> rows
                  .map(r =>
                    Json.obj(
                      "checkedAt" -> Json.fromString(r._1.toString),
                      "status" -> Json.fromString(r._2),
                      "reasons" -> io.circe.parser
                        .parse(r._3)
                        .getOrElse(Json.Null)
                    )
                  )
                  .asJson
              )
            )
          )
    }

  private def healthLogic(
      auth: Option[String],
      ws: UUID,
      accountId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    requirePermission(auth, ws, Permission.ViewWorkspace).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        val eval = Deliverability.evaluateAccount(xa, ws, accountId, 7)
        eval
          .flatMap { h =>
            Deliverability.snapshotAccount(xa, ws, accountId, 7).as(h)
          }
          .map { h =>
            Right(
              Json.obj(
                "status" -> Json.fromString(h.status),
                "reasons" -> h.reasons.asJson,
                "bounceHardRate" -> Json.fromDoubleOrNull(h.bounceHardRate),
                "authFailures" -> Json.fromLong(h.authFailures),
                "trend" -> Json.fromString("see account_health_snapshots")
              )
            )
          }
    }

  private def createPlacementLogic(
      auth: Option[String],
      ws: UUID,
      req: DeliverabilityDtos.CreatePlacementTestRequest
  ): IO[Either[(SttpStatus, Envelope), DeliverabilityDtos.PlacementTestDto]] =
    requirePermission(auth, ws, Permission.ManageEmailAccounts).flatMap {
      case Left(env)   => IO.pure(Left(env))
      case Right(user) =>
        // Permission-based + limited: max 20 seeds, max 5 active tests.
        if req.sampleSize < 1 || req.sampleSize > 20 then
          IO.pure(
            Left(
              failed(
                ApiError.Invalid("Sample size must be 1–20 seed mailboxes.")
              )
            )
          )
        else
          sql"select count(*) from public.placement_tests where workspace_id = $ws and status in ('pending','running')"
            .query[Long]
            .unique
            .transact(xa)
            .flatMap { active =>
              if active >= 5 then
                IO.pure(
                  Left(
                    failed(
                      ApiError.Conflict(
                        "Too many active placement tests — finish or cancel existing ones."
                      )
                    )
                  )
                )
              else
                val seeds = req.seedAddresses.take(req.sampleSize)
                val configured = sys.env.get("PLACEMENT_PROVIDER_KEY").nonEmpty
                val status = if configured then "pending" else "unavailable"
                val reason = if configured then None
                else
                  Some(
                    "No approved external placement provider is configured. Results would not be trustworthy — configure PLACEMENT_PROVIDER_KEY to enable."
                  )
                sql"""
                  insert into public.placement_tests
                    (workspace_id, email_account_id, requested_by, sample_size, seed_addresses, status, status_reason)
                  values ($ws, ${req.emailAccountId}, ${user.id}, ${req.sampleSize},
                          ${Json
                    .arr(seeds.map(Json.fromString)*)
                    .noSpaces}::jsonb, $status, $reason)
                  returning id, email_account_id, sample_size, status, status_reason,
                            results::text, created_at, completed_at
                """
                  .query[
                    (
                        UUID,
                        UUID,
                        Int,
                        String,
                        Option[String],
                        String,
                        OffsetDateTime,
                        Option[OffsetDateTime]
                    )
                  ]
                  .option
                  .transact(xa)
                  .flatMap {
                    case None =>
                      IO.pure(
                        Left(
                          failed(
                            ApiError
                              .Conflict("Could not create placement test.")
                          )
                        )
                      )
                    case Some(t) =>
                      IO.pure(
                        Right(
                          DeliverabilityDtos.PlacementTestDto(
                            t._1,
                            t._2,
                            t._3,
                            t._4,
                            t._5,
                            io.circe.parser.parse(t._6).getOrElse(Json.Null),
                            t._7.toString,
                            t._8.map(_.toString)
                          )
                        )
                      )
                  }
            }
    }

  private def listPlacementLogic(
      auth: Option[String],
      ws: UUID
  ): IO[
    Either[(SttpStatus, Envelope), List[DeliverabilityDtos.PlacementTestDto]]
  ] =
    requirePermission(auth, ws, Permission.ViewWorkspace).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        sql"""select id, email_account_id, sample_size, status, status_reason,
                     results::text, created_at, completed_at
              from public.placement_tests where workspace_id = $ws order by created_at desc limit 50"""
          .query[
            (
                UUID,
                UUID,
                Int,
                String,
                Option[String],
                String,
                OffsetDateTime,
                Option[OffsetDateTime]
            )
          ]
          .to[List]
          .transact(xa)
          .map(
            _.map(t =>
              DeliverabilityDtos.PlacementTestDto(
                t._1,
                t._2,
                t._3,
                t._4,
                t._5,
                io.circe.parser.parse(t._6).getOrElse(Json.Null),
                t._7.toString,
                t._8.map(_.toString)
              )
            )
          )
          .map(Right(_))
    }

  // -- assembly --------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      checkDomain.serverLogic((ws, auth, req) =>
        checkDomainLogic(auth, ws, req)
      ),
      domainHistory.serverLogic((ws, domain, auth) =>
        domainHistoryLogic(auth, ws, domain)
      ),
      healthAccount.serverLogic((ws, accountId, auth) =>
        healthLogic(auth, ws, accountId)
      ),
      createPlacementTest.serverLogic((ws, auth, req) =>
        createPlacementLogic(auth, ws, req)
      ),
      listPlacementTests.serverLogic((ws, auth) => listPlacementLogic(auth, ws))
    )
  )
