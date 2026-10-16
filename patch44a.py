import io

p = 'apps/api/src/main/scala/com/arcadence/api/CampaignRoutes.scala'
s = io.open(p, encoding='utf-8').read()

def must(cond, msg):
    if not cond:
        raise SystemExit('FAIL: ' + msg)

def rep(old, new):
    global s
    must(old in s, 'MISS: ' + old[:70])
    s = s.replace(old, new, 1)

# ---- 1) DTOs ----
rep('''  final case class GraphValidationDto(valid: Boolean, errors: List[String])
  object GraphValidationDto:
    given Encoder[GraphValidationDto] = deriveEncoder
    given Decoder[GraphValidationDto] = deriveDecoder
    given Schema[GraphValidationDto] = Schema.derived''',
'''  final case class GraphValidationDto(valid: Boolean, errors: List[String])
  object GraphValidationDto:
    given Encoder[GraphValidationDto] = deriveEncoder
    given Decoder[GraphValidationDto] = deriveDecoder
    given Schema[GraphValidationDto] = Schema.derived

  final case class SimulateRequest(contactId: UUID)
  object SimulateRequest:
    given Encoder[SimulateRequest] = deriveEncoder
    given Decoder[SimulateRequest] = deriveDecoder
    given Schema[SimulateRequest] = Schema.derived

  final case class PlannedStepDto(stepKey: String, stepType: String, name: String, plannedAt: OffsetDateTime, note: String)
  object PlannedStepDto:
    given Encoder[PlannedStepDto] = deriveEncoder
    given Decoder[PlannedStepDto] = deriveDecoder
    given Schema[PlannedStepDto] = Schema.derived

  final case class SimulationDto(timezone: String, plan: List[PlannedStepDto])
  object SimulationDto:
    given Encoder[SimulationDto] = deriveEncoder
    given Decoder[SimulationDto] = deriveDecoder
    given Schema[SimulationDto] = Schema.derived

  final case class PreflightIssue(code: String, message: String)
  object PreflightIssue:
    given Encoder[PreflightIssue] = deriveEncoder
    given Decoder[PreflightIssue] = deriveDecoder
    given Schema[PreflightIssue] = Schema.derived

  final case class PreflightDto(blockers: List[PreflightIssue], warnings: List[PreflightIssue])
  object PreflightDto:
    given Encoder[PreflightDto] = deriveEncoder
    given Decoder[PreflightDto] = deriveDecoder
    given Schema[PreflightDto] = Schema.derived''')

# ---- 2) endpoints ----
rep('''  private val listVersions = endpoint.get''',
'''  private val simulate = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "campaigns" / path[UUID]("campaignId") / "simulate")
    .in(header[Option[String]]("Authorization"))
    .in(jsonBody[SimulateRequest])
    .out(jsonBody[SimulationDto])
    .errorOut(errorOut)

  private val preflight = endpoint.post
    .in("api" / "v1" / "workspaces" / path[UUID]("workspaceId") / "campaigns" / path[UUID]("campaignId") / "preflight")
    .in(header[Option[String]]("Authorization"))
    .out(jsonBody[PreflightDto])
    .errorOut(errorOut)

  private val listVersions = endpoint.get''')

# ---- 3) scheduling columns on the row/DTO/PATCH ----
rep('''      openTracking: Boolean,
      clickTracking: Boolean,
      threadMode: Boolean,''',
'''      openTracking: Boolean,
      clickTracking: Boolean,
      threadMode: Boolean,
      sendWindowStart: java.time.LocalTime,
      sendWindowEnd: java.time.LocalTime,
      businessDaysOnly: Boolean,''')

rep('''             daily_cap, open_tracking, click_tracking, thread_mode,
             activated_at''',
'''             daily_cap, open_tracking, click_tracking, thread_mode,
             send_window_start, send_window_end, business_days_only,
             activated_at''')

rep('''      Boolean,
      Boolean,
      Boolean,
      Option[OffsetDateTime],''',
'''      Boolean,
      Boolean,
      Boolean,
      java.time.LocalTime,
      java.time.LocalTime,
      Boolean,
      Option[OffsetDateTime],''')

rep('''      t._13,
      t._14,
      t._15
    )
  }''',
'''      t._13,
      t._14,
      t._15,
      t._16,
      t._17,
      t._18
    )
  }''')

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('part 1 ok')
