package com.arcadence.api

import cats.data.Kleisli
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import com.arcadence.core.build.BuildInfo
import com.arcadence.core.config.ApiConfig
import com.arcadence.core.health.ServiceIdentity
import com.comcast.ip4s.*
import doobie.implicits.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, HttpRoutes}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import scala.concurrent.duration.*

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    ApiConfig.fromEnv(sys.env) match
      case Left(error) =>
        IO.consoleForIO
          .errorln(s"arcadence-api: invalid configuration: $error") *> IO.pure(
          ExitCode.Error
        )

      case Right(config) =>
        given LoggerFactory[IO] = Slf4jFactory.create[IO]
        val logger = LoggerFactory[IO].getLogger
        val identity = ServiceIdentity("arcadence-api", BuildInfo.version)

        val applyLogLevel: IO[Unit] = IO {
          val root = org.slf4j.LoggerFactory
            .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            .asInstanceOf[ch.qos.logback.classic.Logger]
          root.setLevel(ch.qos.logback.classic.Level.valueOf(config.logLevel))
        }

        val resources: IO[(doobie.Transactor[IO], Authenticator)] =
          for
            transactor <- Db.transactor(config.databaseUrl)
            jwksUri <- IO.fromEither(
              org.http4s.Uri
                .fromString(config.jwksUrl)
                .leftMap(e =>
                  new IllegalArgumentException(
                    s"Invalid SUPABASE_JWKS_URL: ${e.message}"
                  )
                )
            )
            authenticator = SupabaseJwtAuthenticator(
              config.jwtIssuer,
              config.jwtAudience,
              jwksUri
            )
          yield (transactor, authenticator)

        resources.flatMap { (transactor, authenticator) =>
          val repositories = new WorkspaceRepos(transactor)
          val authorizer = new Authorizer(transactor)
          val accountRepos = new EmailAccountRepos(transactor)
          val workspaceRoutes = new WorkspaceRoutes(
            authenticator = authenticator,
            repos = repositories,
            authorizer = authorizer,
            appOrigin = "http://localhost:3000"
          )

          // Provider adapters (Phase 2.2): Gmail when OAuth credentials exist.
          org.http4s.ember.client.EmberClientBuilder
            .default[IO]
            .withUserAgent(
              org.http4s.headers.`User-Agent`(
                org.http4s
                  .ProductId(s"arcadence-api/${BuildInfo.version}", None)
              )
            )
            .build
            .use(httpClient =>
              ProviderRegistry.build(config, httpClient).flatMap { registry =>
                val emailAccountRoutes = new EmailAccountRoutes(
                  authenticator = authenticator,
                  repos = accountRepos,
                  authorizer = authorizer,
                  registry = Some(registry),
                  revokeCredentials = (providerId, workspaceId, accountId) =>
                    registry.adapter(providerId) match
                      case None          => IO.unit
                      case Some(adapter) =>
                        adapter
                          .revokeAccess(
                            registry.credentialLoader(
                              accountRepos,
                              workspaceId,
                              accountId,
                              providerId
                            )
                          )
                          .attempt
                          .void
                )
                val gmailRoutes = new GmailRoutes(
                  authenticator = authenticator,
                  repos = accountRepos,
                  authorizer = authorizer,
                  registry = registry
                )
                val microsoftRoutes = new MicrosoftRoutes(
                  authenticator = authenticator,
                  repos = accountRepos,
                  authorizer = authorizer,
                  registry = registry
                )
                val leadsRoutes = new ContactRoutes(
                  authenticator = authenticator,
                  repos = new LeadsRepos(transactor),
                  authorizer = authorizer
                )

                // CSV import pipeline (Phase 3.2)
                val leadsReposShared = new LeadsRepos(transactor)
                val importRepos = new ImportRepos(transactor)
                val importService = new ImportService(
                  repos = importRepos,
                  leadsRepos = leadsReposShared,
                  transactor = transactor,
                  spoolRoot = sys.env
                    .get("IMPORT_SPOOL_DIR")
                    .map(java.nio.file.Paths.get(_))
                    .getOrElse(ImportService.defaultSpoolRoot),
                  maxFileBytes = 25L * 1024 * 1024
                )
                val importRoutes = new ImportRoutes(
                  authenticator = authenticator,
                  repos = importRepos,
                  leadsRepos = leadsReposShared,
                  authorizer = authorizer,
                  service = importService
                )

                // Saved segments, exports, bulk actions, merge (Phase 3.4)
                val segmentRepos =
                  new SegmentRepos(transactor, leadsReposShared)
                val segmentRoutes = new SegmentRoutes(
                  authenticator = authenticator,
                  repos = segmentRepos,
                  leadsRepos = leadsReposShared,
                  authorizer = authorizer
                )

                // Campaign domain (Phase 4.1) + composer (Phase 4.2)
                val campaignRepos = new CampaignRepos(transactor)
                val campaignRoutes = new CampaignRoutes(
                  authenticator = authenticator,
                  repos = campaignRepos,
                  leadsRepos = leadsReposShared,
                  emailAccounts = accountRepos,
                  authorizer = authorizer
                )
                val crmRoutes = new CrmRoutes(
                  authenticator = authenticator,
                  xa = transactor
                )
                val deliverabilityRoutes = new DeliverabilityRoutes(
                  authenticator = authenticator,
                  xa = transactor,
                  resolver = Deliverability.jndiResolver
                )
                val analyticsRoutes = new AnalyticsRoutes(
                  authenticator = authenticator,
                  xa = transactor
                )
                val inboxRoutes = new InboxRoutes(
                  authenticator = authenticator,
                  xa = transactor,
                  sync = new InboxSyncService(
                    transactor,
                    Some(registry),
                    accountRepos
                  ),
                  registry = Some(registry),
                  emailAccounts = accountRepos
                )
                val composerRoutes = new ComposerRoutes(
                  authenticator = authenticator,
                  repos = campaignRepos,
                  leadsRepos = leadsReposShared,
                  emailAccounts = accountRepos,
                  authorizer = authorizer,
                  registry = Some(registry)
                )

                val dbReady: IO[Boolean] =
                  sql"select 1"
                    .query[Int]
                    .unique
                    .transact(transactor)
                    .attempt
                    .map(_.isRight)

                val health = new HealthRoutes(identity, dbCheck = Some(dbReady))

                // Browser calls (localhost:3000) hit WorkspaceRoutes directly —
                // CORS must cover the whole API surface, not just health.
                import org.http4s.Method.*
                val corsPolicy =
                  org.http4s.server.middleware.CORS.policy.withAllowOriginAll
                    .withAllowCredentials(false)
                    .withAllowMethodsIn(
                      Set(GET, POST, PATCH, PUT, DELETE, OPTIONS)
                    )
                    .withAllowHeadersIn(
                      Set(
                        org.typelevel.ci.CIString("Authorization"),
                        org.typelevel.ci.CIString("Content-Type"),
                        org.typelevel.ci.CIString("Idempotency-Key")
                      )
                    )
                // Hardening: per-caller rate limiting + security headers (Phase 1.4).
                val hardenedRoutes: HttpRoutes[IO] =
                  RateLimit(config.rateLimitPerMinute)(
                    SecurityHeaders(
                      workspaceRoutes.http <+> emailAccountRoutes.http <+> gmailRoutes.http <+> microsoftRoutes.http
                        <+> segmentRoutes.http <+> campaignRoutes.http <+> composerRoutes.http <+> leadsRoutes.http <+> inboxRoutes.http <+> analyticsRoutes.http <+> deliverabilityRoutes.http <+> crmRoutes.http <+> importRoutes.http
                    )
                  )

                val allRoutes: HttpRoutes[IO] =
                  corsPolicy(health.http <+> hardenedRoutes)

                val routes =
                  new RequestContext(logger)(
                    new ErrorEnvelope(logger)(allRoutes)
                  )

                // JSON error envelope for unmatched paths too (plain Router would
                // return text). Unmatched paths bypass RequestContext, so a request
                // id is generated here when absent.
                val app: HttpApp[IO] = Kleisli { req =>
                  val requestId = Option(ApiResponses.requestIdOf(req))
                    .filter(_.nonEmpty)
                    .getOrElse(java.util.UUID.randomUUID().toString)
                  routes(req)
                    .getOrElseF(IO.pure(ApiResponses.notFound(requestId)))
                }

                applyLogLevel *>
                  importService.recoverAfterRestart.flatMap(n =>
                    logger.info(
                      s"import recovery: $n interrupted job(s) marked failed"
                    )
                  ) *>
                  logger.info(
                    s"arcadence-api ${BuildInfo.version} starting on port ${config.port}"
                  ) *>
                  EmberServerBuilder
                    .default[IO]
                    .withHost(host"0.0.0.0")
                    // API_PORT is validated to [1, 65535] by ApiConfig; fallback unreachable.
                    .withPort(Port.fromInt(config.port).getOrElse(port"8080"))
                    .withHttpApp(app)
                    .withShutdownTimeout(5.seconds)
                    .build
                    .use(_ =>
                      logger.info(
                        s"arcadence-api ${BuildInfo.version} listening on port ${config.port}"
                      ) *> IO.never
                    )
                    .as(ExitCode.Success)
              }
            )
        }
