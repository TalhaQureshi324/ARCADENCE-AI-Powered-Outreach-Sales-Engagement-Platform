package com.arcadence.core.db

/** Parses Supabase/JDBC-style PostgreSQL URLs.
  *
  * Accepts `postgresql://user:pass@host:port/db` and the `jdbc:` prefixed form,
  * returning explicit credentials: empty-string user/password passed to the
  * PostgreSQL driver corrupt URL-embedded credential parsing (the driver reads
  * the whole `user:pass@host` as the hostname).
  */
object DbUrl:
  private val Pattern =
    "^(?:jdbc:)?postgresql://(?:([^:@/]*)(?::([^@/]*))?@)?([^:/]+)(?::(\\d+))?/([^?]+)".r

  final case class Parsed(user: String, password: String, jdbcUrl: String)

  def parse(raw: String): Either[String, Parsed] =
    raw match
      case Pattern(user, password, host, port, db) =>
        Right(
          Parsed(
            Option(user).getOrElse("postgres"),
            Option(password).getOrElse("postgres"),
            s"jdbc:postgresql://$host:${Option(port).getOrElse("5432")}/$db"
          )
        )
      case _ => Left(s"Unparseable PostgreSQL URL: $raw")
