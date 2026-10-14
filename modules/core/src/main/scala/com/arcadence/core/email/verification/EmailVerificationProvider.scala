package com.arcadence.core.email.verification

import cats.effect.IO
import javax.naming.Context
import javax.naming.directory.InitialDirContext

import java.time.OffsetDateTime

/** Email verification result (Phase 3.3). `verified` is reserved for licensed
  * providers that actually probe the mailbox — syntax + MX alone can NEVER
  * yield verified (ADR-0024).
  */
enum VerificationStatus:
  case Verified, Risky, Invalid, Unknown

/** Pluggable email verification provider. Implementations: syntax+MX
  * ([[SyntaxMxProvider]]) and licensed external services (added when API
  * credentials exist — MANUAL_SETUP item 12a).
  */
trait EmailVerificationProvider:
  def id: String

  /** Verifies one address; never throws. */
  def verify(email: String): IO[VerificationOutcome]

final case class VerificationOutcome(
    email: String,
    status: VerificationStatus,
    provider: String,
    checkedAt: OffsetDateTime,
    details: String
)

/** Syntax + MX-records provider (the always-available default).
  *
  *   - Syntax failure → Invalid (no DNS work wasted).
  *   - Domain has mail exchangers → Risky (the mailbox existence is unknown).
  *   - Domain has NO mail exchangers (and no A fallback) → Invalid.
  *   - DNS unavailable/errors → Unknown.
  */
final class SyntaxMxProvider extends EmailVerificationProvider:
  import SyntaxMxProvider.*

  val id: String = "syntax_mx"

  def verify(email: String): IO[VerificationOutcome] =
    IO(OffsetDateTime.now).flatMap { now =>
      if !email.matches(EmailPattern) then
        IO.pure(
          VerificationOutcome(
            email,
            VerificationStatus.Invalid,
            id,
            now,
            "syntax check failed"
          )
        )
      else
        val domain = email.substring(email.lastIndexOf('@') + 1)
        lookupMx(domain)
          .map {
            case Some(records) if records.nonEmpty =>
              VerificationOutcome(
                email,
                VerificationStatus.Risky,
                id,
                now,
                s"MX found: ${records.take(2).mkString(", ")}"
              )
            case None =>
              VerificationOutcome(
                email,
                VerificationStatus.Invalid,
                id,
                now,
                s"no MX records for $domain"
              )
          }
          .handleError(t =>
            VerificationOutcome(
              email,
              VerificationStatus.Unknown,
              id,
              now,
              Option(t.getMessage).getOrElse("dns lookup failed").take(160)
            )
          )
    }

object SyntaxMxProvider:
  val EmailPattern = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"

  private val dnsEnv = {
    val env = new java.util.Hashtable[String, String]()
    env.put(
      Context.INITIAL_CONTEXT_FACTORY,
      "com.sun.jndi.dns.DnsContextFactory"
    )
    env.put("com.sun.jndi.dns.timeout.initial", "3000")
    env.put("com.sun.jndi.dns.timeout.retries", "1")
    env
  }

  /** Some(records) when the domain has MX; None when the domain exists but has
    * no mail exchangers; raises on network failure (caller maps to Unknown).
    */
  def lookupMx(domain: String): IO[Option[List[String]]] =
    IO {
      val ctx = new InitialDirContext(dnsEnv)
      try
        val attrs = ctx.getAttributes(domain, Array("MX"))
        val mxAttr = attrs.get("MX")
        if mxAttr == null then None
        else
          import scala.jdk.CollectionConverters.*
          val records =
            mxAttr.getAll.asScala.map(_.toString).toList
          Some(records)
      finally ctx.close()
    }
