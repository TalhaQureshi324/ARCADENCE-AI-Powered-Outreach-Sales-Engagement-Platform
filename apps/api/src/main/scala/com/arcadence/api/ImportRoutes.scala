package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.api.ApiError.Envelope
import com.arcadence.api.Permissions.Permission
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import org.http4s.HttpRoutes
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.StatusCode as SttpStatus
import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.util.UUID

object ImportDtos:
  import io.circe.{Decoder, Encoder}
  import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
  import sttp.tapir.Schema

  final case class ImportJobDto(
      id: UUID,
      fileName: String,
      status: String,
      duplicateStrategy: String,
      totalRows: Int,
      processedRows: Int,
      insertedRows: Int,
      updatedRows: Int,
      skippedRows: Int,
      invalidRows: Int,
      duplicateRows: Int,
      errorMessage: Option[String],
      createdAt: String,
      updatedAt: String
  )
  object ImportJobDto:
    given Encoder[ImportJobDto] = deriveEncoder
    given Decoder[ImportJobDto] = deriveDecoder
    given Schema[ImportJobDto] = Schema.derived

  final case class UploadResult(
      job: ImportJobDto,
      headers: List[String],
      preview: List[List[String]]
  )
  object UploadResult:
    given Encoder[UploadResult] = deriveEncoder
    given Decoder[UploadResult] = deriveDecoder
    given Schema[UploadResult] = Schema.derived

  final case class StartImportRequest(
      mapping: Map[String, String],
      duplicateStrategy: String,
      defaults: Option[Map[String, String]] = None,
      targetListId: Option[UUID] = None
  )
  object StartImportRequest:
    given Encoder[StartImportRequest] = deriveEncoder
    given Decoder[StartImportRequest] = deriveDecoder
    given Schema[StartImportRequest] = Schema.derived

/** CSV import API (Phase 3.2): upload → header/preview → mapping+start →
  * progress/history/error-report, with idempotent retry.
  */
final class ImportRoutes(
    authenticator: Authenticator,
    repos: ImportRepos,
    leadsRepos: LeadsRepos,
    authorizer: Authorizer,
    service: ImportService
):
  import ImportDtos.*

  private val errorOut = statusCode.and(jsonBody[Envelope])

  private def failed(error: ApiError): (SttpStatus, Envelope) =
    (SttpStatus(error.status.code), error.toEnvelope)

  private def verifyBearer(
      authorization: Option[String]
  ): IO[Either[(SttpStatus, Envelope), AuthenticatedUser]] =
    authorization match
      case Some(token) if token.stripPrefix("Bearer ").nonEmpty =>
        authenticator
          .verify(token.stripPrefix("Bearer ").trim)
          .map(
            _.leftMap(e =>
              (SttpStatus.Unauthorized, Envelope(e.code, e.message))
            )
          )
      case _ => IO.pure(Left(failed(ApiError.Unauthorized)))

  private def requirePermission(
      authorization: Option[String],
      workspaceId: UUID,
      permission: Permission
  ): IO[Either[(SttpStatus, Envelope), (AuthenticatedUser, Membership)]] =
    verifyBearer(authorization).flatMap {
      case Left(envelope) => IO.pure(Left(envelope))
      case Right(user)    =>
        authorizer.requirePermission(user, workspaceId, permission).map {
          case Left(error)       => Left(failed(error))
          case Right(membership) => Right((user, membership))
        }
    }

  private def jobDto(j: ImportJobView): ImportJobDto =
    ImportJobDto(
      id = j.id,
      fileName = j.fileName,
      status = j.status,
      duplicateStrategy = j.duplicateStrategy,
      totalRows = j.totalRows,
      processedRows = j.processedRows,
      insertedRows = j.insertedRows,
      updatedRows = j.updatedRows,
      skippedRows = j.skippedRows,
      invalidRows = j.invalidRows,
      duplicateRows = j.duplicateRows,
      errorMessage = j.errorMessage,
      createdAt = j.createdAt.toString,
      updatedAt = j.updatedAt.toString
    )

  private val MaxUploadBytes = 25L * 1024 * 1024

  private val upload = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "imports" / "upload"
    )
    .in(header[Option[String]]("Authorization"))
    .in(query[String]("fileName"))
    .in(byteArrayBody)
    .out(jsonBody[UploadResult])
    .errorOut(errorOut)

  private val startImport = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "imports" / path[UUID]("jobId") / "start"
    )
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[StartImportRequest])
    .out(jsonBody[ImportJobDto])
    .errorOut(errorOut)

  private val listImports = endpoint.get
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "imports")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[List[ImportJobDto]])
    .errorOut(errorOut)

  private val getImport = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "imports" / path[UUID]("jobId")
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[ImportJobDto])
    .errorOut(errorOut)

  private val errorReport = endpoint.get
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "imports" / path[UUID]("jobId") / "errors.csv"
    )
    .in(header[Option[String]]("Authorization"))
    .out(byteArrayBody)
    .errorOut(errorOut)

  private val retryImport = endpoint.post
    .in(
      "api" / "v1" / "workspaces" / path[UUID](
        "workspaceId"
      ) / "imports" / path[UUID]("jobId") / "retry"
    )
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[ImportJobDto])
    .errorOut(errorOut)

  // -- logic --------------------------------------------------------------------

  private def uploadLogic(
      authorization: Option[String],
      workspaceId: UUID,
      fileName: String,
      bytes: Array[Byte]
  ): IO[Either[(SttpStatus, Envelope), UploadResult]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          if bytes.isEmpty then
            IO.pure(
              Left(failed(ApiError.Invalid("The uploaded file is empty.")))
            )
          else if bytes.length > MaxUploadBytes then
            IO.pure(
              Left(failed(ApiError.Invalid("Files are limited to 25 MB.")))
            )
          else
            CsvReader.parseAll(bytes) match
              case Left(error) => IO.pure(Left(failed(ApiError.Invalid(error))))
              case Right((headers, previewRows)) =>
                for
                  path <- service.storeFile(workspaceId, fileName, bytes)
                  job <- repos.createJob(
                    workspaceId,
                    user.id,
                    path,
                    fileName.take(200),
                    bytes.length.toLong
                  )
                yield Right(
                  UploadResult(
                    jobDto(job),
                    headers,
                    previewRows.take(5)
                  )
                )
      }

  private def startLogic(
      authorization: Option[String],
      workspaceId: UUID,
      jobId: UUID,
      request: StartImportRequest
  ): IO[Either[(SttpStatus, Envelope), ImportJobDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          service
            .start(
              workspaceId,
              user.id,
              jobId,
              ImportService.StartRequest(
                mapping = request.mapping,
                duplicateStrategy = request.duplicateStrategy,
                defaults = request.defaults.getOrElse(Map.empty),
                targetListId = request.targetListId
              )
            )
            .flatMap {
              case Left(error) => IO.pure(Left(failed(ApiError.Invalid(error))))
              case Right(_)    =>
                repos.getJob(workspaceId, jobId).map {
                  case None    => Left(failed(ApiError.NotFound("import job")))
                  case Some(j) => Right(jobDto(j))
                }
            }
      }

  private def listLogic(
      authorization: Option[String],
      workspaceId: UUID
  ): IO[Either[(SttpStatus, Envelope), List[ImportJobDto]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.listJobs(workspaceId).map(js => Right(js.map(jobDto)))
      }

  private def getLogic(
      authorization: Option[String],
      workspaceId: UUID,
      jobId: UUID
  ): IO[Either[(SttpStatus, Envelope), ImportJobDto]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.getJob(workspaceId, jobId).map {
            case None    => Left(failed(ApiError.NotFound("import job")))
            case Some(j) => Right(jobDto(j))
          }
      }

  private def errorReportLogic(
      authorization: Option[String],
      workspaceId: UUID,
      jobId: UUID
  ): IO[Either[(SttpStatus, Envelope), Array[Byte]]] =
    requirePermission(authorization, workspaceId, Permission.ViewWorkspace)
      .flatMap {
        case Left(envelope) => IO.pure(Left(envelope))
        case Right(_)       =>
          repos.getJob(workspaceId, jobId).flatMap {
            case None => IO.pure(Left(failed(ApiError.NotFound("import job"))))
            case Some(job) =>
              repos.errorsForJob(jobId).map { rows =>
                val csv = CsvReader.renderCsv(
                  List(List("row_number", "reason")) ++
                    rows.map(r => List(r.rowNumber.toString, r.reason))
                )
                Right(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))
              }
          }
      }

  private def retryLogic(
      authorization: Option[String],
      workspaceId: UUID,
      jobId: UUID
  ): IO[Either[(SttpStatus, Envelope), ImportJobDto]] =
    requirePermission(authorization, workspaceId, Permission.EditContent)
      .flatMap {
        case Left(envelope)   => IO.pure(Left(envelope))
        case Right((user, _)) =>
          repos.getJob(workspaceId, jobId).flatMap {
            case None => IO.pure(Left(failed(ApiError.NotFound("import job"))))
            case Some(job) =>
              job.status match
                case "running" | "queued" | "created" =>
                  IO.pure(
                    Left(
                      failed(
                        ApiError
                          .Conflict("This job is not in a retryable state.")
                      )
                    )
                  )
                case _ =>
                  // Idempotent: rows that already produced contacts are skipped
                  // via their provenance (job_id, row_number) markers.
                  repos
                    .transitionJob(
                      workspaceId,
                      jobId,
                      List("failed", "completed"),
                      "queued"
                    )
                    .flatMap {
                      case false =>
                        IO.pure(
                          Left(
                            failed(
                              ApiError.Conflict(
                                "This job is not in a retryable state."
                              )
                            )
                          )
                        )
                      case true =>
                        repos
                          .transitionJob(
                            workspaceId,
                            jobId,
                            List("queued"),
                            "running"
                          )
                          .flatMap {
                            case false =>
                              IO.pure(
                                Left(
                                  failed(
                                    ApiError.Conflict("Retry race — try again.")
                                  )
                                )
                              )
                            case true =>
                              service
                                .process(workspaceId, user.id, jobId)
                                .handleErrorWith(t =>
                                  repos.failJob(
                                    workspaceId,
                                    jobId,
                                    Option(t.getMessage)
                                      .getOrElse("processing failed")
                                      .take(300)
                                  )
                                )
                                .start
                              repos.getJob(workspaceId, jobId).map {
                                case None =>
                                  Left(failed(ApiError.NotFound("import job")))
                                case Some(j) => Right(jobDto(j))
                              }
                          }
                    }
          }
      }

  // -- assembly --------------------------------------------------------------------

  val http: HttpRoutes[IO] = Http4sServerInterpreter[IO]().toRoutes(
    List[ServerEndpoint[Fs2Streams[IO], IO]](
      upload.serverLogic((workspaceId, auth, fileName, bytes) =>
        uploadLogic(auth, workspaceId, fileName, bytes)
      ),
      startImport.serverLogic((workspaceId, jobId, auth, req) =>
        startLogic(auth, workspaceId, jobId, req)
      ),
      listImports.serverLogic((workspaceId, auth) =>
        listLogic(auth, workspaceId)
      ),
      getImport.serverLogic((workspaceId, jobId, auth) =>
        getLogic(auth, workspaceId, jobId)
      ),
      errorReport.serverLogic((workspaceId, jobId, auth) =>
        errorReportLogic(auth, workspaceId, jobId)
      ),
      retryImport.serverLogic((workspaceId, jobId, auth) =>
        retryLogic(auth, workspaceId, jobId)
      )
    )
  )
