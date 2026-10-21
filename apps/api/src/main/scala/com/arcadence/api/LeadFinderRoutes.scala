package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.leads.LeadProvider
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.free.FC
import io.circe.Json
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.time.OffsetDateTime
import java.util.UUID

object LeadFinderDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class SearchBody(filters: Json, provider: Option[String] = None)
  object SearchBody:
    given Encoder[SearchBody] = deriveEncoder
    given Decoder[SearchBody] = deriveDecoder
    given Schema[SearchBody] = Schema.derived

  final case class SearchRequestDto(
      id: UUID,
      provider: String,
      status: String,
      resultsCount: Int,
      estimatedCredits: Int,
      creditsCharged: Int,
      error: Option[String]
  )
  object SearchRequestDto:
    given Encoder[SearchRequestDto] = deriveEncoder
    given Decoder[SearchRequestDto] = deriveDecoder
    given Schema[SearchRequestDto] = Schema.derived

  final case class DiscoveredDto(
      id: UUID,
      provider: String,
      email: Option[String],
      emailVerification: String,
      firstName: Option[String],
      lastName: Option[String],
      jobTitle: Option[String],
      companyName: Option[String],
      industry: Option[String],
      region: Option[String],
      companySize: Option[String],
      confidence: Int,
      permittedUse: String
  )
  object DiscoveredDto:
    given Encoder[DiscoveredDto] = deriveEncoder
    given Decoder[DiscoveredDto] = deriveDecoder
    given Schema[DiscoveredDto] = Schema.derived

  final case class AddToListBody(searchRequestId: UUID, listId: UUID)
  object AddToListBody:
    given Encoder[AddToListBody] = deriveEncoder
    given Decoder[AddToListBody] = deriveDecoder
    given Schema[AddToListBody] = Schema.derived

  final case class ProviderInfoDto(name: String, supportedFilters: List[String])
  object ProviderInfoDto:
    given Encoder[ProviderInfoDto] = deriveEncoder
    given Decoder[ProviderInfoDto] = deriveDecoder
    given Schema[ProviderInfoDto] = Schema.derived

/** Lead Finder API (Phase 10). Credits follow reserve → charge/refund: the
  * estimated amount is reserved in a balance-checked transaction before the
  * provider call, charged per delivered result, and refunded when the search
  * fails or delivers nothing. Every ledger entry carries a deterministic
  * idempotency key, so retries never double-charge or double-refund. Only
  * providers with signed agreements are integrated; sandbox results are labeled
  * `sandbox_only` and are never added to outreach lists.
  */
final class LeadFinderRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO],
    extraProviders: Map[String, LeadProvider.Provider] = Map.empty
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private val creditsPerLead = 1
  private val reserveUnits = 5

  private val providers: Map[String, LeadProvider.Provider] =
    Map(
      LeadProvider.SandboxProvider.name -> LeadProvider.SandboxProvider
    ) ++ extraProviders

  /** Waterfall order for enrichment (Phase 10.2): configured order, first
    * provider that returns a match wins.
    */
  private val enrichChain: List[LeadProvider.Provider] =
    List("sandbox", "apollo", "zoominfo", "snov").flatMap(providers.get)

  private class InsufficientCredits(balance: Int, needed: Int)
      extends RuntimeException(
        s"credit balance $balance is below required $needed"
      )

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def envStatus(env: Envelope): SttpStatus =
    env.code match
      case "not_found"            => SttpStatus.NotFound
      case "conflict"             => SttpStatus.Conflict
      case "validation_failed"    => SttpStatus.UnprocessableEntity
      case "insufficient_credits" => SttpStatus.PaymentRequired
      case "provider_error"       => SttpStatus.BadGateway
      case _                      => SttpStatus.InternalServerError

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

  private def resolveProvider(
      name: Option[String]
  ): Either[(SttpStatus, Envelope), LeadProvider.Provider] =
    val requested = name.getOrElse(LeadProvider.SandboxProvider.name)
    providers.get(requested) match
      case Some(p) => Right(p)
      case None    =>
        Left(
          (
            SttpStatus.UnprocessableEntity,
            Envelope(
              "validation_failed",
              s"Provider '$requested' is not integrated. Only providers with a signed data agreement are available."
            )
          )
        )

  // -- endpoints ------------------------------------------------------------------

  private val wspath = path[UUID]("workspaceId")

  private val estimate = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "leadfinder" / "estimate")
    .in(header[Option[String]]("Authorization"))
    .in(query[Int]("maxResults"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val providerList = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "leadfinder" / "providers")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[LeadFinderDtos.ProviderInfoDto]])
    .errorOut(errorOut)

  private val search = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "leadfinder" / "search")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[LeadFinderDtos.SearchBody])
    .out(jsonBody[LeadFinderDtos.SearchRequestDto])
    .errorOut(errorOut)

  private val searchResults = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / wspath / "leadfinder" / "searches" / path[
        UUID
      ]("searchId") / "results"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[LeadFinderDtos.DiscoveredDto]])
    .errorOut(errorOut)

  private val listSearches = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "leadfinder" / "searches")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[LeadFinderDtos.SearchRequestDto]])
    .errorOut(errorOut)

  private val credits = endpoint.get
    .in("api" / "v1" / "workspaces" / wspath / "leadfinder" / "credits")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val enrich = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / wspath / "leadfinder" / "enrich" / path[
        UUID
      ]("contactId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val addToList = endpoint.post
    .in("api" / "v1" / "workspaces" / wspath / "leadfinder" / "add-to-list")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[LeadFinderDtos.AddToListBody])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  // -- credit accounting -----------------------------------------------------------

  private def balanceOf(ws: UUID): ConnectionIO[Int] =
    sql"select coalesce(sum(delta), 0) from public.credit_transactions where workspace_id = $ws"
      .query[Int]
      .unique

  /** Idempotent ledger write — the unique partial index on idempotency_key
    * makes duplicate writes no-ops, so retried finalization never double-
    * charges or double-refunds.
    */
  private def ledger(
      ws: UUID,
      delta: Int,
      kind: String,
      refId: UUID,
      idem: String,
      reason: String
  ): ConnectionIO[Unit] =
    sql"""insert into public.credit_transactions (workspace_id, delta, kind, ref_id, idempotency_key, reason)
          values ($ws, $delta, $kind, $refId, $idem, $reason)
          on conflict do nothing""".update.run.void

  private def loadRow(
      ws: UUID,
      searchId: UUID
  ): ConnectionIO[LeadFinderDtos.SearchRequestDto] =
    sql"""select id, provider, status, results_count, estimated_credits, credits_charged, error
          from public.search_requests where id = $searchId and workspace_id = $ws"""
      .query[(UUID, String, String, Int, Int, Int, Option[String])]
      .unique
      .map(t =>
        LeadFinderDtos
          .SearchRequestDto(t._1, t._2, t._3, t._4, t._5, t._6, t._7)
      )

  private def providerErrorText(err: LeadProvider.ProviderError): String =
    err match
      case LeadProvider.Timeout(d)     => s"provider timeout: $d"
      case LeadProvider.RateLimited(s) =>
        s"provider rate limited (retry after ${s}s)"
      case LeadProvider.QuotaExceeded(d) => s"provider quota exceeded: $d"
      case LeadProvider.Unauthorized(d)  => s"provider unauthorized: $d"
      case LeadProvider.BadRequest(d)    => s"provider rejected the request: $d"
      case LeadProvider.Unavailable(d)   => s"provider unavailable: $d"

  // -- logic -------------------------------------------------------------------------

  private def estimateLogic(
      maxResults: Int
  ): Either[(SttpStatus, Envelope), Json] =
    val units = math.max(1, math.min(100, maxResults))
    Right(
      Json.obj(
        "estimatedCredits" -> Json.fromInt(units * creditsPerLead),
        "creditsPerLead" -> Json.fromInt(creditsPerLead),
        "note" -> Json.fromString(
          "Credits are reserved up front, charged per delivered result, and refunded when a search fails or returns nothing usable."
        )
      )
    )

  private def providersLogic(): List[LeadFinderDtos.ProviderInfoDto] =
    enrichChain.map(p =>
      LeadFinderDtos.ProviderInfoDto(p.name, p.supportedFilters.toList.sorted)
    )

  private def filtersFrom(
      body: LeadFinderDtos.SearchBody
  ): LeadProvider.SearchFilters =
    def str(key: String): Option[String] =
      body.filters.hcursor.get[String](key).toOption.filter(_.trim.nonEmpty)
    LeadProvider.SearchFilters(
      industry = str("industry"),
      region = str("region"),
      jobTitle = str("jobTitle"),
      companySize = str("companySize"),
      technology = str("technology")
    )

  private def storeDiscovered(
      ws: UUID,
      searchId: UUID,
      providerName: String,
      c: LeadProvider.DiscoveredContact
  ): ConnectionIO[Int] =
    sql"""insert into public.discovered_contacts
          (workspace_id, search_request_id, provider, provider_contact_id, email,
           email_verification, first_name, last_name, job_title, company_name,
           company_domain, industry, region, company_size, confidence, permitted_use)
          values ($ws, $searchId, $providerName, ${c.providerContactId}, ${c.email},
                  ${c.emailVerification}, ${c.firstName}, ${c.lastName}, ${c.jobTitle},
                  ${c.companyName}, ${c.companyDomain}, ${c.industry}, ${c.region},
                  ${c.companySize}, ${c.confidence}, ${c.permittedUse})
          on conflict (workspace_id, provider, provider_contact_id) do nothing
          returning id"""
      .query[UUID]
      .option
      .flatMap {
        case None       => FC.pure(0)
        case Some(dcid) =>
          sql"""insert into public.provenance_records
                (workspace_id, discovered_contact_id, field, source, value, confidence, retrieved_at)
                values ($ws, $dcid, 'contact', $providerName, ${c.email
              .getOrElse("")}, ${c.confidence}, now())""".update.run.void *>
            sql"""insert into public.provider_usage (workspace_id, provider, operation, units)
                  values ($ws, $providerName, 'contact', 1)""".update.run.void *>
            FC.pure(1)
      }

  private def searchLogic(
      ws: UUID,
      user: UUID,
      body: LeadFinderDtos.SearchBody
  ): IO[Either[(SttpStatus, Envelope), LeadFinderDtos.SearchRequestDto]] =
    resolveProvider(body.provider) match
      case Left(env)       => IO.pure(Left(env))
      case Right(provider) =>
        val searchId = UUID.randomUUID()
        val reserveTx: ConnectionIO[UUID] =
          for
            // Serialize reservations per workspace so concurrent searches
            // cannot both pass the balance check and overdraw the ledger.
            _ <-
              sql"select 42, pg_advisory_xact_lock(hashtext(${ws.toString}))"
                .query[Int]
                .unique
                .void
            balance <- balanceOf(ws)
            _ <-
              if balance < reserveUnits then
                FC.raiseError(new InsufficientCredits(balance, reserveUnits))
              else FC.unit
            _ <- ledger(
              ws,
              -reserveUnits,
              "reserve",
              searchId,
              s"lf-reserve:$searchId",
              "leadfinder search reservation"
            )
            sid <-
              sql"""insert into public.search_requests
                    (id, workspace_id, requested_by, provider, filters, status, estimated_credits)
                    values ($searchId, $ws, $user, ${provider.name}, ${body.filters.noSpaces}::jsonb,
                            'running', $reserveUnits) returning id"""
                .query[UUID]
                .unique
          yield sid

        def failSearch(
            detail: String
        ): ConnectionIO[LeadFinderDtos.SearchRequestDto] =
          for
            _ <-
              sql"""update public.search_requests set status = 'failed',
                    error = $detail, finished_at = now()
                    where id = $searchId""".update.run.void
            _ <- ledger(
              ws,
              reserveUnits,
              "refund",
              searchId,
              s"lf-refund:$searchId",
              s"search failed: $detail"
            )
            row <- loadRow(ws, searchId)
          yield row

        def finishSearch(
            stored: Int
        ): ConnectionIO[LeadFinderDtos.SearchRequestDto] =
          for
            _ <-
              sql"""update public.search_requests set status = 'done',
                    results_count = $stored,
                    credits_charged = ${stored * creditsPerLead}, finished_at = now()
                    where id = $searchId""".update.run.void
            // Release the reservation, then charge exactly the delivered
            // results — the ledger nets out to stored × creditsPerLead.
            _ <- ledger(
              ws,
              reserveUnits,
              "refund",
              searchId,
              s"lf-release:$searchId",
              "reservation released"
            )
            _ <-
              if stored > 0 then
                ledger(
                  ws,
                  -stored * creditsPerLead,
                  "charge",
                  searchId,
                  s"lf-charge:$searchId",
                  "leadfinder results"
                )
              else FC.unit
            row <- loadRow(ws, searchId)
          yield row

        def runSearch(
            sid: UUID
        ): IO[Either[(SttpStatus, Envelope), LeadFinderDtos.SearchRequestDto]] =
          provider.search(filtersFrom(body), 1).flatMap {
            case Left(err) =>
              failSearch(providerErrorText(err))
                .transact(xa)
                .map(dto => Right(dto))
            case Right(page) =>
              page.contacts
                .traverse(c =>
                  storeDiscovered(ws, sid, provider.name, c).transact(xa)
                )
                .map(_.sum)
                .flatMap(stored => finishSearch(stored).transact(xa))
                .attempt
                .flatMap {
                  case Right(dto)  => IO.pure(Right(dto))
                  case Left(other) =>
                    failSearch(other.getMessage)
                      .transact(xa)
                      .map(dto => Right(dto))
                }
          }

        reserveTx.transact(xa).attempt.flatMap {
          case Left(_: InsufficientCredits) =>
            IO.pure(
              Left(
                (
                  SttpStatus.PaymentRequired,
                  Envelope(
                    "insufficient_credits",
                    s"Credit balance is too low for this search (needs $reserveUnits)."
                  )
                )
              )
            )
          case Left(other) => IO.raiseError(other)
          case Right(sid)  => runSearch(sid)
        }

  private def resultsLogic(
      ws: UUID,
      searchId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[LeadFinderDtos.DiscoveredDto]]] =
    sql"""select id, provider, email, email_verification, first_name, last_name,
          job_title, company_name, industry, region, company_size, confidence, permitted_use
          from public.discovered_contacts
          where workspace_id = $ws and search_request_id = $searchId
          order by confidence desc, created_at"""
      .query[
        (
            UUID,
            String,
            Option[String],
            String,
            Option[String],
            Option[String],
            Option[String],
            Option[String],
            Option[String],
            Option[String],
            Option[String],
            Int,
            String
        )
      ]
      .to[List]
      .transact(xa)
      .map { rows =>
        Right(
          rows.map { t =>
            LeadFinderDtos.DiscoveredDto(
              t._1,
              t._2,
              t._3,
              t._4,
              t._5,
              t._6,
              t._7,
              t._8,
              t._9,
              t._10,
              t._11,
              t._12,
              t._13
            )
          }
        )
      }

  private def listSearchesLogic(
      ws: UUID
  ): IO[List[LeadFinderDtos.SearchRequestDto]] =
    sql"""select id, provider, status, results_count, estimated_credits, credits_charged, error
          from public.search_requests where workspace_id = $ws order by created_at desc limit 50"""
      .query[(UUID, String, String, Int, Int, Int, Option[String])]
      .to[List]
      .transact(xa)
      .map(rows =>
        rows.map(t =>
          LeadFinderDtos
            .SearchRequestDto(t._1, t._2, t._3, t._4, t._5, t._6, t._7)
        )
      )

  private def creditsLogic(ws: UUID): IO[Json] =
    val q =
      for
        balance <- balanceOf(ws)
        history <-
          sql"""select kind, delta, reason, created_at from public.credit_transactions
                where workspace_id = $ws order by created_at desc limit 50"""
            .query[(String, Int, String, OffsetDateTime)]
            .to[List]
      yield
        val hist = history.map { h =>
          Json.obj(
            "kind" -> Json.fromString(h._1),
            "delta" -> Json.fromInt(h._2),
            "reason" -> Json.fromString(h._3),
            "at" -> Json.fromString(h._4.toString)
          )
        }
        Json.obj(
          "balance" -> Json.fromInt(balance),
          "history" -> Json.fromValues(hist)
        )
    q.transact(xa)

  /** Waterfall enrichment (Phase 10.2): providers are tried in configured order
    * and the first match wins. This is a preview — existing contact values are
    * never silently overwritten; the caller approves before any write. Each
    * returned field is recorded as per-contact enrichment history in
    * provenance_records.
    */
  private def enrichLogic(
      ws: UUID,
      contactId: UUID
  ): IO[Either[Envelope, Json]] =
    sql"""select email, website from public.contact
          where workspace_id = $ws and id = $contactId"""
      .query[(Option[String], Option[String])]
      .option
      .transact(xa)
      .flatMap {
        case None =>
          IO.pure(Left(Envelope("not_found", "Contact not found.")))
        case Some((emailOpt, domainOpt)) =>
          emailOpt match
            case None =>
              IO.pure(
                Left(
                  Envelope(
                    "validation_failed",
                    "Contact has no email; providers cannot enrich it."
                  )
                )
              )
            case Some(email) =>
              def tryProvider(
                  p: LeadProvider.Provider
              ): IO[
                Option[(LeadProvider.Provider, LeadProvider.DiscoveredContact)]
              ] =
                p.enrich(email, domainOpt)
                  .attempt
                  .map(
                    _.toOption.flatMap(_.toOption).flatten.map(c => (p, c))
                  )

              enrichChain
                .foldLeft(
                  IO.pure(
                    Option.empty[
                      (LeadProvider.Provider, LeadProvider.DiscoveredContact)
                    ]
                  )
                ) { (acc, p) =>
                  acc.flatMap {
                    case Some(found) => IO.pure(Some(found))
                    case None        => tryProvider(p)
                  }
                }
                .flatMap {
                  case None =>
                    IO.pure(
                      Left(
                        Envelope(
                          "not_found",
                          "No integrated provider returned enrichment for this contact."
                        )
                      )
                    )
                  case Some((p, c)) =>
                    val fields = List(
                      "jobTitle" -> c.jobTitle,
                      "companyName" -> c.companyName,
                      "industry" -> c.industry,
                      "companySize" -> c.companySize,
                      "companyDomain" -> c.companyDomain
                    )
                    val record: ConnectionIO[Unit] =
                      fields
                        .collect { case (f, Some(v)) => (f, v) }
                        .traverse_ { case (f, v) =>
                          sql"""insert into public.provenance_records
                              (workspace_id, contact_id, field, source, value, confidence, retrieved_at)
                              values ($ws, $contactId, $f, ${p.name}, $v, ${c.confidence}, now())""".update.run.void
                        }
                    record.transact(xa).map { _ =>
                      Right(
                        Json.obj(
                          "provider" -> Json.fromString(p.name),
                          "confidence" -> Json.fromInt(c.confidence),
                          "fields" -> Json.obj(
                            "jobTitle" -> c.jobTitle
                              .map(Json.fromString)
                              .getOrElse(Json.Null),
                            "companyName" -> c.companyName
                              .map(Json.fromString)
                              .getOrElse(Json.Null),
                            "industry" -> c.industry
                              .map(Json.fromString)
                              .getOrElse(Json.Null),
                            "companySize" -> c.companySize
                              .map(Json.fromString)
                              .getOrElse(Json.Null),
                            "companyDomain" -> c.companyDomain
                              .map(Json.fromString)
                              .getOrElse(Json.Null)
                          ),
                          "note" -> Json.fromString(
                            "Preview only — nothing was written to the contact. Review and approve to apply."
                          )
                        )
                      )
                    }
                }
      }

  /** Add-to-list (Phase 10.4): per-contact dedupe against the workspace contact
    * table and suppression list, licensing enforcement (sandbox_only contacts
    * are never added), and idempotent re-runs via
    * discovered_contacts.added_to_list_id. Runs in one transaction so the
    * counted result can never disagree with the stored state.
    */
  private def addToListLogic(
      ws: UUID,
      body: LeadFinderDtos.AddToListBody
  ): IO[Either[Envelope, Json]] =
    val tx: ConnectionIO[Either[Envelope, Json]] =
      for
        listOk <-
          sql"select count(*) > 0 from public.lead_list where workspace_id = $ws and id = ${body.listId}"
            .query[Boolean]
            .unique
        result <-
          if !listOk then
            FC.pure(
              Left(Envelope("not_found", "List not found in this workspace."))
            )
          else
            for
              rows <-
                sql"""select id, email, first_name, last_name, job_title, region,
                      permitted_use, added_to_list_id
                      from public.discovered_contacts
                      where workspace_id = $ws and search_request_id = ${body.searchRequestId}"""
                  .query[
                    (
                        UUID,
                        Option[String],
                        Option[String],
                        Option[String],
                        Option[String],
                        Option[String],
                        String,
                        Option[UUID]
                    )
                  ]
                  .to[List]
              counts <-
                rows.foldLeft(FC.pure((0, 0, 0, false))) { (accF, r) =>
                  accF.flatMap {
                    case (added, dupes, suppressed, sandboxBlocked) =>
                      val (
                        dcid,
                        emailOpt,
                        firstName,
                        lastName,
                        jobTitle,
                        region,
                        permittedUse,
                        alreadyList
                      ) = r
                      if alreadyList.isDefined || emailOpt.isEmpty then
                        FC.pure((added, dupes, suppressed, sandboxBlocked))
                      else if permittedUse == "sandbox_only" then
                        FC.pure((added, dupes, suppressed, true))
                      else
                        val em = emailOpt.get.trim.toLowerCase
                        for
                          isSuppressed <-
                            sql"select count(*) > 0 from public.suppression where workspace_id = $ws and lower(email) = $em"
                              .query[Boolean]
                              .unique
                          exists <-
                            if isSuppressed then FC.pure(false)
                            else
                              sql"select count(*) > 0 from public.contact where workspace_id = $ws and lower(email) = $em"
                                .query[Boolean]
                                .unique
                          added2 <-
                            if isSuppressed || exists then FC.pure(added)
                            else
                              for
                                cidOpt <-
                                  sql"""insert into public.contact
                                      (workspace_id, email, first_name, last_name, job_title, region)
                                      values ($ws, $em, $firstName, $lastName, $jobTitle, $region)
                                      on conflict do nothing returning id"""
                                    .query[UUID]
                                    .option
                                    .flatMap {
                                      case Some(cid) => FC.pure(Some(cid))
                                      case None      =>
                                        sql"select id from public.contact where workspace_id = $ws and lower(email) = $em"
                                          .query[UUID]
                                          .option
                                    }
                                _ <- cidOpt.traverse { c =>
                                  sql"""insert into public.source_provenance (workspace_id, contact_id, source, details)
                                      values ($ws, $c, 'leadfinder',
                                              ${Json
                                      .obj(
                                        "provider" -> Json.fromString(
                                          "sandbox"
                                        ),
                                        "searchRequestId" -> Json.fromString(
                                          body.searchRequestId.toString
                                        )
                                      )
                                      .noSpaces}::jsonb)""".update.run.void
                                }
                                mem <-
                                  cidOpt.traverse { c =>
                                    sql"""insert into public.list_membership (list_id, contact_id)
                                        values (${body.listId}, $c)
                                        on conflict do nothing returning contact_id"""
                                      .query[UUID]
                                      .option
                                  }
                              yield mem.flatten match
                                case Some(_) => added + 1
                                case None    => added
                          dupes2 =
                            if exists && !isSuppressed then dupes + 1 else dupes
                          suppressed2 =
                            if isSuppressed then suppressed + 1 else suppressed
                          _ <-
                            if !isSuppressed then
                              sql"""update public.discovered_contacts set added_to_list_id = ${body.listId}
                                  where id = $dcid and added_to_list_id is null""".update.run.void
                            else FC.unit
                        yield (added2, dupes2, suppressed2, sandboxBlocked)
                  }
                }
            yield
              val (added, dupes, suppressed, sandboxBlocked) = counts
              Right(
                Json.obj(
                  "added" -> Json.fromInt(added),
                  "skippedDuplicates" -> Json.fromInt(dupes),
                  "skippedSuppressed" -> Json.fromInt(suppressed),
                  "sandboxOnlyBlocked" -> Json.fromBoolean(sandboxBlocked),
                  "note" -> Json.fromString(
                    "Duplicates and suppressed addresses are skipped. Sandbox-only contacts are never added."
                  )
                )
              ): Either[Envelope, Json]
      yield result
    tx.transact(xa)

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      estimate.serverLogic((w, auth, max) =>
        requireEdit(auth, w).flatMap {
          case Right(_)    => IO.pure(estimateLogic(max))
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      providerList.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => IO.pure(Right(providersLogic()))
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                LeadFinderDtos.ProviderInfoDto
              ]]]
            )
        }
      ),
      search.serverLogic((w, auth, body) =>
        requireEdit(auth, w).flatMap {
          case Right(user) => searchLogic(w, user, body)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[
                Either[(SttpStatus, Envelope), LeadFinderDtos.SearchRequestDto]
              ]
            )
        }
      ),
      searchResults.serverLogic((w, sid, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => resultsLogic(w, sid)
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                LeadFinderDtos.DiscoveredDto
              ]]]
            )
        }
      ),
      listSearches.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_) =>
            listSearchesLogic(w).map(list => Right(list))
          case l @ Left(_) =>
            IO.pure(
              l.asInstanceOf[Either[(SttpStatus, Envelope), List[
                LeadFinderDtos.SearchRequestDto
              ]]]
            )
        }
      ),
      credits.serverLogic((w, auth) =>
        requireView(auth, w).flatMap {
          case Right(_)    => creditsLogic(w).map(json => Right(json))
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      enrich.serverLogic((w, cid, auth) =>
        requireEdit(auth, w).flatMap {
          case Right(_) =>
            enrichLogic(w, cid).map {
              case Right(json) => Right(json)
              case Left(env)   => Left((envStatus(env), env))
            }
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      ),
      addToList.serverLogic((w, auth, body) =>
        requireEdit(auth, w).flatMap {
          case Right(_) =>
            addToListLogic(w, body).map {
              case Right(json) => Right(json)
              case Left(env)   => Left((envStatus(env), env))
            }
          case l @ Left(_) =>
            IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }
      )
    )
  )
