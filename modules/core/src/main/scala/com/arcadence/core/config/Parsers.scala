package com.arcadence.core.config

import cats.data.ValidatedNel
import cats.syntax.all.*

import scala.concurrent.duration.*
import scala.util.Try

/** Shared, total environment parsers. Parsers never throw: every failure
  * becomes a validation message.
  */
private[config] object Parsers:

  private val validLogLevels = Set("DEBUG", "INFO", "WARN", "ERROR")

  def port(
      env: Map[String, String],
      name: String,
      default: Int
  ): ValidatedNel[String, Int] =
    raw(env, name) match
      case None        => default.validNel
      case Some(value) =>
        Try(value.toInt).toOption.filter(p => p >= 1 && p <= 65535) match
          case Some(p) => p.validNel
          case None    =>
            s"$name must be an integer in [1, 65535], got '$value'".invalidNel

  def logLevel(
      env: Map[String, String],
      name: String,
      default: String
  ): ValidatedNel[String, String] =
    raw(env, name) match
      case None        => default.validNel
      case Some(value) =>
        val upper = value.toUpperCase
        if validLogLevels.contains(upper) then upper.validNel
        else
          s"$name must be one of ${validLogLevels.mkString(", ")}, got '$value'".invalidNel

  def positiveMillis(
      env: Map[String, String],
      name: String,
      default: Long
  ): ValidatedNel[String, FiniteDuration] =
    raw(env, name) match
      case None        => default.millis.validNel
      case Some(value) =>
        Try(value.toLong).toOption.filter(_ > 0) match
          case Some(ms) => ms.millis.validNel
          case None     =>
            s"$name must be a positive integer of milliseconds, got '$value'".invalidNel

  private def raw(env: Map[String, String], name: String): Option[String] =
    env.get(name).map(_.trim).filter(_.nonEmpty)
