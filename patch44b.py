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

# campaignDto: scheduling fields
rep('''      threadMode = c.threadMode,
      stepCount = stepCount,''',
'''      threadMode = c.threadMode,
      sendWindowStart = c.sendWindowStart.toString,
      sendWindowEnd = c.sendWindowEnd.toString,
      businessDaysOnly = c.businessDaysOnly,
      stepCount = stepCount,''')

rep('''      threadMode: Boolean,
      stepCount: Int,''',
'''      threadMode: Boolean,
      sendWindowStart: String,
      sendWindowEnd: String,
      businessDaysOnly: Boolean,
      stepCount: Int,''')

# PATCH accepts scheduling (draft-only, like structural)
rep('''      threadMode: Option[Boolean] = None,
      steps: Option[List[StepInput]] = None,''',
'''      threadMode: Option[Boolean] = None,
      sendWindowStart: Option[String] = None,
      sendWindowEnd: Option[String] = None,
      businessDaysOnly: Option[Boolean] = None,
      steps: Option[List[StepInput]] = None,''')

rep('''            val structural =
              req.steps.isDefined || req.mailboxIds.isDefined || req.audiences.isDefined || req.threadMode.isDefined''',
'''            val structural =
              req.steps.isDefined || req.mailboxIds.isDefined || req.audiences.isDefined ||
                req.threadMode.isDefined || req.sendWindowStart.isDefined || req.sendWindowEnd.isDefined ||
                req.businessDaysOnly.isDefined''')

# validate window strings + pass to fields update
rep('''              val graphErrors: List[String] = (req.edges, req.steps) match''',
'''              val windowCheck: Either[String, Unit] =
                for
                  ws <- req.sendWindowStart match
                    case None => Right(java.time.LocalTime.of(9, 0))
                    case Some(v) =>
                      scala.util.Try(java.time.LocalTime.parse(v)).toEither.left.map(_ => s"Invalid send window start: $v")
                  we <- req.sendWindowEnd match
                    case None => Right(java.time.LocalTime.of(17, 0))
                    case Some(v) =>
                      scala.util.Try(java.time.LocalTime.parse(v)).toEither.left.map(_ => s"Invalid send window end: $v")
                yield
                  val _ = ws
                  val _2 = we
                  ()

              windowCheck match
                case Left(error) =>
                  IO.pure(Left(failed(ApiError.Invalid(error))))
                case Right(()) =>
              val graphErrors: List[String] = (req.edges, req.steps) match''')

rep('''                              req.timezone,
                              req.dailyCap,
                              req.threadMode
                            )''',
'''                              req.timezone,
                              req.dailyCap,
                              req.threadMode,
                              req.sendWindowStart.map(v => java.time.LocalTime.parse(v)),
                              req.sendWindowEnd.map(v => java.time.LocalTime.parse(v)),
                              req.businessDaysOnly
                            )''')

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('part 2 ok')
