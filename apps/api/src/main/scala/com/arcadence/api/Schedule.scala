package com.arcadence.api

import io.circe.Json

import java.time.{DayOfWeek, LocalDateTime, ZoneId, ZonedDateTime}

/** Sending-time scheduling and dry-run simulation (Phase 4.4). Planned times
  * honor the campaign timezone, the daily send window, and weekend skipping;
  * branch steps are evaluated against a REAL contact's data (the same field
  * resolution used for personalization) — simulation therefore requires a
  * contact, otherwise there is nothing honest to branch on.
  */
object Schedule:

  final case class PlannedStep(
      stepKey: String,
      stepType: String,
      name: String,
      plannedAt: ZonedDateTime,
      note: String
  )

  private def isWeekend(d: ZonedDateTime): Boolean =
    d.getDayOfWeek == DayOfWeek.SATURDAY || d.getDayOfWeek == DayOfWeek.SUNDAY

  /** Next moment at/after `from` inside [start, end) on an allowed day. Windows
    * may span midnight (start after end).
    */
  def nextSendMoment(
      from: ZonedDateTime,
      windowStart: java.time.LocalTime,
      windowEnd: java.time.LocalTime,
      skipWeekends: Boolean
  ): ZonedDateTime =
    val t = from.toLocalTime
    val inWindow =
      val normal = windowStart.compareTo(windowEnd) <= 0
      if normal then !t.isBefore(windowStart) && t.isBefore(windowEnd)
      else !t.isBefore(windowStart) || t.isBefore(windowEnd)
    var candidate =
      if inWindow then from
      else
        val base = from
          .withHour(windowStart.getHour)
          .withMinute(windowStart.getMinute)
          .withSecond(0)
          .withNano(0)
        if base.isBefore(from) then base.plusDays(1) else base
    var guard = 0
    while (skipWeekends && isWeekend(candidate)) && guard < 400 do
      guard += 1
      candidate = candidate.plusDays(1)
    candidate

  def addWait(
      from: ZonedDateTime,
      days: Int,
      skipWeekends: Boolean
  ): ZonedDateTime =
    var result = from.plusDays(days.toLong)
    var guard = 0
    while (skipWeekends && isWeekend(result)) && guard < 400 do
      guard += 1
      result = result.plusDays(1)
    result

  /** Evaluates a branch condition against a resolver (same field resolution as
    * personalization). Case-insensitive; in/not_in take comma lists.
    */
  def evaluateCondition(
      condition: Json,
      resolve: String => Option[String]
  ): Boolean =
    condition.asObject match
      case None    => false
      case Some(o) =>
        val field = o("field").flatMap(_.asString).getOrElse("")
        val operator = o("operator").flatMap(_.asString).getOrElse("")
        val value = o("value").flatMap(_.asString).getOrElse("")
        val actual = resolve(field).getOrElse("").toLowerCase
        val expected = value.toLowerCase
        operator match
          case "eq"           => actual == expected
          case "neq"          => actual != expected
          case "contains"     => actual.contains(expected)
          case "not_contains" => !actual.contains(expected)
          case "in"           =>
            expected.split(",").map(_.trim).filter(_.nonEmpty).contains(actual)
          case "not_in" =>
            !expected.split(",").map(_.trim).filter(_.nonEmpty).contains(actual)
          case _ => false

  final case class SimStep(
      key: String,
      position: Int,
      stepType: String,
      name: String,
      waitDays: Option[Int],
      businessDays: Boolean,
      condition: Option[Json]
  )

  final case class SimEdge(
      fromKey: String,
      toKey: String,
      branchKey: Option[String]
  )

  /** Walks the graph from the root producing planned timings. */
  def simulate(
      steps: List[SimStep],
      edges: List[SimEdge],
      zone: ZoneId,
      windowStart: java.time.LocalTime,
      windowEnd: java.time.LocalTime,
      businessDaysOnly: Boolean,
      resolve: String => Option[String],
      from: ZonedDateTime
  ): Either[String, List[PlannedStep]] =
    val byKey = steps.map(s => s.key -> s).toMap
    if edges.isEmpty then
      val ordered = steps.sortBy(_.position)
      val out = List.newBuilder[PlannedStep]
      var t = from
      ordered.foreach { s =>
        s.stepType match
          case "wait" =>
            t = addWait(t, s.waitDays.getOrElse(0), s.businessDays)
            out += PlannedStep(
              s.key,
              "wait",
              s.name,
              t,
              s"waits ${s.waitDays.getOrElse(0)} day(s)"
            )
          case _ =>
            t = nextSendMoment(t, windowStart, windowEnd, businessDaysOnly)
            out += PlannedStep(s.key, s.stepType, s.name, t, "")
      }
      Right(out.result())
    else
      val roots = steps.filter(s => !edges.exists(_.toKey == s.key))
      if roots.size != 1 then
        Left("The sequence needs exactly one starting step.")
      else
        def nextOf(key: String, branchKey: Option[String]): Option[SimStep] =
          edges
            .find(e =>
              e.fromKey == key && branchKey
                .forall(bk => e.branchKey.contains(bk))
            )
            .flatMap(e => byKey.get(e.toKey))

        val out = List.newBuilder[PlannedStep]
        var t = from
        var current = roots.head
        var guard = 0
        var done = false
        while !done && guard < 200 do
          guard += 1
          current.stepType match
            case "wait" =>
              t =
                addWait(t, current.waitDays.getOrElse(0), current.businessDays)
              out += PlannedStep(
                current.key,
                "wait",
                current.name,
                t,
                s"waits ${current.waitDays.getOrElse(0)} day(s)${
                    if current.businessDays then " (business)" else ""
                  }"
              )
              nextOf(current.key, None) match
                case Some(next) => current = next
                case None       => done = true
            case "branch" =>
              val yes = evaluateCondition(
                current.condition.getOrElse(Json.Null),
                resolve
              )
              out += PlannedStep(
                current.key,
                "branch",
                current.name,
                t,
                s"condition ${
                    if yes then "matched → yes" else "not matched → no"
                  }"
              )
              nextOf(current.key, Some(if yes then "yes" else "no")) match
                case Some(next) => current = next
                case None       =>
                  done = true // dead end; validation prevents on save
            case _ =>
              t = nextSendMoment(t, windowStart, windowEnd, businessDaysOnly)
              out += PlannedStep(
                current.key,
                current.stepType,
                current.name,
                t,
                ""
              )
              nextOf(current.key, None) match
                case Some(next) => current = next
                case None       => done = true
        if guard >= 200 then
          Left("The sequence could not be simulated — check for cycles.")
        else Right(out.result())
