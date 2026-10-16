package com.arcadence.api

import io.circe.Json
import munit.FunSuite

import java.time.{LocalDateTime, LocalTime, ZoneId, ZonedDateTime}

/** Scheduling math and branch evaluation (Phase 4.4). */
final class ScheduleSpec extends FunSuite:

  private val zone = ZoneId.of("America/New_York")
  private val winStart = LocalTime.of(9, 0)
  private val winEnd = LocalTime.of(17, 0)

  test("nextSendMoment honors the send window and skips weekends") {
    // Friday 18:00 NY → outside window → Monday 09:00 when weekends skipped.
    val fridayEvening = ZonedDateTime.of(2026, 10, 16, 18, 0, 0, 0, zone)
    val next = Schedule.nextSendMoment(
      fridayEvening,
      winStart,
      winEnd,
      skipWeekends = true
    )
    assertEquals(next.getDayOfWeek, java.time.DayOfWeek.MONDAY)
    assertEquals(next.toLocalTime, LocalTime.of(9, 0))

    // Same instant without weekend skip → Saturday 09:00.
    val sat = Schedule.nextSendMoment(
      fridayEvening,
      winStart,
      winEnd,
      skipWeekends = false
    )
    assertEquals(sat.getDayOfWeek, java.time.DayOfWeek.SATURDAY)
  }

  test("nextSendMoment keeps moments already inside the window") {
    val wednesdayNoon = ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, zone)
    val next = Schedule.nextSendMoment(
      wednesdayNoon,
      winStart,
      winEnd,
      skipWeekends = true
    )
    assertEquals(next, wednesdayNoon)
  }

  test("addWait skips weekends when asked") {
    val friday = ZonedDateTime.of(2026, 10, 16, 10, 0, 0, 0, zone)
    assertEquals(
      Schedule.addWait(friday, 1, skipWeekends = true).getDayOfWeek,
      java.time.DayOfWeek.MONDAY
    )
    assertEquals(
      Schedule.addWait(friday, 1, skipWeekends = false).getDayOfWeek,
      java.time.DayOfWeek.SATURDAY
    )
  }

  test(
    "evaluateCondition covers the whitelisted operators case-insensitively"
  ) {
    val resolver: String => Option[String] = {
      case "region"          => Some("EU")
      case "custom.industry" => Some("SaaS")
      case _                 => None
    }
    def cond(f: String, o: String, v: String): Json =
      Json.obj(
        "field" -> Json.fromString(f),
        "operator" -> Json.fromString(o),
        "value" -> Json.fromString(v)
      )

    assert(Schedule.evaluateCondition(cond("region", "eq", "eu"), resolver))
    assert(!Schedule.evaluateCondition(cond("region", "eq", "US"), resolver))
    assert(Schedule.evaluateCondition(cond("region", "neq", "US"), resolver))
    assert(
      Schedule.evaluateCondition(
        cond("region", "in", "us , EU , apac"),
        resolver
      )
    )
    assert(Schedule.evaluateCondition(cond("region", "not_in", "us"), resolver))
    assert(
      Schedule.evaluateCondition(
        cond("custom.industry", "contains", "saas"),
        resolver
      )
    )
    assert(
      !Schedule.evaluateCondition(cond("region", "contains", "apac"), resolver)
    )
  }

  test("simulate walks a linear legacy chain honoring waits") {
    val steps = List(
      Schedule.SimStep(
        "1",
        1,
        "email",
        "Intro",
        None,
        businessDays = false,
        condition = None
      ),
      Schedule.SimStep(
        "2",
        2,
        "wait",
        "Wait",
        Some(2),
        businessDays = false,
        condition = None
      ),
      Schedule.SimStep(
        "3",
        3,
        "email",
        "Follow up",
        None,
        businessDays = false,
        condition = None
      )
    )
    val from = ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, zone) // Wednesday
    val plan = Schedule.simulate(
      steps,
      Nil,
      zone,
      winStart,
      winEnd,
      true,
      _ => None,
      from
    )
    assert(plan.isRight)
    val items = plan.toOption.getOrElse(Nil)
    assertEquals(items.map(_.stepKey), List("1", "2", "3"))
    assertEquals(items(0).plannedAt, from)
    assertEquals(
      items(1).plannedAt.toLocalDate,
      java.time.LocalDate.of(2026, 10, 16)
    )
    assertEquals(
      items(2).plannedAt.toLocalDate,
      java.time.LocalDate.of(2026, 10, 16)
    )
  }

  test("simulate routes branches on the resolved contact value") {
    val steps = List(
      Schedule.SimStep(
        "r",
        1,
        "branch",
        "Region?",
        None,
        businessDays = false,
        condition = Some(
          Json.obj(
            "field" -> Json.fromString("region"),
            "operator" -> Json.fromString("eq"),
            "value" -> Json.fromString("EU")
          )
        )
      ),
      Schedule.SimStep(
        "eu",
        2,
        "email",
        "EU",
        None,
        businessDays = false,
        condition = None
      ),
      Schedule.SimStep(
        "other",
        3,
        "email",
        "Other",
        None,
        businessDays = false,
        condition = None
      )
    )
    val edges = List(
      Schedule.SimEdge("r", "eu", Some("yes")),
      Schedule.SimEdge("r", "other", Some("no"))
    )
    val resolver: String => Option[String] = {
      case "region" => Some("EU"); case _ => None
    }
    val from = ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, zone)
    val plan = Schedule.simulate(
      steps,
      edges,
      zone,
      winStart,
      winEnd,
      true,
      resolver,
      from
    )
    val keys = plan.toOption.getOrElse(Nil).map(_.stepKey)
    assertEquals(keys, List("r", "eu"))

    val resolverUS: String => Option[String] = {
      case "region" => Some("US"); case _ => None
    }
    val plan2 = Schedule.simulate(
      steps,
      edges,
      zone,
      winStart,
      winEnd,
      true,
      resolverUS,
      from
    )
    val keys2 = plan2.toOption.getOrElse(Nil).map(_.stepKey)
    assertEquals(keys2, List("r", "other"))
  }
