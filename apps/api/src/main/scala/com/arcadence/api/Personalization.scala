package com.arcadence.api

import io.circe.Json
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

import scala.util.matching.Regex

/** Lead-field personalization (Phase 4.2). Tokens look like `{{first_name}}`,
  * `{{custom.industry}}` or `{{first_name|there}}` (the part after `|` is the
  * fallback). Rendering never fabricates data: a token whose value is absent
  * falls back, and with no fallback stays empty and is reported as missing —
  * the caller decides whether that is fatal.
  *
  * HTML bodies are sanitized with an allowlist (jsoup relaxed) on the way in;
  * plaintext alternatives derive from the sanitized HTML.
  */
object Personalization:

  val TokenPattern: Regex =
    "\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*?)\\s*(?:\\|\\s*([^{}]*?)\\s*)?\\}\\}".r

  /** Fields resolvable from the contact row itself (custom fields go through
    * the `custom.` prefix and are resolved from `contact.custom_fields`).
    */
  val KnownFields: Set[String] = Set(
    "first_name",
    "last_name",
    "email",
    "company",
    "job_title",
    "region",
    "timezone",
    "website",
    "phone"
  )

  final case class Rendered(
      text: String,
      missing: List[String],
      fellBack: List[String]
  )

  /** Distinct tokens in first-appearance order: (field, fallback). */
  def tokens(text: String): List[(String, Option[String])] =
    TokenPattern
      .findAllMatchIn(text)
      .map(m => (m.group(1), Option(m.group(2)).filter(_.nonEmpty)))
      .toList
      .distinctBy(_._1)

  /** Fields that are not known contact fields and not `custom.*`. */
  def unknownTokens(text: String): List[String] =
    tokens(text)
      .map(_._1)
      .filterNot(f => KnownFields(f) || f.startsWith("custom."))

  /** Renders `template`, resolving each field via `resolve`. Sanitization is
    * the caller's job (see [[sanitizeHtml]]) — this only substitutes tokens.
    */
  def render(template: String, resolve: String => Option[String]): Rendered =
    val missing = List.newBuilder[String]
    val fellBack = List.newBuilder[String]
    // Function-form replaceAllIn still routes results through appendReplacement,
    // so `$` and backslash must be escaped or lead data like "$&" would act
    // as replacement syntax.
    def literal(v: String): String = Regex.quoteReplacement(v)
    val out = TokenPattern.replaceAllIn(
      template,
      m => {
        val field = m.group(1)
        val fallback = Option(m.group(2)).filter(_.nonEmpty)
        resolve(field) match
          case Some(v) if v.nonEmpty => literal(v)
          case _                     =>
            fallback match
              case Some(fb) =>
                fellBack += field
                literal(fb)
              case None =>
                missing += field
                ""
      }
    )
    Rendered(out, missing.result(), fellBack.result())

  /** Plain-text fields (subject, preheader): trim + hard length cap. */
  def sanitizeText(text: String): String = text.trim.take(2000)

  /** Allowlist-based HTML sanitization for campaign bodies, signatures and
    * blocks. Scripts, event handlers, iframes, stylesheets and object/embed
    * never survive.
    */
  def sanitizeHtml(html: String): String =
    Jsoup.clean(
      html,
      "",
      Safelist
        .relaxed()
        .addAttributes("a", "target", "rel")
        .addProtocols("a", "href", "mailto")
    )

  /** Derives the plaintext alternative from sanitized HTML: block-level
    * boundaries become line breaks so paragraphs survive.
    */
  def htmlToText(html: String): String =
    val sanitized = sanitizeHtml(html)
    val doc = Jsoup.parse(sanitized)
    doc.outputSettings().prettyPrint(false)
    val withBreaks = doc
      .body()
      .html()
      .replaceAll("(?i)<br\\s*/?>", "\n")
      .replaceAll("(?i)</p>", "\n\n")
      .replaceAll("(?i)</div>", "\n\n")
      .replaceAll("(?i)</li>", "\n")
    val text = Jsoup.parse(withBreaks).text()
    text.replaceAll("\\n{3,}", "\n\n").trim

  /** Resolves personalization fields from a contact row (ContactView shape:
    * camelCase accessors + custom_fields JSON).
    */
  def resolverFor(
      email: String,
      firstName: Option[String],
      lastName: Option[String],
      company: Option[String],
      jobTitle: Option[String],
      region: Option[String],
      timezone: Option[String],
      website: Option[String],
      phone: Option[String],
      customFields: Json
  ): String => Option[String] =
    case "email"                      => Some(email)
    case "first_name"                 => firstName
    case "last_name"                  => lastName
    case "company"                    => company
    case "job_title"                  => jobTitle
    case "region"                     => region
    case "timezone"                   => timezone
    case "website"                    => website
    case "phone"                      => phone
    case f if f.startsWith("custom.") =>
      val key = f.stripPrefix("custom.")
      customFields.asObject.flatMap(_.apply(key)).flatMap {
        case v if v.isNull    => None
        case v if v.isString  => v.asString
        case v if v.isNumber  => Some(v.asNumber.get.toString)
        case v if v.isBoolean =>
          Some(if v.asBoolean.get then "true" else "false")
        case _ => None
      }
    case _ => None
