package com.arcadence.core.email

import munit.FunSuite

/** Account state machine: label parsing and legal transition matrix. */
final class EmailAccountStatusSpec extends FunSuite:

  test("parses every enum label and rejects unknown ones") {
    EmailAccountStatus.All.foreach { status =>
      assertEquals(EmailAccountStatus.fromLabel(status.label), Right(status))
    }
    assertEquals(
      EmailAccountStatus.fromLabel("nonsense"),
      Left("Unknown account status: nonsense")
    )
  }

  test("labels match the Postgres connection_status enum") {
    assertEquals(
      EmailAccountStatus.All.map(_.label),
      List(
        "connected",
        "reconnect_required",
        "rate_limited",
        "suspended",
        "error",
        "disconnected"
      )
    )
  }

  test("operator pause/resume only between connected and suspended") {
    import EmailAccountStatus.*
    assert(canTransitionTo(Connected, Suspended))
    assert(canTransitionTo(Suspended, Connected))
    assert(!canTransitionTo(Disconnected, Suspended))
    assert(!canTransitionTo(Error, Suspended))
  }

  test("disconnect allowed from every active state, never from disconnected") {
    import EmailAccountStatus.*
    val active =
      List(Connected, ReconnectRequired, RateLimited, Suspended, Error)
    active.foreach(from =>
      assert(canTransitionTo(from, Disconnected), s"$from -> disconnected")
    )
    assert(!canTransitionTo(Disconnected, Disconnected))
  }

  test("reconnect flow restores connected from degraded states") {
    import EmailAccountStatus.*
    List(ReconnectRequired, RateLimited, Error).foreach(from =>
      assert(canTransitionTo(from, Connected), s"$from -> connected")
    )
  }

  test("health degradation paths exist from active states") {
    import EmailAccountStatus.*
    assert(canTransitionTo(Connected, ReconnectRequired))
    assert(canTransitionTo(Connected, RateLimited))
    assert(canTransitionTo(Connected, Error))
    assert(canTransitionTo(Suspended, Error))
  }

  test("no transition escapes Disconnected without an explicit connect flow") {
    import EmailAccountStatus.*
    EmailAccountStatus.All
      .filter(_ != Disconnected)
      .foreach(to =>
        assert(
          !canTransitionTo(Disconnected, to),
          s"disconnected -> $to must be illegal"
        )
      )
  }
