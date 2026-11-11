package com.arcadence.worker

import munit.FunSuite

/** Phase 15.3 staging safety: SENDING_ENABLED parsing/warnings and
  * TEST_RECIPIENT_ALLOWLIST normalization.
  */
final class SendWorkerConfigSpec extends FunSuite:

  test("SENDING_ENABLED defaults to enabled with no warnings") {
    val cfg = SendWorker.Config.fromEnv(Map.empty)
    assertEquals(cfg.sendingEnabled, true)
    assertEquals(cfg.warnings, Nil)
  }

  test("SENDING_ENABLED=false / no / 0 disables sending") {
    for v <- List("false", "no", "0", "False", " NO ")
    do
      val cfg = SendWorker.Config.fromEnv(Map("SENDING_ENABLED" -> v))
      assertEquals(cfg.sendingEnabled, false, s"value: $v")
      assertEquals(cfg.warnings, Nil, s"value: $v")
  }

  test("SENDING_ENABLED=true / yes / 1 keeps sending enabled") {
    for v <- List("true", "yes", "1", "YES")
    do
      val cfg = SendWorker.Config.fromEnv(Map("SENDING_ENABLED" -> v))
      assertEquals(cfg.sendingEnabled, true, s"value: $v")
  }

  test("invalid SENDING_ENABLED falls back to enabled with a warning") {
    val cfg = SendWorker.Config.fromEnv(Map("SENDING_ENABLED" -> "banana"))
    assertEquals(cfg.sendingEnabled, true)
    assert(
      cfg.warnings.exists(_.contains("SENDING_ENABLED")),
      s"expected a warning naming SENDING_ENABLED, got ${cfg.warnings}"
    )
  }

  test("parseAllowlist: unset or empty means unrestricted") {
    assertEquals(SendWorker.Config.parseAllowlist(Map.empty), None)
    assertEquals(
      SendWorker.Config.parseAllowlist(
        Map("TEST_RECIPIENT_ALLOWLIST" -> " , ")
      ),
      None
    )
  }

  test("parseAllowlist lowercases, trims and drops empties") {
    assertEquals(
      SendWorker.Config.parseAllowlist(
        Map("TEST_RECIPIENT_ALLOWLIST" -> " A@X.test ,, b@Y.test,")
      ),
      Some(Set("a@x.test", "b@y.test"))
    )
  }
