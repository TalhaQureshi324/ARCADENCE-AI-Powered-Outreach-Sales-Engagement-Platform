package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import doobie.*
import doobie.free.FC
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json

import java.time.OffsetDateTime
import java.util.UUID

/** Persistence for the lead module (Phase 3.1): contacts, companies, lead
  * lists, tags, custom fields and source provenance. All queries are
  * workspace-scoped; emails are stored normalized (lowercase).
  */
final class LeadsRepos(val transactor: Transactor[IO]):

  // fragment helpers (doobie 1.0 fragments object lacks intercalate)
  private def joinFr(sep: String, fs: List[Fragment]): Fragment =
    fs.reduceOption((a, b) => a ++ Fragment.const(sep) ++ b)
      .getOrElse(Fragment.empty)
  private def setClause(fs: List[Fragment]): Fragment =
    joinFr(", ", fs)
  private def whereOf(fs: List[Fragment]): Fragment =
    if fs.isEmpty then Fragment.empty else fr"where" ++ joinFr(" and ", fs)
  private def parseJson(text: String): Json =
    io.circe.parser.parse(text).getOrElse(Json.Null)

  // -- views ------------------------------------------------------------------

  final case class ContactView(
      id: UUID,
      workspaceId: UUID,
      companyId: Option[UUID],
      companyName: Option[String],
      email: String,
      emailDisplay: Option[String],
      firstName: Option[String],
      lastName: Option[String],
      jobTitle: Option[String],
      website: Option[String],
      phone: Option[String],
      region: Option[String],
      timezone: Option[String],
      notes: Option[String],
      customFields: Json,
      verificationStatus: String,
      verificationCheckedAt: Option[OffsetDateTime],
      verificationProvider: Option[String],
      state: String,
      createdAt: OffsetDateTime,
      updatedAt: OffsetDateTime
  )

  final case class ContactFilter(
      q: Option[String] = None,
      state: Option[String] = None,
      verification: Option[String] = None,
      companyId: Option[UUID] = None,
      listId: Option[UUID] = None,
      tagId: Option[UUID] = None,
      sort: String = "created_desc",
      limit: Int = 25,
      cursorCreatedAt: Option[OffsetDateTime] = None,
      cursorId: Option[UUID] = None,
      cursorEmail: Option[String] = None
  )

  private val contactSelect = fr"""
      select c.id, c.workspace_id, c.company_id, co.name,
             c.email, c.email_display, c.first_name, c.last_name,
             c.job_title, c.website, c.phone, c.region, c.timezone,
             c.notes, c.custom_fields::text, c.verification_status,
             c.verification_checked_at, c.verification_provider,
             c.state, c.created_at, c.updated_at
      from public.contact c
      left join public.company co on co.id = c.company_id
    """

  private def contactFilterFr(workspaceId: UUID, f: ContactFilter): Fragment =
    val parts = List.newBuilder[Fragment]
    parts += fr"c.workspace_id = $workspaceId"
    f.q.foreach { q =>
      val like = s"%${q.toLowerCase}%"
      parts += fr"(lower(c.email) like $like or lower(coalesce(c.first_name, '')) like $like or lower(coalesce(c.last_name, '')) like $like)"
    }
    f.state.foreach(s => parts += fr"c.state = ${s}::public.contact_state")
    f.verification.foreach(v =>
      parts += fr"c.verification_status = ${v}::public.contact_verification"
    )
    f.companyId.foreach(id => parts += fr"c.company_id = $id")
    f.listId.foreach(id =>
      parts += fr"exists (select 1 from public.list_membership lm where lm.contact_id = c.id and lm.list_id = $id)"
    )
    f.tagId.foreach(id =>
      parts += fr"exists (select 1 from public.contact_tag ct where ct.contact_id = c.id and ct.tag_id = $id)"
    )
    whereOf(parts.result())

  private def contactOrderFr(sort: String): Fragment = sort match
    case "created_asc" => fr"c.created_at asc, c.id asc"
    case "email_asc"   => fr"c.email asc, c.id asc"
    case "email_desc"  => fr"c.email desc, c.id desc"
    case _             => fr"c.created_at desc, c.id desc"

  implicit val contactRead: Read[ContactView] = Read[
    (
        UUID,
        UUID,
        Option[UUID],
        Option[String],
        String,
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        Option[String],
        String,
        String,
        Option[OffsetDateTime],
        Option[String],
        String,
        OffsetDateTime,
        OffsetDateTime
    )
  ].map { t =>
    ContactView(
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
      t._16,
      t._17,
      t._18,
      t._19,
      t._20,
      t._21
    )
  }

  def listContacts(workspaceId: UUID, f: ContactFilter): IO[List[ContactView]] =
    val keyset: Fragment =
      (f.cursorCreatedAt, f.cursorId) match
        case (Some(ts), Some(id)) =>
          f.sort match
            case "email_asc" =>
              // keyset must match the sort: (email, id) tuple comparison
              f.cursorEmail match
                case Some(email) => fr" and (c.email, c.id) > ($email, $id)"
                case None        => fr" and (c.created_at, c.id) > ($ts, $id)"
            case "email_desc" =>
              f.cursorEmail match
                case Some(email) => fr" and (c.email, c.id) < ($email, $id)"
                case None        => fr" and (c.created_at, c.id) < ($ts, $id)"
            case "created_asc" => fr" and (c.created_at, c.id) > ($ts, $id)"
            case _             => fr" and (c.created_at, c.id) < ($ts, $id)"
        case _ => Fragment.empty
    (contactSelect ++ contactFilterFr(workspaceId, f) ++ keyset ++
      fr"order by" ++ contactOrderFr(f.sort) ++ fr"limit ${f.limit}")
      .query[ContactView]
      .to[List]
      .transact(transactor)

  def countContacts(workspaceId: UUID, f: ContactFilter): IO[Long] =
    (fr"select count(*) from public.contact c left join public.company co on co.id = c.company_id" ++
      contactFilterFr(workspaceId, f))
      .query[Long]
      .unique
      .transact(transactor)

  def findContact(workspaceId: UUID, contactId: UUID): IO[Option[ContactView]] =
    (contactSelect ++ fr"where c.workspace_id = $workspaceId and c.id = $contactId")
      .query[ContactView]
      .option
      .transact(transactor)

  def findContactByEmail(
      workspaceId: UUID,
      email: String
  ): IO[Option[ContactView]] =
    (contactSelect ++ fr"where c.workspace_id = $workspaceId and c.email = $email")
      .query[ContactView]
      .option
      .transact(transactor)

  final case class NewContactRow(
      companyId: Option[UUID],
      email: String,
      emailDisplay: Option[String],
      firstName: Option[String],
      lastName: Option[String],
      jobTitle: Option[String],
      website: Option[String],
      phone: Option[String],
      region: Option[String],
      timezone: Option[String],
      notes: Option[String],
      customFields: Json,
      provenanceDetails: Json = Json.obj()
  )

  /** Creates a contact + a provenance row (manual/api) atomically. */
  def createContact(
      workspaceId: UUID,
      source: String,
      c: NewContactRow
  ): IO[ContactView] =
    (for
      id <- sql"""
        insert into public.contact
          (workspace_id, company_id, email, email_display, first_name, last_name,
           job_title, website, phone, region, timezone, notes, custom_fields)
        values ($workspaceId, ${c.companyId}, ${c.email}, ${c.emailDisplay},
                ${c.firstName}, ${c.lastName}, ${c.jobTitle}, ${c.website},
                ${c.phone}, ${c.region}, ${c.timezone}, ${c.notes},
                ${c.customFields.noSpaces}::jsonb)
        returning id
      """.query[UUID].unique
      _ <- sql"""
        insert into public.source_provenance (workspace_id, contact_id, source, details)
        values ($workspaceId, $id, $source, ${c.provenanceDetails.noSpaces}::jsonb)
      """.update.run.void
      row <- (contactSelect ++ fr"where c.id = $id").query[ContactView].unique
    yield row).transact(transactor)

  def updateContact(
      workspaceId: UUID,
      contactId: UUID,
      sets: List[Fragment]
  ): IO[Int] =
    if sets.isEmpty then IO.pure(0)
    else
      (fr"update public.contact set " ++ setClause(sets) ++
        fr"where workspace_id = $workspaceId and id = $contactId").update.run
        .transact(transactor)

  def deleteContact(workspaceId: UUID, contactId: UUID): IO[Boolean] =
    sql"delete from public.contact where workspace_id = $workspaceId and id = $contactId".update.run
      .map(_ > 0)
      .transact(transactor)

  def recordProvenance(
      workspaceId: UUID,
      contactId: UUID,
      source: String,
      importJobId: Option[UUID],
      details: Json
  ): IO[Unit] =
    sql"""
      insert into public.source_provenance (workspace_id, contact_id, source, import_job_id, details)
      values ($workspaceId, $contactId, $source, ${importJobId}, ${details.noSpaces}::jsonb)
    """.update.run.void.transact(transactor)

  final case class ProvenanceView(
      id: UUID,
      source: String,
      importJobId: Option[UUID],
      details: Json,
      createdAt: OffsetDateTime
  )

  def listProvenance(
      workspaceId: UUID,
      contactId: UUID,
      limit: Int = 20
  ): IO[List[ProvenanceView]] =
    sql"""
      select id, source, import_job_id, details::text, created_at
      from public.source_provenance
      where workspace_id = $workspaceId and contact_id = $contactId
      order by created_at desc
      limit $limit
    """
      .query[(UUID, String, Option[UUID], String, OffsetDateTime)]
      .map { case (id, source, jobId, details, at) =>
        ProvenanceView(id, source, jobId, parseJson(details), at)
      }
      .to[List]
      .transact(transactor)

  // -- companies ----------------------------------------------------------------

  final case class CompanyView(
      id: UUID,
      name: String,
      domain: Option[String],
      website: Option[String],
      contactCount: Int,
      createdAt: OffsetDateTime
  )

  def listCompanies(
      workspaceId: UUID,
      q: Option[String]
  ): IO[List[CompanyView]] =
    val filter = q.map { term =>
      val like = s"%${term.toLowerCase}%"
      fr"and lower(co.name) like $like"
    }
    (fr"""
      select co.id, co.name, co.domain, co.website,
             (select count(*)::int from public.contact c where c.company_id = co.id),
             co.created_at
      from public.company co
      where co.workspace_id = $workspaceId
    """ ++ filter.getOrElse(
      Fragment.empty
    ) ++ fr"order by co.name asc limit 200")
      .query[CompanyView]
      .to[List]
      .transact(transactor)

  def createCompany(
      workspaceId: UUID,
      name: String,
      domain: Option[String],
      website: Option[String]
  ): IO[Option[CompanyView]] =
    (for
      id <- sql"""
        insert into public.company (workspace_id, name, domain, website)
        values ($workspaceId, $name, ${domain}, ${website})
        returning id
      """.query[UUID].unique
      row <- sql"""
        select co.id, co.name, co.domain, co.website, 0, co.created_at
        from public.company co where co.id = $id
      """.query[CompanyView].option
    yield row).transact(transactor)

  def updateCompany(
      workspaceId: UUID,
      companyId: UUID,
      name: Option[String],
      domain: Option[Option[String]],
      website: Option[Option[String]]
  ): IO[Int] =
    val sets = List.newBuilder[Fragment]
    name.foreach(v => sets += fr"name = $v")
    domain.foreach(v => sets += fr"domain = $v")
    website.foreach(v => sets += fr"website = $v")
    val assignments = sets.result()
    if assignments.isEmpty then IO.pure(0)
    else
      (fr"update public.company set " ++ setClause(assignments) ++
        fr"where workspace_id = $workspaceId and id = $companyId").update.run
        .transact(transactor)

  def deleteCompany(workspaceId: UUID, companyId: UUID): IO[Boolean] =
    sql"delete from public.company where workspace_id = $workspaceId and id = $companyId".update.run
      .map(_ > 0)
      .transact(transactor)

  // -- lead lists ----------------------------------------------------------------

  final case class LeadListView(
      id: UUID,
      name: String,
      description: Option[String],
      memberCount: Int,
      createdAt: OffsetDateTime
  )

  def listLeadLists(workspaceId: UUID): IO[List[LeadListView]] =
    sql"""
      select l.id, l.name, l.description,
             (select count(*)::int from public.list_membership lm where lm.list_id = l.id),
             l.created_at
      from public.lead_list l
      where l.workspace_id = $workspaceId
      order by l.created_at
    """.query[LeadListView].to[List].transact(transactor)

  def createLeadList(
      workspaceId: UUID,
      actor: UUID,
      name: String,
      description: Option[String]
  ): IO[Option[LeadListView]] =
    (for
      id <- sql"""
        insert into public.lead_list (workspace_id, created_by, name, description)
        values ($workspaceId, $actor, $name, ${description})
        returning id
      """.query[UUID].unique
      row <- sql"""
        select l.id, l.name, l.description, 0, l.created_at
        from public.lead_list l where l.id = $id
      """.query[LeadListView].option
    yield row).transact(transactor)

  def updateLeadList(
      workspaceId: UUID,
      listId: UUID,
      name: Option[String],
      description: Option[Option[String]]
  ): IO[Int] =
    val sets = List.newBuilder[Fragment]
    name.foreach(v => sets += fr"name = $v")
    description.foreach(v => sets += fr"description = $v")
    val assignments = sets.result()
    if assignments.isEmpty then IO.pure(0)
    else
      (fr"update public.lead_list set " ++ setClause(assignments) ++
        fr"where workspace_id = $workspaceId and id = $listId").update.run
        .transact(transactor)

  def deleteLeadList(workspaceId: UUID, listId: UUID): IO[Boolean] =
    sql"delete from public.lead_list where workspace_id = $workspaceId and id = $listId".update.run
      .map(_ > 0)
      .transact(transactor)

  /** Adds contacts to a list (idempotent per pair); returns how many were newly
    * added.
    */
  def addListMembers(
      workspaceId: UUID,
      actor: UUID,
      listId: UUID,
      contactIds: List[UUID]
  ): IO[Int] =
    sql"select count(*) from public.lead_list where workspace_id = $workspaceId and id = $listId"
      .query[Int]
      .unique
      .transact(transactor)
      .flatMap {
        case 0 => IO.pure(0)
        case _ =>
          val rows = contactIds.map(cid => sql"($listId, $cid, $actor)")
          (fr"insert into public.list_membership (list_id, contact_id, added_by) values " ++
            joinFr(
              ", ",
              rows
            ) ++ fr"on conflict (list_id, contact_id) do nothing").update.run
            .transact(transactor)
      }

  def removeListMember(
      workspaceId: UUID,
      listId: UUID,
      contactId: UUID
  ): IO[Boolean] =
    sql"""
      delete from public.list_membership lm
      using public.lead_list l
      where lm.list_id = l.id and l.workspace_id = $workspaceId
        and lm.list_id = $listId and lm.contact_id = $contactId
    """.update.run.map(_ > 0).transact(transactor)

  def listMembers(
      workspaceId: UUID,
      listId: UUID,
      f: ContactFilter
  ): IO[List[ContactView]] =
    val withList = f.copy(listId = Some(listId))
    (contactSelect ++ contactFilterFr(workspaceId, withList) ++
      fr"order by" ++ contactOrderFr(f.sort) ++ fr"limit ${f.limit}")
      .query[ContactView]
      .to[List]
      .transact(transactor)

  // -- tags --------------------------------------------------------------------

  final case class TagView(
      id: UUID,
      name: String,
      color: Option[String],
      contactCount: Int,
      createdAt: OffsetDateTime
  )

  def listTags(workspaceId: UUID): IO[List[TagView]] =
    sql"""
      select t.id, t.name, t.color,
             (select count(*)::int from public.contact_tag ct where ct.tag_id = t.id),
             t.created_at
      from public.tag t
      where t.workspace_id = $workspaceId
      order by t.name asc
    """.query[TagView].to[List].transact(transactor)

  def createTag(
      workspaceId: UUID,
      name: String,
      color: Option[String]
  ): IO[Option[TagView]] =
    (for
      id <- sql"""
        insert into public.tag (workspace_id, name, color)
        values ($workspaceId, $name, ${color})
        returning id
      """.query[UUID].unique
      row <- sql"""
        select t.id, t.name, t.color, 0, t.created_at from public.tag t where t.id = $id
      """.query[TagView].option
    yield row).transact(transactor)

  def deleteTag(workspaceId: UUID, tagId: UUID): IO[Boolean] =
    sql"delete from public.tag where workspace_id = $workspaceId and id = $tagId".update.run
      .map(_ > 0)
      .transact(transactor)

  def addContactTags(
      workspaceId: UUID,
      contactId: UUID,
      tagIds: List[UUID]
  ): IO[Int] =
    sql"select count(*) from public.contact where workspace_id = $workspaceId and id = $contactId"
      .query[Int]
      .unique
      .transact(transactor)
      .flatMap {
        case 0 => IO.pure(0)
        case _ =>
          val rows = tagIds.map(tid => sql"($contactId, $tid)")
          (fr"insert into public.contact_tag (contact_id, tag_id) values " ++
            joinFr(
              ", ",
              rows
            ) ++ fr"on conflict (contact_id, tag_id) do nothing").update.run
            .transact(transactor)
      }

  def removeContactTag(
      workspaceId: UUID,
      contactId: UUID,
      tagId: UUID
  ): IO[Boolean] =
    sql"""
      delete from public.contact_tag ct
      using public.contact c
      where ct.contact_id = c.id and c.workspace_id = $workspaceId
        and ct.contact_id = $contactId and ct.tag_id = $tagId
    """.update.run.map(_ > 0).transact(transactor)

  def listContactTags(workspaceId: UUID, contactId: UUID): IO[List[TagView]] =
    sql"""
      select t.id, t.name, t.color, 0, t.created_at
      from public.tag t
      join public.contact_tag ct on ct.tag_id = t.id
      join public.contact c on c.id = ct.contact_id
      where c.workspace_id = $workspaceId and c.id = $contactId
      order by t.name asc
    """.query[TagView].to[List].transact(transactor)

  // -- custom fields -------------------------------------------------------------

  final case class CustomFieldView(
      id: UUID,
      name: String,
      fieldType: String,
      description: Option[String],
      createdAt: OffsetDateTime
  )

  def listCustomFields(workspaceId: UUID): IO[List[CustomFieldView]] =
    sql"""
      select id, name, field_type::text, description, created_at
      from public.custom_field
      where workspace_id = $workspaceId
      order by name asc
    """.query[CustomFieldView].to[List].transact(transactor)

  def createCustomField(
      workspaceId: UUID,
      name: String,
      fieldType: String,
      description: Option[String]
  ): IO[Option[CustomFieldView]] =
    (for
      id <- sql"""
        insert into public.custom_field (workspace_id, name, field_type, description)
        values ($workspaceId, $name, ${fieldType}::public.field_type, ${description})
        returning id
      """.query[UUID].unique
      row <- sql"""
        select id, name, field_type::text, description, created_at
        from public.custom_field where id = $id
      """.query[CustomFieldView].option
    yield row).transact(transactor)

  def updateCustomField(
      workspaceId: UUID,
      fieldId: UUID,
      name: Option[String],
      description: Option[Option[String]]
  ): IO[Int] =
    val sets = List.newBuilder[Fragment]
    name.foreach(v => sets += fr"name = $v")
    description.foreach(v => sets += fr"description = $v")
    val assignments = sets.result()
    if assignments.isEmpty then IO.pure(0)
    else
      (fr"update public.custom_field set " ++ setClause(assignments) ++
        fr"where workspace_id = $workspaceId and id = $fieldId").update.run
        .transact(transactor)

  def deleteCustomField(workspaceId: UUID, fieldId: UUID): IO[Boolean] =
    sql"delete from public.custom_field where workspace_id = $workspaceId and id = $fieldId".update.run
      .map(_ > 0)
      .transact(transactor)

  /** Custom-field values are validated against known field names. */
  def knownFieldNames(workspaceId: UUID): IO[Set[String]] =
    sql"select lower(name) from public.custom_field where workspace_id = $workspaceId"
      .query[String]
      .to[List]
      .transact(transactor)
      .map(_.toSet)

  // -- verification + suppression (Phase 3.3) -----------------------------------

  /** Applies a verification result to the contact. */
  def applyVerification(
      workspaceId: UUID,
      contactId: UUID,
      status: String,
      provider: String,
      checkedAt: OffsetDateTime
  ): IO[Int] =
    sql"""
      update public.contact
      set verification_status = ${status}::public.contact_verification,
          verification_provider = $provider,
          verification_checked_at = $checkedAt
      where workspace_id = $workspaceId and id = $contactId
    """.update.run.transact(transactor)

  final case class SuppressionView(
      id: UUID,
      email: String,
      contactId: Option[UUID],
      reason: String,
      source: String,
      createdAt: OffsetDateTime
  )

  def addSuppression(
      workspaceId: UUID,
      email: String,
      contactId: Option[UUID],
      reason: String,
      source: String,
      actor: UUID
  ): IO[SuppressionView] =
    (for
      id <- sql"""
        insert into public.suppression (workspace_id, email, contact_id, reason, source, created_by)
        values ($workspaceId, $email, ${contactId}, ${reason}::public.suppression_reason,
                ${source}::public.suppression_source, $actor)
        on conflict (workspace_id, email) do update set reason = excluded.reason
        returning id
      """.query[UUID].unique
      row <- sql"""
        select id, email, contact_id, reason::text, source::text, created_at
        from public.suppression where id = $id
      """.query[SuppressionView].unique
    yield row).transact(transactor)

  def listSuppressions(
      workspaceId: UUID,
      q: Option[String],
      limit: Int = 100
  ): IO[List[SuppressionView]] =
    val base = fr"""
      select id, email, contact_id, reason::text, source::text, created_at
      from public.suppression
      where workspace_id = $workspaceId
    """
    val filter = q
      .map { term =>
        val like = s"%${term.toLowerCase}%"
        fr" and lower(email) like $like"
      }
      .getOrElse(Fragment.empty)
    (base ++ filter ++ fr"order by created_at desc limit $limit")
      .query[SuppressionView]
      .to[List]
      .transact(transactor)

  def findSuppression(
      workspaceId: UUID,
      suppressionId: UUID
  ): IO[Option[SuppressionView]] =
    sql"""
      select id, email, contact_id, reason::text, source::text, created_at
      from public.suppression where workspace_id = $workspaceId and id = $suppressionId
    """.query[SuppressionView].option.transact(transactor)

  def deleteSuppression(workspaceId: UUID, suppressionId: UUID): IO[Boolean] =
    sql"delete from public.suppression where workspace_id = $workspaceId and id = $suppressionId".update.run
      .map(_ > 0)
      .transact(transactor)

  /** Bulk suppressed-email check for the import pipeline. */
  def suppressedEmails(
      workspaceId: UUID,
      emails: List[String]
  ): IO[Set[String]] =
    if emails.isEmpty then IO.pure(Set.empty)
    else
      val inList = emails
        .map(e => fr"$e")
        .reduceOption((a, b) => a ++ fr", " ++ b)
        .getOrElse(Fragment.empty)
      (fr"select email from public.suppression where workspace_id = $workspaceId and email in (" ++ inList ++ fr")")
        .query[String]
        .to[List]
        .transact(transactor)
        .map(_.toSet)

  /** Reactivates a contact (state -> active) when its suppression is removed.
    */
  def reactivateContact(workspaceId: UUID, contactId: UUID): IO[Int] =
    sql"""
      update public.contact set state = 'active'
      where workspace_id = $workspaceId and id = $contactId and state <> 'active'
    """.update.run.transact(transactor)

  /** Sets a contact's lifecycle state (suppression side-effects). */
  def setContactState(
      workspaceId: UUID,
      contactId: UUID,
      state: String
  ): IO[Unit] =
    sql"""
      update public.contact set state = ${state}::public.contact_state
      where workspace_id = $workspaceId and id = $contactId
    """.update.run.void.transact(transactor)

  /** Data-quality dashboard counters (Phase 3.4). */
  def dataQuality(workspaceId: UUID): IO[Json] =
    sql"""
      select
        count(*)::bigint,
        count(*) filter (where verification_status = 'valid')::bigint,
        count(*) filter (where verification_status = 'risky')::bigint,
        count(*) filter (where verification_status = 'invalid')::bigint,
        count(*) filter (where verification_status = 'unknown')::bigint,
        count(*) filter (where state = 'active')::bigint,
        count(*) filter (where state = 'unsubscribed')::bigint,
        count(*) filter (where state = 'bounced')::bigint,
        count(*) filter (where state = 'complained')::bigint,
        count(*) filter (where first_name is null and last_name is null)::bigint,
        count(*) filter (where company_id is null)::bigint
      from public.contact
      where workspace_id = $workspaceId
    """
      .query[(Long, Long, Long, Long, Long, Long, Long, Long, Long, Long, Long)]
      .unique
      .transact(transactor)
      .map { t =>
        Json.obj(
          "total" -> Json.fromLong(t._1),
          "valid" -> Json.fromLong(t._2),
          "risky" -> Json.fromLong(t._3),
          "invalid" -> Json.fromLong(t._4),
          "unknown" -> Json.fromLong(t._5),
          "active" -> Json.fromLong(t._6),
          "unsubscribed" -> Json.fromLong(t._7),
          "bounced" -> Json.fromLong(t._8),
          "complained" -> Json.fromLong(t._9),
          "missingName" -> Json.fromLong(t._10),
          "missingCompany" -> Json.fromLong(t._11)
        )
      }

  /** Bulk tag for a segment's member set (fragment-based WHERE). */
  def bulkTagByFragment(
      workspaceId: UUID,
      tagId: UUID,
      where: Fragment
  ): IO[Int] =
    (fr"""
      insert into public.contact_tag (contact_id, tag_id)
      select c.id, $tagId
      from public.contact c
      left join public.company co on co.id = c.company_id
      where c.workspace_id = $workspaceId and """ ++ where ++
      fr"on conflict (contact_id, tag_id) do nothing").update.run
      .transact(transactor)

  /** Merges source into kept: fills empty kept fields from source, moves tags +
    * list memberships + provenance, deletes the source contact. Returns
    * (filledFields, movedTags, movedListMemberships).
    */
  def mergeContacts(
      workspaceId: UUID,
      keptId: UUID,
      sourceId: UUID
  ): IO[(Int, Int, Int)] =
    (for
      keptOpt <-
        (contactSelect ++ fr"where c.workspace_id = $workspaceId and c.id = $keptId")
          .query[ContactView]
          .option
      kept <- keptOpt match
        case None =>
          FC.raiseError(new IllegalStateException("kept contact missing"))
        case Some(view) => FC.pure(view)
      srcFields <- sql"""
        select first_name, last_name, job_title, phone, region, timezone, website, notes, company_id
        from public.contact
        where workspace_id = $workspaceId and id = $sourceId
      """
        .query[
          (
              Option[String],
              Option[String],
              Option[String],
              Option[String],
              Option[String],
              Option[String],
              Option[String],
              Option[String],
              Option[UUID]
          )
        ]
        .option
      filled <- srcFields match
        case None      => FC.pure(0)
        case Some(src) =>
          val pairs = List(
            ("first_name", src._1, kept.firstName),
            ("last_name", src._2, kept.lastName),
            ("job_title", src._3, kept.jobTitle),
            ("phone", src._4, kept.phone),
            ("region", src._5, kept.region),
            ("timezone", src._6, kept.timezone),
            ("website", src._7, kept.website),
            ("notes", src._8, kept.notes)
          )
          val fills = pairs.collect {
            case (col, srcVal, None) if srcVal.nonEmpty =>
              Fragment.const(s"$col = ") ++ fr"$srcVal"
          }
          val companyFill: List[Fragment] =
            (kept.companyId, src._9) match
              case (None, Some(companyId)) => List(fr"company_id = $companyId")
              case _                       => Nil
          val allFills = fills ++ companyFill
          if allFills.isEmpty then FC.pure(0)
          else
            val clause = allFills.reduce((a, b) => a ++ fr", " ++ b)
            (fr"update public.contact set " ++ clause ++ fr"where workspace_id = $workspaceId and id = $keptId").update.run
              .map(n => if n > 0 then allFills.size else 0)
      movedTags <-
        sql"""
          insert into public.contact_tag (contact_id, tag_id)
          select $keptId, ct.tag_id from public.contact_tag ct where ct.contact_id = $sourceId
          on conflict (contact_id, tag_id) do nothing
          returning tag_id
        """.query[UUID].to[List].map(_.size)
      _ <-
        sql"delete from public.contact_tag where contact_id = $sourceId".update.run
      movedLists <-
        sql"""
          insert into public.list_membership (list_id, contact_id, added_at)
          select lm.list_id, $keptId, lm.added_at from public.list_membership lm where lm.contact_id = $sourceId
          on conflict (list_id, contact_id) do nothing
          returning list_id
        """.query[UUID].to[List].map(_.size)
      _ <-
        sql"delete from public.list_membership where contact_id = $sourceId".update.run
      _ <- sql"""
        update public.source_provenance set contact_id = $keptId where contact_id = $sourceId
      """.update.run
      _ <-
        sql"delete from public.contact where workspace_id = $workspaceId and id = $sourceId".update.run
    yield (filled, movedTags, movedLists)).transact(transactor)

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
