package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.fragments.whereAnd
import doobie.util.transactor.Transactor
import io.circe.Json

import java.time.OffsetDateTime
import java.util.UUID

/** Segment persistence + safe filter evaluation (Phase 3.4). Filters come from
  * the UI as a typed tree; this repo whitelists fields/operators and binds all
  * values as parameters — never string-concatenates user input.
  */
final class SegmentRepos(
    transactor: Transactor[IO],
    val leadsRepos: LeadsRepos
):
  private def parseJson(text: String): Json =
    io.circe.parser.parse(text).getOrElse(Json.Null)

  final case class SegmentView(
      id: UUID,
      name: String,
      filters: Json,
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime
  )

  private val segmentSelect = fr"""
      select id, name, filters::text, created_at, updated_at
      from public.segment
    """

  implicit val segmentRead: Read[SegmentView] = Read[
    (UUID, String, String, OffsetDateTime, OffsetDateTime)
  ].map { t =>
    SegmentView(t._1, t._2, parseJson(t._3), t._4, t._5)
  }

  def listSegments(workspaceId: UUID): IO[List[SegmentView]] =
    (segmentSelect ++ fr"where workspace_id = $workspaceId order by created_at")
      .query[SegmentView]
      .to[List]
      .transact(transactor)

  def findSegment(workspaceId: UUID, segmentId: UUID): IO[Option[SegmentView]] =
    (segmentSelect ++ fr"where workspace_id = $workspaceId and id = $segmentId")
      .query[SegmentView]
      .option
      .transact(transactor)

  def createSegment(
      workspaceId: UUID,
      actor: UUID,
      name: String,
      filters: Json
  ): IO[Option[SegmentView]] =
    (for
      id <- sql"""
        insert into public.segment (workspace_id, created_by, name, filters)
        values ($workspaceId, $actor, $name, ${filters.noSpaces}::jsonb)
        returning id
      """.query[UUID].unique
      row <- (segmentSelect ++ fr"where id = $id").query[SegmentView].option
    yield row).transact(transactor)

  def updateSegment(
      workspaceId: UUID,
      segmentId: UUID,
      name: Option[String],
      filters: Option[Json]
  ): IO[Int] =
    val sets = List.newBuilder[Fragment]
    name.foreach(v => sets += fr"name = $v")
    filters.foreach(v => sets += fr"filters = ${v.noSpaces}::jsonb")
    val assignments = sets.result()
    if assignments.isEmpty then IO.pure(0)
    else
      (fr"update public.segment set " ++
        assignments.reduce((a, b) => a ++ fr", " ++ b) ++
        fr"where workspace_id = $workspaceId and id = $segmentId").update.run
        .transact(transactor)

  def deleteSegment(workspaceId: UUID, segmentId: UUID): IO[Boolean] =
    sql"delete from public.segment where workspace_id = $workspaceId and id = $segmentId".update.run
      .map(_ > 0)
      .transact(transactor)

  /** Builds the WHERE fragment for a filter tree. All fields/operators are
    * whitelisted; values are bound parameters.
    */
  def filterFragment(
      workspaceId: UUID,
      filters: Json
  ): Either[String, Fragment] =
    SegmentRepos.buildFilter(workspaceId, filters)

  def contactCount(workspaceId: UUID, where: Fragment): IO[Long] =
    (fr"select count(*) from public.contact c left join public.company co on co.id = c.company_id" ++
      fr"where c.workspace_id = $workspaceId and" ++ where)
      .query[Long]
      .unique
      .transact(transactor)

  def contactPage(
      workspaceId: UUID,
      where: Fragment,
      limit: Int
  ): IO[List[leadsRepos.ContactView]] =
    import leadsRepos.contactRead
    (SegmentRepos.contactSelectForSegment ++ fr"where c.workspace_id = $workspaceId and" ++ where ++
      fr"order by c.created_at desc, c.id limit $limit")
      .query[leadsRepos.ContactView]
      .to[List]
      .transact(transactor)

object SegmentRepos:

  private def parseJson(text: String): Json =
    io.circe.parser.parse(text).getOrElse(Json.Null)

  /** Contact select reused for segment previews (same column order as
    * LeadsRepos.contactSelect — kept in sync by LeadsIntegrationSpec).
    */
  val contactSelectForSegment = fr"""
      select c.id, c.workspace_id, c.company_id, co.name,
             c.email, c.email_display, c.first_name, c.last_name,
             c.job_title, c.website, c.phone, c.region, c.timezone,
             c.notes, c.custom_fields::text, c.verification_status,
             c.verification_checked_at, c.verification_provider,
             c.state, c.created_at, c.updated_at
      from public.contact c
      left join public.company co on co.id = c.company_id
    """

  // -- filter tree ------------------------------------------------------------

  /** Filter JSON shape (both levels): {"op":"and"|"or","conditions":[
    * <condition-or-group>, ... ]}
    * {"field":"state|verification_status|company|tag|region|job_title|email",
    * "operator":"eq|neq|contains|not_contains|in|not_in", "value": string |
    * [strings]} Whitelisted fields only; values always bound as parameters.
    */
  private val Fields = Set(
    "state",
    "verification_status",
    "company",
    "tag",
    "region",
    "job_title",
    "email"
  )
  private val Operators =
    Set("eq", "neq", "contains", "not_contains", "in", "not_in")

  private def columnFor(field: String): Fragment = field match
    case "state"               => fr"c.state::text"
    case "verification_status" => fr"c.verification_status::text"
    case "company"             => fr"lower(co.name)"
    case "tag"                 => fr"lower(t.name)"
    case "region"              => fr"lower(c.region)"
    case "job_title"           => fr"lower(c.job_title)"
    case "email"               => fr"c.email"
    case _                     => fr"c.email" // unreachable via whitelist

  private def opFragment(
      op: String,
      column: Fragment,
      value: Json,
      values: List[String]
  ): Either[String, Fragment] =
    // All text columns are compared case-insensitively: columns go through
    // lower() in columnFor, so bound values must be lowered to match.
    op match
      case "eq" =>
        value.asString
          .map(v => Right(column ++ fr"= lower($v)"))
          .getOrElse(Left("eq requires a string value"))
      case "neq" =>
        value.asString
          .map(v => Right(column ++ fr"<> lower($v)"))
          .getOrElse(Left("neq requires a string value"))
      case "contains" =>
        value.asString
          .map(v => Right(column ++ fr"like ${"%" + v.toLowerCase + "%"}"))
          .getOrElse(
            Left("contains requires a string value")
          )
      case "not_contains" =>
        value.asString
          .map(v =>
            Right(
              fr"(" ++ column ++ fr"is null or " ++ column ++ fr"not like ${"%" + v.toLowerCase + "%"})"
            )
          )
          .getOrElse(Left("not_contains requires a string value"))
      case "in" =>
        if values.isEmpty then Left("in requires a value list")
        else
          val inList = values
            .map(v => fr"lower($v)")
            .reduceOption((a, b) => a ++ fr", " ++ b)
            .getOrElse(Fragment.empty)
          Right(column ++ fr"in (" ++ inList ++ fr")")
      case "not_in" =>
        if values.isEmpty then Left("not_in requires a value list")
        else
          val inList = values
            .map(v => fr"lower($v)")
            .reduceOption((a, b) => a ++ fr", " ++ b)
            .getOrElse(Fragment.empty)
          Right(
            fr"(" ++ column ++ fr"is null or " ++ column ++ fr"not in (" ++ inList ++ fr"))"
          )
      case _ => Left(s"Unknown operator: $op")

  def buildFilter(workspaceId: UUID, filters: Json): Either[String, Fragment] =
    filters.asObject match
      case None      => Left("filters must be an object")
      case Some(obj) =>
        val op = obj("op").flatMap(_.asString).getOrElse("and")
        val conditions =
          obj("conditions").flatMap(_.asArray).getOrElse(Vector.empty)
        if !Set("and", "or").contains(op) then Left("op must be and/or")
        else if conditions.isEmpty then Left("conditions must not be empty")
        else
          conditions.toList
            .traverse { c =>
              c.asObject match
                case None     => Left("each condition must be an object")
                case Some(co) =>
                  if co("conditions").isDefined then
                    buildFilter(workspaceId, c) // nested group
                  else
                    val field = co("field").flatMap(_.asString).getOrElse("")
                    val operator =
                      co("operator").flatMap(_.asString).getOrElse("")
                    val value = co("value").getOrElse(Json.Null)
                    val values = co("values")
                      .flatMap(_.asArray)
                      .getOrElse(Vector.empty)
                      .flatMap(_.asString)
                      .toList
                    if !Fields.contains(field) then
                      Left(s"Unknown field: $field")
                    else if !Operators.contains(operator) then
                      Left(s"Unknown operator: $operator")
                    else
                      val column = columnFor(field)
                      val withTagJoin =
                        if field == "tag" then
                          opFragment(operator, column, value, values).map(f =>
                            fr"exists (select 1 from public.contact_tag ct join public.tag t on t.id = ct.tag_id where ct.contact_id = c.id and " ++ f ++ fr")"
                          )
                        else opFragment(operator, column, value, values)
                      withTagJoin
            }
            .map { fragments =>
              val joined = fragments
                .reduceOption((a, b) =>
                  if op == "and" then a ++ fr" and " ++ b
                  else a ++ fr" or " ++ b
                )
                .getOrElse(Fragment.empty)
              fr"(" ++ joined ++ fr")"
            }
