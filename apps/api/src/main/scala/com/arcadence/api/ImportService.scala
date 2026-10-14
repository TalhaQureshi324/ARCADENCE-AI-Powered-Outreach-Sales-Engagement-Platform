package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import com.arcadence.core.config.ApiConfig
import doobie.Fragment
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*

import java.nio.file.{Files as JFiles, Paths, StandardCopyOption}
import java.time.OffsetDateTime
import java.util.UUID
import scala.collection.mutable

/** CSV import pipeline (Phase 3.2): durable jobs, bounded-memory processing,
  * duplicate strategies, idempotent retries and error reports.
  *
  * Files spool on the API host under [[spoolRoot]] (created 0700). A Supabase
  * Storage backend can be added behind the same [[ImportFileStore]] interface;
  * the pipeline logic is storage-agnostic.
  *
  * Idempotency: every processed row writes a `source_provenance` row keyed by
  * (import_job_id, row_number) with a UNIQUE index — a retried job skips rows
  * that already produced contacts, so at-least-once retries never duplicate
  * data.
  */
final class ImportService(
    val repos: ImportRepos,
    val leadsRepos: LeadsRepos,
    val transactor: Transactor[IO],
    val spoolRoot: java.nio.file.Path,
    val maxFileBytes: Long
):

  private def workspaceDir(workspaceId: UUID): java.nio.file.Path =
    java.nio.file.Paths.get(spoolRoot.toString, workspaceId.toString)

  /** Saves the uploaded bytes to the spool; returns the relative path. */
  def storeFile(
      workspaceId: UUID,
      fileName: String,
      bytes: Array[Byte]
  ): IO[String] =
    IO {
      val dir = workspaceDir(workspaceId)
      JFiles.createDirectories(dir)
      val safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_").take(120)
      val path = dir.resolve(s"${UUID.randomUUID()}-$safeName")
      JFiles.write(path, bytes)
      path.toString
    }

  def readBytes(workspaceId: UUID, filePath: String): IO[Array[Byte]] =
    IO {
      val resolved = java.nio.file.Paths.get(filePath).toAbsolutePath.normalize
      val allowed = workspaceDir(workspaceId).toAbsolutePath.normalize
      if !resolved.startsWith(allowed) then
        throw new IllegalArgumentException("Invalid file path")
      JFiles.readAllBytes(resolved)
    }

  /** Marks jobs left `running` by a previous process as failed. */
  def recoverAfterRestart: IO[Int] = repos.interruptAllRunning()

  // -- processing -----------------------------------------------------------------

  /** Valid mapping targets: fixed contact columns; other targets must match
    * defined custom-field names.
    */
  val AllowedFields: Set[String] = Set(
    "email",
    "first_name",
    "last_name",
    "job_title",
    "phone",
    "region",
    "timezone",
    "website",
    "notes",
    "company"
  )

  def start(
      workspaceId: UUID,
      actor: UUID,
      jobId: UUID,
      request: ImportService.StartRequest
  ): IO[Either[String, Unit]] =
    if !Set("skip", "update", "reject").contains(request.duplicateStrategy) then
      IO.pure(Left("duplicateStrategy must be skip, update or reject"))
    else if !request.mapping.values.exists(_.trim.toLowerCase == "email") then
      IO.pure(Left("The mapping must include an email column."))
    else
      val parsedKeys: Either[String, List[(Int, String)]] =
        request.mapping.toList.traverse { (k, v) =>
          k.trim.toIntOption
            .map(idx => idx -> v.trim.toLowerCase)
            .toRight(s"Invalid column index: $k")
        }
      parsedKeys match
        case Left(error)  => IO.pure(Left(error))
        case Right(pairs) =>
          val clean = pairs.toMap
          val unknown =
            clean.values.filterNot(v => AllowedFields.contains(v)).toSet
          if unknown.isEmpty then
            enqueue(workspaceId, actor, jobId, clean, request)
          else
            leadsRepos.knownFieldNames(workspaceId).flatMap { known =>
              val stillUnknown = unknown.diff(known)
              if stillUnknown.nonEmpty then
                IO.pure(
                  Left(
                    s"Unknown target fields: ${stillUnknown.mkString(", ")}. Define them as custom fields first."
                  )
                )
              else enqueue(workspaceId, actor, jobId, clean, request)
            }

  private def enqueue(
      workspaceId: UUID,
      actor: UUID,
      jobId: UUID,
      cleanMapping: Map[Int, String],
      request: ImportService.StartRequest
  ): IO[Either[String, Unit]] =
    val mappingJson = Json.fromJsonObject(
      io.circe.JsonObject.fromIterable(
        cleanMapping.view.map { (idx, field) =>
          idx.toString -> Json.fromString(field)
        }.toIterable
      )
    )
    val defaultsJson = Json.fromJsonObject(
      io.circe.JsonObject.fromIterable(
        request.defaults.view.map { (k, v) =>
          k -> Json.fromString(v)
        }.toIterable
      )
    )
    repos
      .setMapping(
        workspaceId,
        jobId,
        mappingJson,
        request.duplicateStrategy,
        defaultsJson,
        request.targetListId
      )
      .flatMap {
        case false => IO.pure(Left("This job cannot be started (wrong state)."))
        case true  => runFiber(workspaceId, actor, jobId).as(Right(()))
      }

  /** Spawns the processing fiber; failures land in the job row. */
  private def runFiber(workspaceId: UUID, actor: UUID, jobId: UUID): IO[Unit] =
    repos.transitionJob(workspaceId, jobId, List("queued"), "running").flatMap {
      case false => IO.unit
      case true  =>
        process(workspaceId, actor, jobId)
          .handleErrorWith(t =>
            repos.failJob(
              workspaceId,
              jobId,
              Option(t.getMessage).getOrElse("processing failed").take(300)
            )
          )
          .start
          .void
    }

  def process(workspaceId: UUID, actor: UUID, jobId: UUID): IO[Unit] =
    for
      jobOpt <- repos.getJob(workspaceId, jobId)
      job <- jobOpt match
        case None => IO.raiseError(new IllegalStateException("job vanished"))
        case Some(job) => IO.pure(job)
      bytes <- readBytes(workspaceId, job.filePath)
      parsed = CsvReader.parseAll(bytes, maxRows = 200_000)
      result <- parsed match
        case Left(error) => repos.failJob(workspaceId, jobId, error).as(())
        case Right((header, rows)) =>
          val mapping = jsonParse(job.columnMapping.noSpaces).toOption
            .flatMap(_.asObject)
            .map { obj =>
              obj.toMap.view.flatMap { (k, v) =>
                for
                  idx <- k.trim.toIntOption
                  name <- v.asString
                yield idx -> name
              }.toMap
            }
            .getOrElse(Map.empty[Int, String])
          val defaults = jsonParse(job.defaults.noSpaces).toOption
            .flatMap(_.asObject)
            .map { obj =>
              obj.toMap.view.flatMap { (k, v) => v.asString.map(k -> _) }.toMap
            }
            .getOrElse(Map.empty[String, String])
          repos.setTotalRows(workspaceId, jobId, rows.size) *>
            processRows(
              workspaceId,
              actor,
              jobId,
              job.duplicateStrategy,
              mapping,
              defaults,
              job.targetListId,
              rows
            )
    yield result

  /** Batched pipeline: existence checks, inserts and provenance happen in bulk
    * per 500-row batch. Idempotent retries come from the UNIQUE (import_job_id,
    * row_number) provenance marker.
    */
  def processRows(
      workspaceId: UUID,
      actor: UUID,
      jobId: UUID,
      strategy: String,
      mapping: Map[Int, String],
      defaults: Map[String, String],
      targetListId: Option[UUID],
      rows: List[List[String]]
  ): IO[Unit] =
    val emailCol = mapping
      .collectFirst { case (idx, f) if f == "email" => idx }
      .getOrElse(-1)
    val seen = mutable.Set.empty[String]

    def cell(row: List[String], field: String): Option[String] =
      mapping.collectFirst { case (idx, f) if f == field => idx }.flatMap {
        idx =>
          row
            .lift(idx)
            .map(_.trim)
            .filter(_.nonEmpty)
            .orElse(defaults.get(field).map(_.trim).filter(_.nonEmpty))
      }

    val emailRegex = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"

    val batches = rows.zipWithIndex.grouped(500).toList
    batches
      .foldLeftM[IO, (Int, Int, Int, Int, Int, Int)]((0, 0, 0, 0, 0, 0)) {
        (acc, batch) =>
          val invalidErrs =
            scala.collection.mutable.ListBuffer.empty[(Int, String)]
          val dupErrs = scala.collection.mutable.ListBuffer.empty[(Int, String)]
          var invalid = 0
          var dupInFile = 0

          val classified = List.newBuilder[(Int, String, List[String])]
          batch.foreach { (row, offset) =>
            val rowNumber = offset + 2
            val emailOpt = if emailCol >= 0 then
              row.lift(emailCol).map(_.trim).filter(_.nonEmpty)
            else None
            emailOpt match
              case None =>
                invalid += 1
                invalidErrs += ((rowNumber, "missing email"))
              case Some(rawEmail) =>
                val email = rawEmail.toLowerCase
                if !email.matches(emailRegex) then
                  invalid += 1
                  invalidErrs += ((rowNumber, s"invalid email: $rawEmail"))
                else if seen.contains(email) then
                  dupInFile += 1
                  if strategy == "reject" then
                    dupErrs += ((rowNumber, s"duplicate email in file: $email"))
                else
                  seen += email
                  classified += ((rowNumber, email, row))
          }

          val candidates = classified.result()
          val emails = candidates.map(_._2)
          val existingLookup: IO[Map[String, UUID]] =
            if emails.isEmpty then IO.pure(Map.empty)
            else
              val inList = emails
                .map(e => fr"$e")
                .reduceOption((a, b) => a ++ fr", " ++ b)
                .getOrElse(Fragment.empty)
              (fr"select email, id from public.contact where workspace_id = $workspaceId and email in (" ++ inList ++ fr")")
                .query[(String, UUID)]
                .to[List]
                .transact(transactor)
                .map(_.toMap)

          existingLookup.flatMap { existing =>
            val toInsert = List.newBuilder[(Int, String, List[String])]
            val toUpdate = List.newBuilder[(UUID, Int, List[String])]
            var skippedExisting = 0
            var dupExisting = 0
            candidates.foreach { (rowNumber, email, row) =>
              existing.get(email) match
                case Some(contactId) =>
                  strategy match
                    case "skip"   => skippedExisting += 1
                    case "update" => toUpdate += ((contactId, rowNumber, row))
                    case "reject" =>
                      dupExisting += 1
                      dupErrs += ((
                        rowNumber,
                        s"duplicate of existing contact: $email"
                      ))
                case None => toInsert += ((rowNumber, email, row))
            }

            val companyNames = toInsert
              .result()
              .map { (_, _, row) => cell(row, "company") }
              .flatten
              .distinct
            val companiesIO: IO[Map[String, UUID]] =
              if companyNames.isEmpty then IO.pure(Map.empty)
              else
                companyNames
                  .traverse { name =>
                    leadsRepos.listCompanies(workspaceId, Some(name)).flatMap {
                      case hit :: _ => IO.pure(Some(name -> hit.id))
                      case Nil      =>
                        leadsRepos
                          .createCompany(workspaceId, name, None, None)
                          .map(_.map(c => name -> c.id))
                    }
                  }
                  .map(_.flatten.toMap)

            companiesIO
              .flatMap { nameToCompanyId =>
                val inserts = toInsert.result()
                val updates = toUpdate.result()

                val insertedIO: IO[(Int, Map[String, UUID])] =
                  if inserts.isEmpty then IO.pure((0, Map.empty))
                  else
                    val values: List[Fragment] = inserts.map {
                      (rowNumber, email, row) =>
                        val display = cell(row, "email")
                        val companyUuid: Option[UUID] =
                          cell(row, "company").flatMap(nameToCompanyId.get)
                        fr"($workspaceId, $companyUuid, $email, $display," ++
                          fr"${cell(row, "first_name")}, ${cell(row, "last_name")}, ${cell(row, "job_title")}," ++
                          fr"${cell(row, "website")}, ${cell(row, "phone")}, ${cell(row, "region")}," ++
                          fr"${cell(row, "timezone")}, ${cell(row, "notes")}, '{}'::jsonb)"
                    }
                    (fr"insert into public.contact (workspace_id, company_id, email, email_display, first_name, last_name, job_title, website, phone, region, timezone, notes, custom_fields) values " ++
                      joinFr(
                        values
                      ) ++ fr" on conflict do nothing returning email, id")
                      .query[(String, UUID)]
                      .to[List]
                      .transact(transactor)
                      .map(pairs => (pairs.size, pairs.toMap))

                insertedIO.flatMap { (inserted, emailToId) =>
                  val updatesIO: IO[Int] =
                    if updates.isEmpty then IO.pure(0)
                    else
                      updates
                        .traverse { (contactId, _, row) =>
                          val sets = List.newBuilder[Fragment]
                          List(
                            "first_name",
                            "last_name",
                            "job_title",
                            "phone",
                            "region",
                            "timezone",
                            "website",
                            "notes"
                          )
                            .foreach { field =>
                              cell(row, field).foreach { v =>
                                sets += Fragment.const(s"$field = ") ++ fr"$v"
                              }
                            }
                          val assignments = sets.result()
                          if assignments.isEmpty then IO.pure(0)
                          else
                            (fr"update public.contact set " ++ joinFr(
                              assignments
                            ) ++
                              fr"where workspace_id = $workspaceId and id = $contactId").update.run
                              .transact(transactor)
                        }
                        .map(_.sum)

                  updatesIO.flatMap { updated =>
                    val provRows = List.newBuilder[Fragment]
                    inserts.foreach { (rowNumber, email, _) =>
                      emailToId.get(email).foreach { contactId =>
                        provRows += fr"($workspaceId, $contactId, 'import', $jobId, " ++
                          fr"${Json.obj("row" -> Json.fromInt(rowNumber)).noSpaces}::jsonb, $rowNumber)"
                      }
                    }
                    updates.foreach { (contactId, rowNumber, _) =>
                      provRows += fr"($workspaceId, $contactId, 'import', $jobId, " ++
                        fr"${Json.obj("row" -> Json.fromInt(rowNumber)).noSpaces}::jsonb, $rowNumber)"
                    }
                    val provRowsFinal = provRows.result()
                    val provIO: IO[Unit] =
                      if provRowsFinal.isEmpty then IO.unit
                      else
                        (fr"insert into public.source_provenance (workspace_id, contact_id, source, import_job_id, details, row_number) values " ++
                          joinFr(
                            provRowsFinal
                          ) ++ fr" on conflict (import_job_id, row_number) do nothing").update.run.void
                          .transact(transactor)

                    val listIO: IO[Unit] =
                      targetListId match
                        case None         => IO.unit
                        case Some(listId) =>
                          val ids = inserts.flatMap { (_, email, _) =>
                            emailToId.get(email)
                          }
                          if ids.isEmpty then IO.unit
                          else
                            leadsRepos
                              .addListMembers(workspaceId, actor, listId, ids)
                              .void

                    val errIO: IO[Unit] =
                      val all = invalidErrs.result() ++ dupErrs.result()
                      if all.isEmpty then IO.unit
                      else
                        val values = all.map { (rowNumber, reason) =>
                          fr"($jobId, $rowNumber, $reason)"
                        }
                        (fr"insert into public.import_error (job_id, row_number, reason) values " ++
                          joinFr(values)).update.run.void.transact(transactor)

                    for _ <- provIO; _ <- listIO; _ <- errIO
                    yield (
                      inserted,
                      updated,
                      skippedExisting,
                      invalid,
                      dupInFile + dupExisting,
                      0
                    )
                  }
                }
              }
              .map { (ins, upd, skip, inv, dup, _) =>
                val delta = (ins + upd + skip + inv, ins, upd, skip, inv, dup)
                (
                  acc._1 + delta._1,
                  acc._2 + delta._2,
                  acc._3 + delta._3,
                  acc._4 + delta._4,
                  acc._5 + delta._5,
                  acc._6 + delta._6
                )
              }
          }
      }
      .flatMap { totals =>
        repos.bumpProgress(
          workspaceId,
          jobId,
          totals._1,
          totals._2,
          totals._3,
          totals._4,
          totals._5,
          totals._6
        ) *>
          repos.finishJob(workspaceId, jobId)
      }

  private def joinFr(fs: List[Fragment]): Fragment =
    fs.reduceOption((a, b) => a ++ fr", " ++ b).getOrElse(Fragment.empty)

  private def chr(ignored: String): Char = '\\'

object ImportService:
  final case class StartRequest(
      mapping: Map[String, String],
      duplicateStrategy: String,
      defaults: Map[String, String] = Map.empty,
      targetListId: Option[UUID] = None
  )

  def defaultSpoolRoot: java.nio.file.Path =
    java.nio.file.Paths
      .get(System.getProperty("java.io.tmpdir"), "arcadence-imports")
