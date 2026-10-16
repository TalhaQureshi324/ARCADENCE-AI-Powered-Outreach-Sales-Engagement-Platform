package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json

import java.time.OffsetDateTime
import java.util.UUID

/** Persistence for CSV imports (Phase 3.2). */
final case class ImportJobView(
    id: UUID,
    workspaceId: UUID,
    filePath: String,
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
    columnMapping: Json,
    defaults: Json,
    targetListId: Option[UUID],
    createdAt: OffsetDateTime,
    updatedAt: OffsetDateTime
)

final class ImportRepos(transactor: Transactor[IO]):
  private def parseJson(text: String): Json =
    io.circe.parser.parse(text).getOrElse(Json.Null)

  private val jobSelect = fr"""
      select id, workspace_id, file_path, file_name, status, duplicate_strategy,
             total_rows, processed_rows, inserted_rows, updated_rows,
             skipped_rows, invalid_rows, duplicate_rows, error_message,
             coalesce(column_mapping::text, '{}'), coalesce(defaults::text, '{}'), target_list_id, created_at, updated_at
      from public.import_job
    """

  implicit val jobRead: Read[ImportJobView] = Read[
    (
        UUID,
        UUID,
        String,
        String,
        String,
        String,
        Int,
        Int,
        Int,
        Int,
        Int,
        Int,
        Int,
        Option[String],
        String,
        String,
        Option[UUID],
        OffsetDateTime,
        OffsetDateTime
    )
  ].map { t =>
    ImportJobView(
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
      t._13,
      t._14,
      parseJson(t._15),
      parseJson(t._16),
      t._17,
      t._18,
      t._19
    )
  }

  def createJob(
      workspaceId: UUID,
      actor: UUID,
      filePath: String,
      fileName: String,
      fileSize: Long
  ): IO[ImportJobView] =
    sql"""
      insert into public.import_job (workspace_id, created_by, file_path, file_name, file_size)
      values ($workspaceId, $actor, $filePath, $fileName, $fileSize)
    """.update
      .withUniqueGeneratedKeys[UUID]("id")
      .transact(transactor)
      .flatMap { id =>
        (jobSelect ++ fr"where id = $id")
          .query[ImportJobView]
          .unique
          .transact(transactor)
      }

  def getJob(workspaceId: UUID, jobId: UUID): IO[Option[ImportJobView]] =
    (jobSelect ++ fr"where workspace_id = $workspaceId and id = $jobId")
      .query[ImportJobView]
      .option
      .transact(transactor)

  def listJobs(workspaceId: UUID, limit: Int = 50): IO[List[ImportJobView]] =
    (jobSelect ++ fr"where workspace_id = $workspaceId order by created_at desc limit $limit")
      .query[ImportJobView]
      .to[List]
      .transact(transactor)

  /** Sets status + optional error; guarded on current status so restarts and
    * retries cannot clobber a live run.
    */
  def transitionJob(
      workspaceId: UUID,
      jobId: UUID,
      from: List[String],
      to: String,
      errorMessage: Option[String] = None
  ): IO[Boolean] =
    // `from` values are whitelisted literals, safe to inline
    val inList = from.map(s => "'" + s + "'").mkString(", ")
    (fr"update public.import_job set status = $to, error_message = ${errorMessage} where workspace_id = $workspaceId and id = $jobId and status in (" ++ Fragment
      .const(inList) ++ fr")").update.run
      .map(_ > 0)
      .transact(transactor)

  def setMapping(
      workspaceId: UUID,
      jobId: UUID,
      mapping: Json,
      duplicateStrategy: String,
      defaults: Json,
      targetListId: Option[UUID]
  ): IO[Boolean] =
    sql"""
      update public.import_job
      set column_mapping = ${mapping.noSpaces}::jsonb,
          duplicate_strategy = ${duplicateStrategy},
          defaults = ${defaults.noSpaces}::jsonb,
          target_list_id = ${targetListId},
          status = 'queued'
      where workspace_id = $workspaceId and id = $jobId and status = 'created'
    """.update.run.map(_ > 0).transact(transactor)

  def setTotalRows(workspaceId: UUID, jobId: UUID, totalRows: Int): IO[Unit] =
    sql"update public.import_job set total_rows = $totalRows where workspace_id = $workspaceId and id = $jobId".update.run.void
      .transact(transactor)

  def bumpProgress(
      workspaceId: UUID,
      jobId: UUID,
      processed: Int,
      inserted: Int,
      updated: Int,
      skipped: Int,
      invalid: Int,
      duplicate: Int
  ): IO[Unit] =
    sql"""
      update public.import_job
      set processed_rows = processed_rows + $processed,
          inserted_rows = inserted_rows + $inserted,
          updated_rows = updated_rows + $updated,
          skipped_rows = skipped_rows + $skipped,
          invalid_rows = invalid_rows + $invalid,
          duplicate_rows = duplicate_rows + $duplicate
      where workspace_id = $workspaceId and id = $jobId
    """.update.run.void.transact(transactor)

  def failJob(workspaceId: UUID, jobId: UUID, errorMessage: String): IO[Unit] =
    sql"update public.import_job set status = 'failed', error_message = $errorMessage where workspace_id = $workspaceId and id = $jobId".update.run.void
      .transact(transactor)

  def finishJob(workspaceId: UUID, jobId: UUID): IO[Unit] =
    sql"update public.import_job set status = 'completed', error_message = null where workspace_id = $workspaceId and id = $jobId".update.run.void
      .transact(transactor)

  def markInterrupted(workspaceId: UUID, jobId: UUID): IO[Unit] =
    sql"update public.import_job set status = 'failed', error_message = 'Interrupted by a service restart — retry to resume.' where workspace_id = $workspaceId and id = $jobId and status = 'running'".update.run.void
      .transact(transactor)

  def addError(jobId: UUID, rowNumber: Int, reason: String): IO[Unit] =
    sql"insert into public.import_error (job_id, row_number, reason) values ($jobId, $rowNumber, $reason)".update.run.void
      .transact(transactor)

  final case class ImportRow(jobId: UUID, rowNumber: Int, reason: String)

  def errorsForJob(jobId: UUID): IO[List[ImportRow]] =
    sql"select job_id, row_number, reason from public.import_error where job_id = $jobId order by row_number"
      .query[ImportRow]
      .to[List]
      .transact(transactor)

  /** True when this (job, row) already produced a contact — the idempotency
    * marker that makes retries safe.
    */
  def rowProcessed(jobId: UUID, rowNumber: Int): IO[Boolean] =
    sql"select exists (select 1 from public.source_provenance where import_job_id = $jobId and row_number = $rowNumber)"
      .query[Boolean]
      .unique
      .transact(transactor)

  /** Marks stuck `running` jobs as failed after a process restart. Called at
    * startup; returns how many were interrupted.
    */
  def interruptAllRunning(): IO[Int] =
    sql"update public.import_job set status = 'failed', error_message = 'Interrupted by a service restart — retry to resume.' where status = 'running'".update.run
      .transact(transactor)
