# Arcadence — API

> Status: **No endpoints exist yet.** The Scala API service is scaffolded in Phase 0; the first business endpoints arrive with Phase 1. This file records binding API conventions; endpoint catalogs are appended per phase.

**Last updated:** 2026-10-09

---

## 1. Surface

- **Scala API** (http4s + Tapir) exposes the business API under a versioned prefix: `/api/v1/...`
- OpenAPI documentation is **generated from Tapir descriptions** (single source of truth) and served by the API (Swagger UI path decided at scaffold time).
- The Next.js frontend calls the Scala API directly with the caller's Supabase JWT; Next.js server routes act only as SSR/auth glue, not as a duplicated business layer.

## 2. Conventions (binding)

| Concern | Convention |
| --- | --- |
| Versioning | URI-versioned (`/api/v1`); additive changes only within a version |
| Auth | `Authorization: Bearer <Supabase JWT>` — verified via JWKS (issuer, audience, expiry, algorithms) per ADR-0003 |
| Tenancy | Workspace addressed in the path (`/workspaces/{workspaceId}/...`); membership + RBAC resolved server-side on **every** request; RLS is a backstop, not the gate |
| Errors | Uniform JSON envelope (below); machine-readable `code`, safe human `message`, correlation `requestId`; no stack traces or internals leaked |
| Validation | Request decoding via Tapir + domain-level validation; 400 with field-level details where applicable |
| Idempotency | Unsafe POSTs with external side effects (sends, imports, webhooks) accept an `Idempotency-Key` header; replays return the original outcome |
| Pagination | Cursor-based: `?limit=&cursor=` → `{ "data": [...], "nextCursor": "..." }`; no offset pagination on large tables |
| Filtering/sorting | Explicit, whitelisted parameters only |
| Time & IDs | UTC ISO-8601 timestamps; UUID string identifiers |
| Rate limiting | Applied at the edge/API per workspace; limits documented per endpoint group when introduced |
| Deprecation | `Deprecation`/`Sunset` headers + changelog notice before removal |

**Error envelope:**

```json
{
  "error": {
    "code": "workspace_forbidden",
    "message": "You do not have access to this workspace.",
    "requestId": "b7ad6b71-..."
  }
}
```

**Standard status semantics:** 400 validation · 401 unauthenticated · 403 unauthorized (member, insufficient role) · 404 not found **or** out-of-tenant (never confirm other tenants' resource existence) · 409 conflict · 422 domain rule violation · 429 rate-limited · 5xx with `requestId` for support.

## 3. Endpoint catalog

Populated per phase as endpoints ship. Format per endpoint: method, path, auth/roles, request/response schemas, idempotency, error codes.

- ✅ **Phase 0 — infrastructure probes (implemented & tested, no auth by design):**
  - `GET /health` → `200 {"service": "arcadence-api", "version": "<build>", "status": "up"}`
  - `GET /ready` → `200 {"service": "arcadence-api", "version": "<build>", "status": "ready", "checks": {"process": "ok"}}` — dependency checks (database, …) are appended from Phase 0.3 onward; a not-ready dependency will flip `status` to `"not_ready"` and add failed check entries.
  - Unknown paths → `404`. Endpoints are described in Tapir (OpenAPI generation lands with the first authenticated endpoints); CORS currently allow-all-without-credentials for local development, replaced by environment-scoped origins in Phase 0.4.
- ⬜ Phase 1 — workspaces, members, invitations
- ⬜ Phase 2 — email connections (OAuth start/callback, SMTP/IMAP accounts)
- ⬜ Phases 3–12 — per roadmap

## 4. Webhooks (outbound, later phases)

Signed payloads (HMAC with per-endpoint secrets), at-least-once delivery with retries/backoff, documented event catalog when introduced.

## 5. Testing & documentation gates per endpoint

1. Tapir description → OpenAPI regenerated and committed.
2. Happy-path + authorization-failure + validation-failure tests.
3. Cross-tenant isolation test.
4. This file updated.
