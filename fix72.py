import io

p = 'apps/api/src/main/scala/com/arcadence/api/AnalyticsRoutes.scala'
s = io.open(p, encoding='utf-8').read()

old = """.query[(LocalDate, Int, Int, Int, Int)].to[List].transact(xa)
          .map(rows => Right(Json.fromValues(rows.map { r =>
            Json.obj(
              "day" -> Json.fromString(r._1.toString),
              "sends" -> Json.fromInt(r._2),
              "accepted" -> Json.fromInt(r._3),
              "bounceHard" -> Json.fromInt(r._4),
              "bounceSoft" -> Json.fromInt(r._5),
              "replies" -> Json.fromInt(r._6),
              "meetings" -> Json.fromInt(r._7)
            ))*)))"""
new = """.query[(LocalDate, Int, Int, Int, Int, Int, Int)].to[List].transact(xa)
          .map(rows => Right(Json.fromValues(rows.map { r =>
            Json.obj(
              "day" -> Json.fromString(r._1.toString),
              "sends" -> Json.fromInt(r._2),
              "accepted" -> Json.fromInt(r._3),
              "bounceHard" -> Json.fromInt(r._4),
              "bounceSoft" -> Json.fromInt(r._5),
              "replies" -> Json.fromInt(r._6),
              "meetings" -> Json.fromInt(r._7)
            ))*)))"""
assert old in s, 'ts tuple'
s = s.replace(old, new, 1)

old2 = """              def sumOf(xs: List[(LocalDate, Int, Int, Int, Int)], i: Int): Int =
                xs.map { r => i match
                  case 1 => r._2
                  case 2 => r._3
                  case 3 => r._4
                  case 5 => r._6
                  case _ => 0
                }.sum"""
new2 = """              def sumOf(xs: List[(LocalDate, Int, Int, Int, Int, Int, Int)], i: Int): Int =
                xs.map { r => i match
                  case 1 => r._2
                  case 2 => r._3
                  case 3 => r._4
                  case 5 => r._6
                  case _ => 0
                }.sum"""
assert old2 in s, 'sumOf'
s = s.replace(old2, new2, 1)

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
