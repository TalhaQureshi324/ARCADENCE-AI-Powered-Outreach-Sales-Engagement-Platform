import sbt._

// ---------------------------------------------------------------------------
// Arcadence build — Scala 3 modular monolith.
// Modules live in modules/ (shared libraries) and apps/ (runnable processes).
// Versions are pinned from Maven Central release metadata (see docs/DECISIONS.md, ADR-0015).
// ---------------------------------------------------------------------------

lazy val V = new {
  val catsCore        = "2.13.0"
  val catsEffect      = "3.7.1"
  val http4s          = "0.23.38"
  val tapir           = "1.13.33"
  val circe           = "0.14.17"
  val doobie          = "1.0.0-RC12"
  val postgres        = "42.7.14"
  val javaJwt         = "4.6.1"
  val jwksRsa         = "0.24.1"
  val log4cats        = "2.8.0"
  val logback         = "1.6.5"
  val munit           = "1.3.6"
  val munitCatsEffect = "2.2.1"
  val angusMail       = "2.0.3"
  val jakartaMail     = "2.1.3"
  val greenMail       = "2.1.3"
}

ThisBuild / organization  := "com.arcadence"
ThisBuild / scalaVersion  := "3.3.8"
ThisBuild / version       := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")

// Docker images built with `sbt api/docker:publishLocal` (sbt-native-packager).
// Image names resolve to arcadence/api:<version> and arcadence/worker:<version>.
ThisBuild / dockerBaseImage := "eclipse-temurin:21-jre-noble"
ThisBuild / dockerUsername  := Some("arcadence")

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-encoding",
    "utf-8",
    "-feature",
    "-unchecked",
    "-deprecation",
    "-Wunused:all",
    "-language:higherKinds"
  ),
  Test / fork := true,
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit"             % V.munit           % Test,
    "org.typelevel" %% "munit-cats-effect" % V.munitCatsEffect % Test
  )
)

lazy val core = (project in file("modules/core"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    commonSettings,
    name := "arcadence-core",
    buildInfoPackage     := "com.arcadence.core.build",
    buildInfoOptions += BuildInfoOption.BuildTime,
    libraryDependencies ++= Seq(
      "org.typelevel"  %% "cats-core"        % V.catsCore,
      "org.typelevel"  %% "cats-effect"      % V.catsEffect,
      "org.tpolecat"   %% "doobie-core"      % V.doobie,
      "org.postgresql" % "postgresql"        % V.postgres,
      // Provider adapters (Phase 2): HTTP access to Google/Microsoft/SMTP worlds
      "org.http4s"     %% "http4s-client"       % V.http4s,
      "org.http4s"     %% "http4s-ember-client" % V.http4s,
      "org.http4s"     %% "http4s-circe"        % V.http4s,
      "io.circe"       %% "circe-core"          % V.circe,
      "io.circe"       %% "circe-parser"        % V.circe,
      // SMTP/IMAP adapter (Phase 2.4)
      "org.eclipse.angus" % "angus-mail"        % V.angusMail,
      "jakarta.mail"      % "jakarta.mail-api"  % V.jakartaMail
    ),
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % V.http4s % Test,
      "com.icegreen" % "greenmail" % V.greenMail % Test
    )
  )

lazy val api = (project in file("apps/api"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(core)
  .settings(
    commonSettings,
    name := "arcadence-api",
    libraryDependencies ++= Seq(
      "org.typelevel"               %% "cats-effect"         % V.catsEffect,
      "org.http4s"                  %% "http4s-ember-server" % V.http4s,
      "org.http4s"                  %% "http4s-ember-client" % V.http4s,
      "org.http4s"                  %% "http4s-dsl"          % V.http4s,
      "com.softwaremill.sttp.tapir" %% "tapir-core"          % V.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % V.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"    % V.tapir,
      "io.circe"                    %% "circe-generic"       % V.circe,
      "org.tpolecat"                %% "doobie-core"         % V.doobie,
      "org.tpolecat"                %% "doobie-postgres"     % V.doobie,
      "com.auth0"                   % "java-jwt"             % V.javaJwt,
      "com.auth0"                   % "jwks-rsa"             % V.jwksRsa,
      "org.typelevel"               %% "log4cats-core"       % V.log4cats,
      "org.typelevel"               %% "log4cats-slf4j"      % V.log4cats,
      "ch.qos.logback"              % "logback-classic"      % V.logback
    ),
    libraryDependencies ++= Seq(
      "com.icegreen" % "greenmail" % V.greenMail % Test
    )
  )

lazy val worker = (project in file("apps/worker"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(core)
  .settings(
    commonSettings,
    name := "arcadence-worker",
    libraryDependencies ++= Seq(
      "org.typelevel"  %% "cats-effect"    % V.catsEffect,
      "org.typelevel"  %% "log4cats-core"  % V.log4cats,
      "org.typelevel"  %% "log4cats-slf4j" % V.log4cats,
      "ch.qos.logback" % "logback-classic" % V.logback
    )
  )

lazy val root = (project in file("."))
  .aggregate(core, api, worker)
  .settings(
    name           := "arcadence",
    publish / skip := true
  )
