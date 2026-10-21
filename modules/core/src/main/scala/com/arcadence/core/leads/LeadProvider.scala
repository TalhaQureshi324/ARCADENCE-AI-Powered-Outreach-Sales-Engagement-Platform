package com.arcadence.core.leads

import cats.effect.IO

import java.time.OffsetDateTime
import java.util.UUID

/** Lead data provider framework (Phase 10.1). Providers integrate only
  * under a licensed data agreement; each adapter normalizes its provider's
  * field formats into [[DiscoveredContact]] and preserves provenance,
  * confidence, retrieval dates and permitted-use metadata.
  *
  * SandboxProvider returns a fixed, clearly-labeled sandbox dataset for
  * contract tests and UI development. It NEVER fabricates live leads and
  * its contacts are marked `sandbox_only` — they may not be emailed.
  */
object LeadProvider:

  final case class SearchFilters(
      industry: Option[String] = None,
      region: Option[String] = None,
      jobTitle: Option[String] = None,
      companySize: Option[String] = None,
      technology: Option[String] = None,
      page: Int = 1
  )

  final case class DiscoveredContact(
      providerContactId: String,
      email: Option[String],
      emailVerification: String, // verified | risky | unverified | unknown
      firstName: Option[String],
      lastName: Option[String],
      jobTitle: Option[String],
      companyName: Option[String],
      companyDomain: Option[String],
      industry: Option[String],
      region: Option[String],
      companySize: Option[String],
      confidence: Int,
      permittedUse: String // licensed | sandbox_only
  )

  final case class Page(
      contacts: List[DiscoveredContact],
      hasNext: Boolean
  )

  sealed trait ProviderError
  case class Timeout(detail: String) extends ProviderError
  case class RateLimited(retryAfterSeconds: Int) extends ProviderError
  case class QuotaExceeded(detail: String) extends ProviderError
  case class Unauthorized(detail: String) extends ProviderError
  case class BadRequest(detail: String) extends ProviderError
  case class Unavailable(detail: String) extends ProviderError

  trait Provider:
    def name: String
    /** Supported filter keys; the UI uses this to gray out unsupported
      * filters and the API explains when a filter can't be applied.
      */
    def supportedFilters: Set[String]
    def search(filters: SearchFilters, page: Int): IO[Either[ProviderError, Page]]
    def enrich(email: String, domain: Option[String]): IO[Either[ProviderError, Option[DiscoveredContact]]]

  /** Approved sandbox adapter: deterministic fixtures labeled sandbox_only. */
  object SandboxProvider extends Provider:
    val name = "sandbox"
    val fixtures: List[DiscoveredContact] = List(
      DiscoveredContact("sb-1", Some("sandra.sandbox@example-sandbox.test"), "verified",
        Some("Sandra"), Some("Sandbox"), Some("Head of Growth"), Some("Sandbox Co"),
        Some("sandboxco.test"), Some("software"), Some("EU"), Some("11-50"), 90, "sandbox_only"),
      DiscoveredContact("sb-2", None, "unknown",
        Some("Bob"), Some("Bench"), Some("CTO"), Some("Benchware"),
        Some("benchware.test"), Some("software"), Some("US"), Some("51-200"), 60, "sandbox_only"),
      DiscoveredContact("sb-3", Some("carla.contact@example-sandbox.test"), "risky",
        Some("Carla"), Some("Contact"), Some("Ops Manager"), Some("Sandbox Co"),
        Some("sandboxco.test"), Some("logistics"), Some("EU"), Some("11-50"), 75, "sandbox_only")
    )

    override def supportedFilters: Set[String] =
      Set("industry", "region", "jobTitle", "companySize")

    override def search(filters: SearchFilters, page: Int): IO[Either[ProviderError, Page]] =
      val filtered = fixtures.filter { c =>
        filters.industry.forall(v => c.industry.exists(_.equalsIgnoreCase(v))) &&
        filters.region.forall(v => c.region.exists(_.equalsIgnoreCase(v))) &&
        filters.jobTitle.forall(v => c.jobTitle.exists(_.toLowerCase.contains(v.toLowerCase))) &&
        filters.companySize.forall(v => c.companySize.exists(_.equalsIgnoreCase(v)))
      }
      if page > 1 then IO.pure(Right(Page(Nil, hasNext = false)))
      else IO.pure(Right(Page(filtered, hasNext = false)))

    override def enrich(email: String, domain: Option[String]): IO[Either[ProviderError, Option[DiscoveredContact]]] =
      IO.pure(Right(fixtures.find(c => c.email.exists(_.equalsIgnoreCase(email)))))
