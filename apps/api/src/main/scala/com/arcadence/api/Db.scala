package com.arcadence.api

import cats.effect.IO
import com.arcadence.core.db.DbUrl
import doobie.Transactor

object Db:

  /** Builds the shared transactor for a long-lived service (direct/session
    * connections per ADR-0018; transaction pooling would additionally need
    * prepared statements disabled).
    */
  def transactor(databaseUrl: String): IO[Transactor[IO]] =
    IO.fromEither(
      DbUrl
        .parse(databaseUrl)
        .left
        .map(err => new IllegalArgumentException(err))
    ).map { parsed =>
      Transactor.fromDriverManager[IO](
        driver = "org.postgresql.Driver",
        url = parsed.jdbcUrl,
        user = parsed.user,
        password = parsed.password,
        logHandler = None
      )
    }
