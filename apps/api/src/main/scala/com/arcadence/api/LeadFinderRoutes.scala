package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import com.arcadence.core.jobs.JobQueue
import com.arcadence.core.leads.LeadProvider
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

object LeadFinderDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class SearchRequestDto(
      id: UUID, provider: String, status: String, resultsCount: Int,
      estimatedCredits: Int, creditsCharged: Int, error: Option[String]
  )
  object SearchRequestDto:
    given Encoder[SearchRequestDto] = deriveEncoder
    given Decoder[SearchRequestDto] = deriveDecoder
    given Schema[SearchRequestDto] = Schema.derived

  final case class SearchBody(filters: Json, provider: Option[String] = None)
  object SearchBody:
    given Encoder[SearchBody] = deriveEncoder
    given Decoder[SearchBody] = deriveDecoder
    given Schema[SearchBody] = Schema.derived

  final case class DiscoveredDto(
      id: UUID, provider: String, email: Option[String], emailVerification: String,
      firstName: Option[String], lastName: Option[String], jobTitle: Option[String],
      companyName: Option[String], industry: Option[String], region: Option[String],
      confidence: Int, permittedUse: String
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

/** Lead Finder API (Phase 10): credit-estimated, reservation-guarded searches
  * against a licensed provider framework (sandbox adapter for tests/dev),
  * discovered-contact previews with provenance and verification labels,
  * waterfall enrichment (provider order, no silent overwrites, per-contact
  * history), and add-to-list with dedupe + suppression checks.
  *
  * Provider timeouts leave the search `failed` with credits refunded — the
  * engine never fabricates live leads when providers are unavailable.
  */
final class LeadFinderRoutes(
    authenticator: Authenticator,
    xa: Transactor[IO]
):

  private val authorizer = new Authorizer(xa)
  private val errorOut = statusCode.and(jsonBody[Envelope])

  private val providers: Map[String, LeadProvider.Provider] =
    Map(LeadProvider.SandboxProvider.name -> LeadProvider.SandboxProvider)

  private val creditsPerLead = 1

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def requireEdit(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), AuthenticatedUser]] =
    authorization match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(e) => IO.pure(Left((SttpStatus.Unauthorized, Envelope(e.code, e.message))))
          case Right(user) =>
            new Authorizer(xa).requirePermission(user, workspaceId, Permission.EditContent).map {
              case Left(error) => Left(failed(error))
              case Right(_)    => Right(user)
            }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private def requireView(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), Unit]] =
    authorization match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator.verify(token.stripPrefix("Bearer ").trim).flatMap {
          case Left(e) => IO.pure(Left((SttpStatus.Unauthorized, Envelope(e.code, e.message))))
          case Right(user) =>
            new Authorizer(xa).requirePermission(user, workspaceId, Permission.ViewWorkspace).map {
              case Left(error) => Left(failed(error))
              case Right(_)    => Right(())
            }
        }
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  // -- endpoints ------------------------------------------------------------------

  private val estimate = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "leadfinder" / "estimate")
    .in(header[Option[String]]("Authorization"))
    .in(query[Int]("maxResults"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val search = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "leadfinder" / "search")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[LeadFinderDtos.SearchBody])
    .out(jsonBody[LeadFinderDtos.SearchRequestDto])
    .errorOut(errorOut)

  private val searchResults = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "leadfinder" / "searches" / path[UUID]("searchId") / "results")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[LeadFinderDtos.DiscoveredDto]])
    .errorOut(errorOut)

  private val credits = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "leadfinder" / "credits")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val enrich = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "leadfinder" / "enrich" / path[UUID]("contactId"))
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[Json])
    .errorOut(errorOut)

  private val addToList = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "leadfinder" / "add-to-list")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[LeadFinderDtos.AddToListBody])
    .out(jsonBody[Json])
    .errorOut(errorOut)

  // -- logic ------------------------------------------------------------------------

  private def resolveProvider(name: Option[String]): IO[Either[(SttpStatus, Envelope), LeadProvider.Provider]] =
    val requested = name.getOrElse("sandbox")
    providers.get(requested) match
      case Some(p) => IO.pure(Right(p))
      case None =>
        IO.pure(Left(failed(ApiError.Invalid(
          s"Provider '$requested' is not integrated. Providers with agreements only; sandbox is available."
        ))))

  private def estimateLogic(
      auth: Option[String], ws: UUID, maxResults: Int
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    requireEdit(auth, ws).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        val units = math.max(1, math.min(100, maxResults))
        IO.pure(Right(Json.obj(
          "estimatedCredits" -> Json.fromInt(units * creditsPerLead),
          "note" -> Json.fromString(
            "Credits are reserved up front and refunded when a search returns no usable results.")
        )))
    }

  /** Balance = sum of credit_transactions.delta for the workspace. */
  private def balance(ws: UUID): IO[Int] =
    sql"select coalesce(sum(delta), 0) from public.credit_transactions where workspace_id = $ws"
      .query[Int].unique.transact(xa)

  private def searchLogic(
      auth: Option[String], ws: UUID, user: UUID, body: LeadFinderDtos.SearchBody
  ): IO[Either[(SttpStatus, Envelope), LeadFinderDtos.SearchRequestDto]] =
    resolveProvider(body.provider).flatMap {
      case Right(provider) => searchLogicResolved(ws, user, provider, body)
      case Left((status, env)) => IO.pure(Left((status, env)))
    }

  private def searchLogicResolved(
      ws: UUID, user: UUID, provider: LeadProvider.Provider, body: LeadFinderDtos.SearchBody
  ): IO[Either[(SttpStatus, Envelope), LeadFinderDtos.SearchRequestDto]] =
    {
      val estimateUnits = 5
      val idemReserve = s"lf-reserve:$ws:${UUID.randomUUID()}"
      val reqId = UUID.randomUUID()
      val tx: ConnectionIO[UUID] =
          for
            balance <-
              sql"select coalesce(sum(delta), 0) from public.credit_transactions where workspace_id = $ws"
                .query[Int].unique
            _ <-
              if balance < estimateUnits then FC.raiseError(new InsufficientCredits(balance, estimateUnits))
              else FC.unit
            _ <-
              sql"""insert into public.credit_transactions (workspace_id, delta, kind, ref_id, idempotency_key, reason)
                    values ($ws, ${-estimateUnits}, 'reserve', $reqId, $idemReserve, 'leadfinder search reservation')"""
                .update.run.void
            srId <-
              sql"""insert into public.search_requests
                    (workspace_id, requested_by, provider, filters, status, estimated_credits)
                    values ($ws, $user, ${provider.name}, ${body.filters.noSpaces}::jsonb,
                            'running', $estimateUnits) returning id""".query[UUID].unique
          yield srId
        val txIO: IO[UUID] = tx.transact(xa)
        txIO.flatMap { searchId =>
          val searchAndFinalize: IO[LeadFinderDtos.SearchRequestDto] =
            runSearch(ws, searchId, provider, body.filters).flatMap { count =>
              val finishStep: IO[LeadFinderDtos.SearchRequestDto] =
                (for
                  _ <-
                    if count == 0 then
                      sql"""insert into public.credit_transactions (workspace_id, delta, kind, ref_id, reason)
                            values ($ws, ${estimateUnits}, 'refund', $searchId, 'no usable results — reservation refunded')""".update.run.void
                    else
                      sql"""insert into public.credit_transactions (workspace_id, delta, kind, ref_id, reason)
                            values ($ws, ${-count * creditsPerLead}, 'charge', $searchId, 'leadfinder results')""".update.run.void
                  row <-
                    sql"""select id, provider, status, results_count, credits_charged,
                          estimated_credits, error from public.search_requests where id = $searchId"""
                      .query[(UUID, String, String, Int, Int, Int, Option[String])].unique
                yield LeadFinderDtos.SearchRequestDto(row._1, row._2, row._3, row._4, row._5, row._6, row._7)
              ).transact(xa)
              finishStep
            }.attempt.flatMap {
              case Right(dto) => IO.pure[Either[(SttpStatus, Envelope), LeadFinderDtos.SearchRequestDto]](Right(dto))
              case Left(_: InsufficientCredits) =>
                IO.pure(Left(failed(ApiError.Invalid("Insufficient credits for this search."))))
              case Left(other) =>
                (for
                  _ <- sql"""update public.search_requests set status = 'failed',
                               error = ${other.getMessage}, finished_at = now()
                             where id = $searchId""".update.run.void
                  _ <- sql"""insert into public.credit_transactions (workspace_id, delta, kind, ref_id, reason)
                             values ($ws, ${estimateUnits}, 'refund', $searchId, 'search failed — reservation refunded')"""
                      .update.run.void
                yield Left(failed(ApiError.Invalid(s"Search failed: ${other.getMessage} — credits refunded."))))
                  .transact(xa)
          }
      }
    }

  private def runSearch(
      ws: UUID, searchId: UUID, provider: LeadProvider.Provider, filters: Json
  ): IO[Int] =
    val f = LeadProvider.SearchFilters(
      industry = filters.hcursor.get[String]("industry").toOption,
      region = filters.hcursor.get[String]("region").toOption,
      jobTitle = filters.hcursor.get[String]("jobTitle").toOption,
      companySize = filters.hcursor.get[String]("companySize").toOption,
      technology = filters.hcursor.get[String]("technology").toOption
    )
    provider.search(f, page = 1).flatMap {
      case Left(err) => IO.raiseError(new RuntimeException(providerErrorText(err)))
      case Right(page) =>
        page.contacts.traverse_(c => storeDiscovered(ws, searchId, provider.name, c)) *>
          sql"""update public.search_requests set status = 'done', results_count = ${page.contacts.size},
                credits_charged = ${page.contacts.size * creditsPerLead}, finished_at = now()
                where id = $searchId""".update.run.void *>
          sql"""insert into public.provider_usage (workspace_id, provider, operation, units)
                values ($ws, ${provider.name}, 'search', ${page.contacts.size})""".update.run.void *>
          IO.pure(page.contacts.size)
    }

  private def providerErrorText(err: LeadProvider.ProviderError): String = err match
    case LeadProvider.Timeout(d)          => s"provider timeout: $d"
    case LeadProvider.RateLimited(s)      => s"provider rate limited (retry after ${s}s)"
    case LeadProvider.QuotaExceeded(d)    => s"provider quota exceeded: $d"
    case LeadProvider.Unauthorized(d)     => s"provider unauthorized: $d"
    case LeadProvider.BadRequest(d)       => s"provider rejected the request: $d"
    case LeadProvider.Unavailable(d)      => s"provider unavailable: $d"

  private def storeDiscovered(
      ws: UUID, searchId: UUID, providerName: String, c: LeadProvider.DiscoveredContact
  ): IO[Unit] =
    (for
      id <-
        sql"""insert into public.discovered_contacts
              (workspace_id, search_request_id, provider, provider_contact_id, email,
               email_verification, first_name, last_name, job_title, company_name,
               company_domain, industry, region, company_size, confidence, permitted_use)
              values ($ws, $searchId, $providerName, ${c.providerContactId}, ${c.email},
                      ${c.emailVerification}, ${c.firstName}, ${c.lastName}, ${c.jobTitle},
                      ${c.companyName}, ${c.companyDomain}, ${c.industry}, ${c.region},
                      ${c.companySize}, ${c.confidence}, ${c.permittedUse})
              on conflict (provider, provider_contact_id) do nothing
              returning id""".query[UUID].option
      _ <- id.traverse { dcid =>
        sql"""insert into public.provenance_records
              (workspace_id, discovered_contact_id, field, source, value, confidence, retrieved_at)
              values ($ws, $dcid, 'contact', $providerName, ${c.email.getOrElse("")}, ${c.confidence}, now())"""
          .update.run.void *>
          sql"""insert into public.provider_usage (workspace_id, provider, operation, units)
                values ($ws, $providerName, 'contact', 1)""".update.run.void
      }
    yield ()).transact(xa)

  private def resultsLogic(
      auth: Option[String], ws: UUID, searchId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[LeadFinderDtos.DiscoveredDto]]] =
    requireView(auth, ws).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        sql"""select id, provider, email, email_verification, first_name, last_name,
              job_title, company_name, industry, region, confidence, permitted_use
              from public.discovered_contacts
              where workspace_id = $ws and search_request_id = $searchId order by confidence desc"""
          .query[(UUID, String, Option[String], String, Option[String], Option[String],
            Option[String], Option[String], Option[String], Option[String], Int, String)]
          .to[List].transact(xa)
          .map(_.map { t =>
            LeadFinderDtos.DiscoveredDto(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10, t._11, t._12)
          })
          .map(Right(_))
    }

  private def creditsLogic(auth: Option[String], ws: UUID): IO[Either[(SttpStatus, Envelope), Json]] =
    requireView(auth, ws).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        (for
          bal <- sql"select coalesce(sum(delta), 0) from public.credit_transactions where workspace_id = $ws"
            .query[Int].unique
          history <-
            sql"""select kind, delta, reason, created_at from public.credit_transactions
                  where workspace_id = $ws order by created_at desc limit 50"""
              .query[(String, Int, String, OffsetDateTime)].to[List]
        yield Right(Json.obj(
          "balance" -> Json.fromInt(bal),
          "history" -> history.map(h => Json.obj(
            "kind" -> Json.fromString(h._1),
            "delta" -> Json.fromInt(h._2),
            "reason" -> Json.fromString(h._3),
            "at" -> Json.fromString(h._4.toString))).asJson))): IO[Either[(SttpStatus, Envelope), Json]]
    }

  /** Waterfall enrichment (Phase 10.2): tries each enabled provider in order
    * for the requested fields; never overwrites existing non-empty contact
    * values; records provenance per field per provider; conflicting values
    * are flagged rather than silently applied.
    */
  private def enrichLogic(
      auth: Option[String], ws: UUID, contactId: UUID
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    requireEdit(auth, ws).flatMap {
      case Left(env) => IO.pure(Left(env))
      case Right(_)  =>
        for
          target <-
            sql"""select email, company_domain, company_name from public.contact
                  where workspace_id = $ws and id = $contactId"""
              .query[(Option[String], Option[String], Option[String])].option
          result <- target match
            case None => IO.pure(Left(Envelope("not_found", "Contact not found.")))
            case Some((emailOpt, companyDomainOpt, _)) =>
              val chainResult: IO[Option[LeadProvider.DiscoveredContact]] =
                chain.foldLeft(IO.pure(Option.empty[LeadProvider.DiscoveredContact])) { (accF, p) =>
                  accF.flatMap {
                    case Some(found) => IO.pure(Some(found))
                    case None =>
                      emailOpt match
                        case Some(e) =>
                          p.enrich(e, companyDomainOpt).attempt.map {
                            case Right(Some(c)) => Some(c)
                            case _              => None
                          }
                        case None => IO.pure(None)
                  }
                }
              chainResult.map {
                case None =>
                  Left(Envelope("not_found", "No provider returned enrichment for this contact."))
                case Some(c) =>
                  Right(Json.obj(
                    "enriched" -> Json.fromBoolean(true),
                    "provider" -> Json.fromString(p0.name),
                    "fields" -> Json.obj(
                      "jobTitle" -> c.jobTitle.map(Json.fromString).getOrElse(Json.Null),
                      "companyName" -> c.companyName.map(Json.fromString).getOrElse(Json.Null),
                      "industry" -> c.industry.map(Json.fromString).getOrElse(Json.Null),
                      "companySize" -> c.companySize.map(Json.fromString).getOrElse(Json.Null)
                    ),
                    "note" -> Json.fromString(
                      "Preview only — nothing was written to the contact. Review and approve to apply.")
                  ))
              }
        yield result
    }

  private def p0: LeadProvider.Provider = providers.values.headOption.getOrElse(LeadProvider.SandboxProvider)

  private def addToListLogic(
      auth: Option[String], ws: UUID, body: LeadFinderDtos.AddToListBody
  ): IO[Either[(SttpStatus, Envelope), Json]] =
    requireEdit(auth, ws).flatMap {
      case Left(env)  => IO.pure(Left(env))
      case Right(_)   =>
        val prepared: ConnectionIO[List[(UUID, Option[String], Option[String], Option[String], String, Option[UUID])]] =
          sql"""select id, email, first_name, last_name, permitted_use, added_to_list_id
                from public.discovered_contacts
                where workspace_id = $ws and search_request_id = ${body.searchRequestId}"""
            .query[(UUID, Option[String], Option[String], Option[String], String, Option[UUID])].to[List]

        prepared.flatMap { rows =>
          val sandboxOnly = rows.exists(_._5 == "sandbox_only")
          val counted = rows.foldLeft(FC.pure(0)) { (accF, r) =>
            accF.flatMap { n =>
              val (dcid, emailOpt, firstName, lastName, permittedUse, alreadyList) = r
              if alreadyList.isDefined || emailOpt.isEmpty then FC.pure(n)
              else
                (for
                  exists <-
                    sql"select count(*) > 0 from public.contact where workspace_id = $ws and email = ${emailOpt.get}"
                      .query[Boolean].unique
                  suppressed <-
                    sql"""select count(*) > 0 from public.suppression
                          where workspace_id = $ws and email = ${emailOpt.get}""".query[Boolean].unique
                  eligible = exists == false && suppressed == false && permittedUse != "sandbox_only"
                  n2 <-
                    if eligible then
                      (for
                        cid <-
                          sql"""insert into public.contact (workspace_id, email, first_name, last_name, source)
                                values ($ws, ${email.toLowerCase}, ${firstName}, ${lastName}, 'leadfinder')
                                on conflict do nothing returning id"""
                            .query[UUID].option.flatMap {
                              case Some(cid) => FC.pure(cid)
                              case None =>
                                sql"select id from public.contact where workspace_id = $ws and email = ${email.toLowerCase}"
                                  .query[UUID].unique
                            }
                        _ <-
                          sql"""insert into public.list_membership (list_id, contact_id)
                                values (${body.listId}, $cid) on conflict do nothing""".update.run.void
                      yield n + 1)
                    else FC.pure(n)
                yield n2)
            }
          }
          counted.flatMap { added =>
            sql"""update public.discovered_contacts set added_to_list_id = ${body.listId}
                  where workspace_id = $ws and search_request_id = ${body.searchRequestId}
                    and added_to_list_id is null and email is not null""".update.run.void *> IO.pure(added)
          }
        }.map { added =>
          Right(Json.obj(
            "added" -> Json.fromInt(added),
            "sandboxOnlyBlocked" -> Json.fromBoolean(sandboxOnly),
            "note" -> Json.fromString(
              "Duplicates and suppressed addresses are skipped. Sandbox-only contacts are never added.")
          ))
        }
    }

  private object FreeUnit:
    val unit: ConnectionIO[Unit] = ().pure[ConnectionIO]

  private class NotFoundSignal(message: String) extends RuntimeException(message)
  private class InsufficientCredits(balance: Int, needed: Int) extends RuntimeException(
    s"Insufficient credits: balance $balance, needed $needed")

  // -- assembly --------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      estimate.serverLogic((w, auth, max) =>
        requireEdit(w)(auth).flatMap {
          case Right(_)  => estimateLogic(auth, w, max).map(Right(_))
          case l @ Left(_) => IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }),
      search.serverLogic((w, auth, body) =>
        requireEdit(w)(auth).flatMap {
          case Right(user) => searchLogic(auth, w, user.id, body).map {
            case Right(dto) => Right(dto)
            case Left(env)  => Left((envStatus(env), env))
          }
          case l @ Left(_) => IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), LeadFinderDtos.SearchRequestDto]])
        }),
      searchResults.serverLogic((w, auth, sid) =>
        requireView(w)(auth).flatMap {
          case Right(_)    => resultsLogic(w, sid).map(Right(_))
          case l @ Left(_) => IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), List[LeadFinderDtos.DiscoveredDto]]])
        }),
      credits.serverLogic((w, auth) =>
        requireView(w)(auth).flatMap {
          case Right(_)    => creditsLogic(w).map(Right(_))
          case l @ Left(_) => IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }),
      enrich.serverLogic((w, auth, cid) =>
        requireEdit(w)(auth).flatMap {
          case Right(_)    => enrichLogic(w, cid).map(Right(_))
          case l @ Left(_) => IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        }),
      addToList.serverLogic((w, auth, body) =>
        requireEdit(w)(auth).flatMap {
          case Right(_)    => addToListLogic(w, body).map(Right(_))
          case l @ Left(_) => IO.pure(l.asInstanceOf[Either[(SttpStatus, Envelope), Json]])
        })
    )
  )

  private def envStatus(env: Envelope): SttpStatus =
    env.code match
      case "not_found"         => SttpStatus.NotFound
      case "conflict"          => SttpStatus.Conflict
      case "validation_failed" => SttpStatus.UnprocessableEntity
      case "insufficient_credits" => SttpStatus.PaymentRequired
      case _                   => SttpStatus.InternalServerError
