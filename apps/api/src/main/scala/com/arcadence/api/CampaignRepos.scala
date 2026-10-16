package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.free.FC
import io.circe.Json
import io.circe.syntax.*

import java.time.OffsetDateTime
import java.util.UUID

/** Campaign persistence (Phase 4.1): campaign rows with a Scala-validated
  * lifecycle (draft/active/paused/completed/cancelled/archived), steps +
  * variants replaced transactionally, mailbox/audience assignments validated
  * against workspace ownership, enrollment rows, and version snapshots of every
  * structural save.
  */
final class CampaignRepos(val transactor: Transactor[IO]):

  private def parseJson(text: String): Json =
    io.circe.parser.parse(text).getOrElse(Json.Null)

  final case class CampaignRow(
      id: UUID,
      workspaceId: UUID,
      name: String,
      description: String,
      tags: List[String],
      status: String,
      timezone: String,
      dailyCap: Int,
      openTracking: Boolean,
      clickTracking: Boolean,
      activatedAt: Option[OffsetDateTime],
      archivedAt: Option[OffsetDateTime],
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime
  )

  final case class StepRow(
      id: UUID,
      position: Int,
      `type`: String,
      name: String,
      waitDays: Option[Int]
  )

  final case class VariantRow(
      stepId: UUID,
      variantKey: String,
      subject: String,
      preheader: String,
      bodyHtml: String,
      bodyText: String
  )

  final case class AudienceRow(
      id: UUID,
      leadListId: Option[UUID],
      segmentId: Option[UUID]
  )

  final case class VersionRow(
      id: UUID,
      version: Int,
      definition: Json,
      createdAt: OffsetDateTime
  )

  private val campaignSelect = fr"""
      select id, workspace_id, name, description, tags, status::text, timezone,
             daily_cap, open_tracking, click_tracking,
             activated_at, archived_at, created_at, updated_at
      from public.campaign
    """

  private implicit val campaignRead: Read[CampaignRow] = Read[
    (
        UUID,
        UUID,
        String,
        String,
        List[String],
        String,
        String,
        Int,
        Boolean,
        Boolean,
        Option[OffsetDateTime],
        Option[OffsetDateTime],
        OffsetDateTime,
        OffsetDateTime
    )
  ].map { t =>
    CampaignRow(
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
      t._14
    )
  }

  private val stepSelect = fr"""
      select id, position, type::text, name, wait_days
      from public.campaign_step
    """

  private implicit val stepRead: Read[StepRow] =
    Read[(UUID, Int, String, String, Option[Int])]
      .map(t => StepRow(t._1, t._2, t._3, t._4, t._5))

  def listCampaigns(
      workspaceId: UUID,
      status: Option[String]
  ): IO[List[CampaignRow]] =
    val base =
      campaignSelect ++ fr"where workspace_id = $workspaceId"
    val filtered = status match
      case Some(s) => base ++ fr"and status = $s::campaign_status"
      case None    => base
    (filtered ++ fr"order by created_at desc")
      .query[CampaignRow]
      .to[List]
      .transact(transactor)

  def findCampaign(
      workspaceId: UUID,
      campaignId: UUID
  ): IO[Option[CampaignRow]] =
    (campaignSelect ++ fr"where workspace_id = $workspaceId and id = $campaignId")
      .query[CampaignRow]
      .option
      .transact(transactor)

  def stepsFor(campaignId: UUID): IO[List[StepRow]] =
    (stepSelect ++ fr"where campaign_id = $campaignId order by position")
      .query[StepRow]
      .to[List]
      .transact(transactor)

  def variantsFor(campaignId: UUID): IO[List[VariantRow]] =
    sql"""
      select v.step_id, v.variant_key, v.subject, v.preheader, v.body_html, v.body_text
      from public.step_variant v
      join public.campaign_step s on s.id = v.step_id
      where s.campaign_id = $campaignId
      order by s.position, v.variant_key
    """
      .query[VariantRow]
      .to[List]
      .transact(transactor)

  def audiencesFor(campaignId: UUID): IO[List[AudienceRow]] =
    sql"""
      select id, lead_list_id, segment_id from public.campaign_audience
      where campaign_id = $campaignId order by added_at
    """
      .query[AudienceRow]
      .to[List]
      .transact(transactor)

  def mailboxIdsFor(campaignId: UUID): IO[List[UUID]] =
    sql"select email_account_id from public.campaign_mailbox where campaign_id = $campaignId order by priority, created_at"
      .query[UUID]
      .to[List]
      .transact(transactor)

  def versionsFor(workspaceId: UUID, campaignId: UUID): IO[List[VersionRow]] =
    sql"""
      select v.id, v.version, v.definition::text, v.created_at
      from public.campaign_version v
      join public.campaign c on c.id = v.campaign_id
      where v.campaign_id = $campaignId and c.workspace_id = $workspaceId
      order by v.version desc
    """
      .query[(UUID, Int, String, OffsetDateTime)]
      .map(t => VersionRow(t._1, t._2, parseJson(t._3), t._4))
      .to[List]
      .transact(transactor)

  /** Counts active (non-terminal) enrollments — used to explain why a
    * structural change was rejected.
    */
  def activeEnrollmentCount(campaignId: UUID): IO[Long] =
    sql"""
      select count(*) from public.campaign_enrollment
      where campaign_id = $campaignId and status in ('active', 'paused', 'pending')
    """
      .query[Long]
      .unique
      .transact(transactor)

  /** Workspace ownership checks for referenced rows. */
  def countOwnedEmailAccounts(workspaceId: UUID, ids: List[UUID]): IO[Long] =
    if ids.isEmpty then IO.pure(0L)
    else
      (fr"select count(distinct id) from public.email_accounts where workspace_id = $workspaceId and id in (" ++
        ids.map(id => fr"$id").reduce((a, b) => a ++ fr", " ++ b) ++ fr")")
        .query[Long]
        .unique
        .transact(transactor)

  def countOwnedList(workspaceId: UUID, listId: UUID): IO[Long] =
    sql"select count(*) from public.lead_list where workspace_id = $workspaceId and id = $listId"
      .query[Long]
      .unique
      .transact(transactor)

  def countOwnedSegment(workspaceId: UUID, segmentId: UUID): IO[Long] =
    sql"select count(*) from public.segment where workspace_id = $workspaceId and id = $segmentId"
      .query[Long]
      .unique
      .transact(transactor)

  /** Result of a create/update attempt; None = name conflict. */
  def createCampaign(
      workspaceId: UUID,
      actor: UUID,
      name: String,
      description: String,
      tags: List[String],
      timezone: String,
      dailyCap: Int
  ): IO[Option[CampaignRow]] =
    sql"""
      insert into public.campaign (workspace_id, created_by, name, description, tags, timezone, daily_cap)
      values ($workspaceId, $actor, $name, $description, $tags, $timezone, $dailyCap)
      on conflict do nothing
      returning id
    """
      .query[UUID]
      .option
      .transact(transactor)
      .flatMap {
        case None      => IO.pure(None)
        case Some(pid) => findCampaign(workspaceId, pid)
      }

  /** Field edit; structural arrays are handled by
    * replaceSteps/replaceMailboxes/ replaceAudiences. Only call after lifecycle
    * rules permit it.
    */
  def updateCampaignFields(
      workspaceId: UUID,
      campaignId: UUID,
      name: Option[String],
      description: Option[String],
      tags: Option[List[String]],
      timezone: Option[String],
      dailyCap: Option[Int]
  ): IO[Int] =
    val sets = List.newBuilder[Fragment]
    name.foreach(v => sets += fr"name = $v")
    description.foreach(v => sets += fr"description = $v")
    tags.foreach(v => sets += fr"tags = $v")
    timezone.foreach(v => sets += fr"timezone = $v")
    dailyCap.foreach(v => sets += fr"daily_cap = $v")
    val assignments = sets.result()
    if assignments.isEmpty then IO.pure(0)
    else
      (fr"update public.campaign set " ++
        assignments.reduce((a, b) => a ++ fr", " ++ b) ++
        fr"where workspace_id = $workspaceId and id = $campaignId").update.run.attempt
        .transact(transactor)
        .map {
          case Right(n) => n
          case Left(e: java.sql.SQLException)
              if e.getSQLState != null && e.getSQLState.startsWith("23") =>
            -1 // name conflict
          case Left(e) => throw e
        }

  /** Replaces steps + variants in one transaction and snapshots a version.
    * Caller enforces the lifecycle rules.
    */
  def replaceSteps(
      campaignId: UUID,
      actor: UUID,
      steps: List[(Int, String, String, Option[Int], List[VariantRow])]
  ): IO[Unit] =
    (for
      _ <-
        sql"delete from public.campaign_step where campaign_id = $campaignId".update.run
      _ <- steps.traverse {
        case (position, stepType, name, waitDays, variants) =>
          sql"""
          insert into public.campaign_step (campaign_id, position, type, name, wait_days)
          values ($campaignId, $position, $stepType::step_type, $name, $waitDays)
          returning id
        """.query[UUID].unique.flatMap { stepId =>
            variants.traverse(v =>
              sql"""
              insert into public.step_variant (step_id, variant_key, subject, preheader, body_html, body_text)
              values ($stepId, ${v.variantKey}, ${v.subject}, ${v.preheader}, ${v.bodyHtml}, ${v.bodyText})
            """.update.run.void
            )
          }
      }
      definition <- definitionJson(campaignId)
      _ <- snapshotVersion(campaignId, actor, definition)
      _ <-
        sql"update public.campaign set updated_at = now() where id = $campaignId".update.run
    yield ()).transact(transactor)

  def replaceMailboxes(campaignId: UUID, accountIds: List[UUID]): IO[Unit] =
    (for
      _ <-
        sql"delete from public.campaign_mailbox where campaign_id = $campaignId".update.run
      _ <- accountIds.zipWithIndex.traverse { case (accountId, idx) =>
        sql"""
          insert into public.campaign_mailbox (campaign_id, email_account_id, priority)
          values ($campaignId, $accountId, ${idx})
          on conflict do nothing
        """.update.run.void
      }
    yield ()).transact(transactor)

  def replaceAudiences(
      campaignId: UUID,
      audiences: List[(Option[UUID], Option[UUID])]
  ): IO[Unit] =
    (for
      _ <-
        sql"delete from public.campaign_audience where campaign_id = $campaignId".update.run
      _ <- audiences.traverse { case (listId, segmentId) =>
        sql"""
          insert into public.campaign_audience (campaign_id, lead_list_id, segment_id)
          values ($campaignId, $listId, $segmentId)
          on conflict do nothing
        """.update.run.void
      }
    yield ()).transact(transactor)

  /** Guarded lifecycle transition: only applies when the row still has the
    * expected status. Returns affected row count (0 = raced or missing).
    */
  def transitionStatus(
      workspaceId: UUID,
      campaignId: UUID,
      expectedCurrent: String,
      next: String
  ): IO[Int] =
    next match
      case "active" =>
        sql"""
          update public.campaign set status = 'active', activated_at = coalesce(activated_at, now())
          where workspace_id = $workspaceId and id = $campaignId and status = $expectedCurrent::campaign_status
        """.update.run.transact(transactor)
      case "archived" =>
        sql"""
          update public.campaign set status = 'archived', archived_at = now()
          where workspace_id = $workspaceId and id = $campaignId and status = $expectedCurrent::campaign_status
        """.update.run.transact(transactor)
      case other =>
        sql"""
          update public.campaign set status = $other::campaign_status
          where workspace_id = $workspaceId and id = $campaignId and status = $expectedCurrent::campaign_status
        """.update.run.transact(transactor)

  /** Hard-deletes a draft. Returns false when the campaign is not a draft or
    * does not exist — the route distinguishes those.
    */
  def deleteDraft(workspaceId: UUID, campaignId: UUID): IO[Boolean] =
    sql"delete from public.campaign where workspace_id = $workspaceId and id = $campaignId and status = 'draft'".update.run
      .map(_ > 0)
      .transact(transactor)

  /** Deep-copies the campaign (steps, variants, mailboxes, audiences) as a
    * DRAFT named `newName`; returns the new campaign id.
    */
  def duplicateCampaign(
      workspaceId: UUID,
      actor: UUID,
      sourceId: UUID,
      newName: String
  ): IO[Option[UUID]] =
    (for
      src <-
        (campaignSelect ++ fr"where id = $sourceId and workspace_id = $workspaceId")
          .query[CampaignRow]
          .option
      newId <- src match
        case None    => FC.pure(None: Option[UUID])
        case Some(c) =>
          sql"""
            insert into public.campaign (workspace_id, created_by, name, description, tags, timezone, daily_cap, open_tracking, click_tracking)
            values ($workspaceId, $actor, $newName, ${c.description}, ${c.tags}, ${c.timezone}, ${c.dailyCap}, ${c.openTracking}, ${c.clickTracking})
            on conflict do nothing
            returning id
          """.query[UUID].option
      _ <- newId.traverse { targetId =>
        for
          steps <-
            (stepSelect ++ fr"where campaign_id = $sourceId order by position")
              .query[StepRow]
              .to[List]
          stepMap <- steps
            .traverse { s =>
              sql"""
              insert into public.campaign_step (campaign_id, position, type, name, wait_days)
              values ($targetId, ${s.position}, ${s.`type`}::step_type, ${s.name}, ${s.waitDays})
              returning id
            """.query[UUID].unique.map(s.id -> _)
            }
            .map(_.toMap)
          variants <- sql"""
            select v.step_id, v.variant_key, v.subject, v.preheader, v.body_html, v.body_text
            from public.step_variant v join public.campaign_step s on s.id = v.step_id
            where s.campaign_id = $sourceId order by s.position, v.variant_key
          """.query[VariantRow].to[List]
          _ <- variants.traverse(v =>
            sql"""
              insert into public.step_variant (step_id, variant_key, subject, preheader, body_html, body_text)
              values (${stepMap(
                v.stepId
              )}, ${v.variantKey}, ${v.subject}, ${v.preheader}, ${v.bodyHtml}, ${v.bodyText})
            """.update.run.void
          )
          _ <- sql"""
            insert into public.campaign_mailbox (campaign_id, email_account_id, priority)
            select $targetId, email_account_id, priority from public.campaign_mailbox where campaign_id = $sourceId
          """.update.run
          _ <- sql"""
            insert into public.campaign_audience (campaign_id, lead_list_id, segment_id)
            select $targetId, lead_list_id, segment_id from public.campaign_audience where campaign_id = $sourceId
          """.update.run
        yield ()
      }
    yield newId).transact(transactor)

  /** Enrolls contacts into an ACTIVE campaign; idempotent per (campaign,
    * contact). Returns rows actually added.
    */
  def enroll(
      workspaceId: UUID,
      campaignId: UUID,
      contactIds: List[UUID]
  ): IO[Int] =
    if contactIds.isEmpty then IO.pure(0)
    else
      (fr"""
        insert into public.campaign_enrollment (workspace_id, campaign_id, contact_id)
        select $workspaceId, $campaignId, c.id
        from public.contact c
        where c.workspace_id = $workspaceId and c.id in (""" ++
        contactIds.map(id => fr"$id").reduce((a, b) => a ++ fr", " ++ b) ++
        fr") on conflict (campaign_id, contact_id) do nothing").update.run
        .transact(transactor)

  def audit(
      workspaceId: UUID,
      actor: UUID,
      action: String,
      targetType: String,
      targetId: String,
      metadata: Json = Json.obj()
  ): IO[Unit] =
    sql"""
      insert into public.audit_log (workspace_id, actor, action, target_type, target_id, metadata)
      values ($workspaceId, $actor, $action, $targetType, $targetId, ${metadata.noSpaces}::jsonb)
    """.update.run.void.transact(transactor)

  // -- internals ------------------------------------------------------------------

  private def definitionJson(campaignId: UUID): ConnectionIO[Json] =
    for
      steps <-
        (stepSelect ++ fr"where campaign_id = $campaignId order by position")
          .query[StepRow]
          .to[List]
      variants <- variantsForConn(campaignId)
      byStep = variants.groupBy(_.stepId)
    yield Json.obj(
      "steps" -> steps.map { s =>
        Json.obj(
          "position" -> Json.fromInt(s.position),
          "type" -> Json.fromString(s.`type`),
          "name" -> Json.fromString(s.name),
          "waitDays" -> s.waitDays
            .map(d => Json.fromInt(d))
            .getOrElse(Json.Null),
          "variants" -> byStep
            .getOrElse(s.id, Nil)
            .map(v =>
              Json.obj(
                "variantKey" -> Json.fromString(v.variantKey),
                "subject" -> Json.fromString(v.subject)
              )
            )
            .asJson
        )
      }.asJson
    )

  private def variantsForConn(
      campaignId: UUID
  ): ConnectionIO[List[VariantRow]] =
    sql"""
      select v.step_id, v.variant_key, v.subject, v.preheader, v.body_html, v.body_text
      from public.step_variant v
      join public.campaign_step s on s.id = v.step_id
      where s.campaign_id = $campaignId
      order by s.position, v.variant_key
    """
      .query[VariantRow]
      .to[List]

  private def snapshotVersion(
      campaignId: UUID,
      actor: UUID,
      definition: Json
  ): ConnectionIO[Unit] =
    for
      next <-
        sql"select coalesce(max(version), 0) + 1 from public.campaign_version where campaign_id = $campaignId"
          .query[Int]
          .unique
      _ <- sql"""
        insert into public.campaign_version (campaign_id, version, definition, created_by)
        values ($campaignId, $next, ${definition.noSpaces}::jsonb, $actor)
      """.update.run.void
    yield ()
