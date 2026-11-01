package com.arcadence.api

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.*
import org.http4s.dsl.Http4sDsl

/** Developer documentation endpoints (Phase 12.1): serves the OpenAPI 3.0
  * document bundled with the API plus a minimal human-readable guide.
  */
final class DocsRoutes extends Http4sDsl[IO]:

  private lazy val openapiJson: Json =
    parse(
      Option(getClass.getResourceAsStream("/openapi.json"))
        .map(src => new String(src.readAllBytes(), "UTF-8"))
        .getOrElse("{}")
    ).getOrElse(Json.obj())

  private val guide: String =
    """<!doctype html><html><head><title>Arcadence API</title>
      |<meta charset="utf-8"><style>body{font-family:system-ui;max-width:820px;margin:2rem auto;padding:0 1rem;color:#111}
      |pre{background:#f4f4f6;padding:12px;border-radius:8px;overflow:auto}code{background:#f4f4f6;padding:2px 5px;border-radius:4px}
      |h2{margin-top:2rem}</style></head><body>
      |<h1>Arcadence Public API <small>v1</small></h1>
      |<p>Machine-readable spec: <a href="/openapi.json">/openapi.json</a> (OpenAPI 3.0).</p>
      |<h2>Authentication</h2>
      |<p>Create a workspace API key (Settings &rarr; API keys or <code>POST /api/v1/workspaces/{id}/api-keys</code>).
      |The secret is shown once. Use it as a bearer token:</p>
      |<pre>curl -H "Authorization: Bearer ak_Xj3kQm82..." \\

      |  https://your-host/api/v1/workspaces/$WS/contacts</pre>
      |<h2>Webhooks</h2>
      |<p>Register an HTTPS endpoint and pick events. Deliveries POST a JSON envelope
      |(<code>event, workspaceId, data, sentAt</code>) signed with
      |<code>X-Arcadence-Signature: t=&lt;ts&gt;,v1=&lt;hmac_sha256(ts.payload)&gt;</code>.
      |Failed deliveries retry with exponential backoff and dead-letter after 5 attempts;
      |dead deliveries can be replayed.</p>
      |<h2>Rate limits</h2>
      |<p>Per-caller sliding-window limits apply to every endpoint; AI endpoints are also
      |governed by workspace token/spend budgets.</p>
      |</body></html>""".stripMargin

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "openapi.json" =>
      Ok(openapiJson.noSpaces).map(
        _.withContentType(
          org.http4s.headers.`Content-Type`(MediaType.application.json)
        )
      )
    case GET -> Root / "developers" =>
      Ok(guide).map(
        _.withContentType(
          org.http4s.headers.`Content-Type`(MediaType.text.html)
        )
      )
  }
