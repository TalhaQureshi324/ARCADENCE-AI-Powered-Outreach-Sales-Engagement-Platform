# Arcadence — API

> Status: **Conventions binding; route catalog designed (Phase 0.2).** Implemented endpoints marked ✅; everything else is the designed catalog that later phases implement. OpenAPI generation from Tapir activates with the first authenticated endpoints.

**Last updated:** 2026-10-10

---

## 1. Surface

- **Scala API** (http4s + Tapir) exposes the business API under `/api/v1/...`
- OpenAPI documentation is **generated from Tapir descriptions** (single source of truth) and served by the API.
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

## 3. Route catalog (Phase 0.2 design)

Legend: ✅ implemented · ⬜ designed. Roles: O=owner, A=admin, M=member, V=viewer (see `docs/ARCHITECTURE.md` §3).

### 3.1 Infrastructure

- ✅ `GET /health` — liveness (unauthenticated) → `{"service","version","status":"up"}`
- ✅ `GET /ready` — readiness (unauthenticated) → `{"service","version","status","checks":{...}}`; dependency checks (database, JWKS reachability) activate in Phase 0.3
- ⬜ `POST /internal/webhooks/{provider}` — provider-signed ingress (Gmail/Graph); enqueues idempotent sync jobs (`docs/ARCHITECTURE.md` §8.1)

### 3.2 Identity & workspaces — Phase 1

- ⬜ `GET /api/v1/me` — profile + workspace memberships
- ⬜ `GET|POST /api/v1/organizations/{orgId}/workspaces` — O/A (org-level)
- ⬜ `GET|PATCH|DELETE /api/v1/workspaces/{workspaceId}` — O/A
- ⬜ `GET|POST /api/v1/workspaces/{workspaceId}/members` — O/A list/invite
- ⬜ `PATCH|DELETE /api/v1/workspaces/{workspaceId}/members/{userId}` — O (role change/remove)
- ⬜ `POST /api/v1/invitations/accept` — invited user

### 3.3 Email connections — Phase 2

- ⬜ `GET|POST /api/v1/workspaces/{id}/email-connections`
- ⬜ `GET /api/v1/email-connections/oauth/{provider}/start` → consent redirect; `GET .../callback` (state-validated)
- ⬜ `PATCH|DELETE /api/v1/workspaces/{id}/email-connections/{connId}` (pause/resume/disconnect; A/M+)
- ⬜ `POST .../{connId}/test-send` — verification email (M+)

### 3.4 Leads & lists — Phase 3

- ⬜ `GET|POST /api/v1/workspaces/{id}/contacts` · `GET|PATCH|DELETE .../contacts/{contactId}`
- ⬜ `POST .../contacts/import` (CSV via signed URL) · `GET .../contacts/export`
- ⬜ `GET|POST|PATCH|DELETE .../companies[/{companyId}]`
- ⬜ `GET|POST|PATCH|DELETE .../lists[/{listId}]` · `POST .../lists/{listId}/contacts` (add/remove batch)
- ⬜ `GET|POST|PATCH|DELETE .../custom-fields[/{fieldId}]`
- ⬜ `GET|POST|DELETE .../suppression[/{value}]` (A+ for writes)

### 3.5 Campaigns — Phase 4

- ⬜ `GET|POST /api/v1/workspaces/{id}/campaigns` · `GET|PATCH|DELETE .../campaigns/{campaignId}`
- ⬜ `POST .../campaigns/{campaignId}/start|pause|resume|archive`
- ⬜ `PUT .../campaigns/{campaignId}/sequence` (steps + variants as a unit)
- ⬜ `POST .../campaigns/{campaignId}/connections` (assign sending accounts)
- ⬜ `POST .../campaigns/{campaignId}/enrollments` (batch enroll; idempotent) · `GET .../enrollments?status=`
- ⬜ `POST .../enrollments/{enrollmentId}/pause|resume|delete`

### 3.6 Inbox — Phase 6

- ⬜ `GET /api/v1/workspaces/{id}/conversations?filter=` · `GET .../conversations/{conversationId}` (with messages)
- ⬜ `POST .../conversations/{conversationId}/reply` (Idempotency-Key required)
- ⬜ `POST .../conversations/{conversationId}/close|reopen` · mark-read

### 3.7 CRM — Phase 7

- ⬜ `GET|POST|PATCH|DELETE .../pipelines[/{pipelineId}]` (stages nested)
- ⬜ `GET|POST .../deals` · `GET|PATCH|DELETE .../deals/{dealId}` · `POST .../deals/{dealId}/move` (stage change)
- ⬜ `GET|POST .../activities` · `PATCH .../activities/{activityId}`

### 3.8 Deliverability & analytics — Phases 8–9

- ⬜ `GET .../analytics/campaigns/{campaignId}/summary|daily|sequence` (V+)
- ⬜ `GET .../analytics/overview` (workspace rollups)
- ⬜ `GET .../deliverability/connections/{connId}` (reputation signals, guidance)

### 3.9 AI — Phase 10

- ⬜ `POST /api/v1/ai/generate` (kind: campaign_copy | reply_draft | lead_summary; returns generation id + output; every call audited in `ai_generation`)
- ⬜ `GET .../ai/generations/{generationId}`

### 3.10 Billing — Phase 11

- ⬜ `GET /api/v1/organizations/{orgId}/subscription` · `POST .../checkout-session` (Stripe)
- ⬜ `POST /internal/webhooks/stripe` (signature-verified, idempotent)

### 3.11 Developer platform (post-parity)

- ⬜ Workspace-scoped API keys, outbound webhook registration `GET|POST .../webhooks` (tables designed in `docs/DATABASE.md` §6.10)

## 4. Webhooks (outbound, later phases)

Signed payloads (HMAC-SHA256 with per-endpoint secret + timestamp), at-least-once delivery with retries/backoff, customer dedupe by delivery id; full catalog published when introduced.

## 5. Testing & documentation gates per endpoint

1. Tapir description → OpenAPI regenerated and committed.
2. Happy-path + authorization-failure + validation-failure tests.
3. Cross-tenant isolation test.
4. This file updated.
