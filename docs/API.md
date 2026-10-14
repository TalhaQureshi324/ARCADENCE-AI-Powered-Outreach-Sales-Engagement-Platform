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
| Rate limiting | ✅ Implemented (1.4): per-caller sliding window, `RATE_LIMIT_PER_MINUTE` (default 120/min), 429 + error envelope. In-memory per instance; distributed limiter at deployment |
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

- ✅ `GET /api/v1/me` — profile + workspace memberships
- ✅ `POST /api/v1/workspaces` — creates organization + workspace + owner membership
- ✅ `GET|PATCH /api/v1/workspaces/{workspaceId}` — member+ read; admin+ update (audited)
- ✅ `GET /api/v1/workspaces/{workspaceId}/members` — member+
- ✅ `PATCH|DELETE /api/v1/workspaces/{workspaceId}/members/{userId}` — admin+; owner membership immutable (audited)
- ✅ `GET|POST /api/v1/workspaces/{workspaceId}/invitations` — admin+; duplicate open invite → 409 (audited)
- ✅ `DELETE /api/v1/workspaces/{workspaceId}/invitations/{invitationId}` — admin+ revoke (audited)
- ✅ `POST /api/v1/invitations/{token}/accept` — authenticated; creates membership (audited)
- Roles: owner, admin, campaign_manager, member, viewer (ADR-0017 matrix; campaign_manager added in migration `20261012100000`)

- ⬜ `GET /api/v1/me` — profile + workspace memberships
- ⬜ `GET|POST /api/v1/organizations/{orgId}/workspaces` — O/A (org-level)
- ⬜ `GET|PATCH|DELETE /api/v1/workspaces/{workspaceId}` — O/A
- ⬜ `GET|POST /api/v1/workspaces/{workspaceId}/members` — O/A list/invite
- ⬜ `PATCH|DELETE /api/v1/workspaces/{workspaceId}/members/{userId}` — O (role change/remove)
- ⬜ `POST /api/v1/invitations/accept` — invited user

### 3.3 Email accounts — Phase 2

Implemented (2.1):

- ✅ `GET /api/v1/email-providers` — capability matrix (authenticated); no secrets
- ✅ `GET /api/v1/workspaces/{id}/email-accounts` (view) · `GET .../{accountId}` (view)
- ✅ `PATCH .../{accountId}` — displayName / accountGroupId / dailySendCap (M+; absent = unchanged, explicit null = clear)
- ✅ `POST .../{accountId}/pause` · `/resume` · `/disconnect` (M+; state-machine validated, audited, disconnect purges credentials)
- ✅ `DELETE .../{accountId}` (M+; cascades credentials, health, sync state)
- ✅ `GET .../{accountId}/health` (view) — recent account_health_events
- ✅ `GET|POST /api/v1/workspaces/{id}/account-groups` (view / M+) · `PATCH|DELETE .../account-groups/{groupId}` (M+)

Planned:

- ✅ (2.2) `POST /api/v1/email-connections/oauth/google/start` `{workspaceId}` → `{authorizeUrl}` (M+; 503 `not_configured` without console credentials; state = HMAC-signed workspace+user+PKCE verifier, 10-min TTL)
- ✅ (2.2) `GET /api/v1/email-connections/oauth/google/callback?code&state` — browser route (no bearer; state-validated, membership re-checked) → 303 to `/app/email-accounts?gmail=connected|reconnected|denied|invalid_state|expired|forbidden|not_configured|error`
- ✅ (2.2) `POST /api/v1/workspaces/{id}/email-accounts/{accountId}/test` `{to}` (M+) — authorized test send; records `test_succeeded`/`test_failed`; provider failures surface as 502 `provider_error`
- ✅ (2.2) disconnect now best-effort revokes the Google grant before purging credentials; capability matrix rows carry `configured`
- ✅ (2.3) `POST /api/v1/email-connections/oauth/microsoft/start` `{workspaceId}` → `{authorizeUrl}` (M+; tenant-aware authority, same signed-state shape; 503 `not_configured` without an app registration)
- ✅ (2.3) `GET /api/v1/email-connections/oauth/microsoft/callback?code&state` — browser route → 303 `/app/email-accounts?microsoft=connected|reconnected|denied|invalid_state|expired|forbidden|not_configured|error`
- ✅ (2.3) test-send / disconnect / capability `configured` are provider-agnostic — the shared `/test` endpoint moved to the email-accounts routes and routes through the adapter registry
- ✅ (2.4) `POST /api/v1/workspaces/{id}/email-accounts` `{emailAddress, displayName?, username, password, smtp{Host,Port,Security}, imap{Host,Port,Security}}` (M+) — credentials validated by real server sign-in before creation; 409 on duplicates; 502 `provider_error` (never echoes the password); `signature` now patchable on every account (explicit null clears); account payloads carry `signature` + inbox `syncStatus`/`syncError`

### 3.4 Leads & lists — Phase 3

Implemented (3.1):

- ✅ `POST /api/v1/workspaces/{id}/contacts` (E+) — email normalized lowercase (dedup per workspace → 409), display casing kept in `emailDisplay`; custom fields validated against defined fields
- ✅ `GET /contacts` (view) — filters `q/state/verification/companyId/listId/tagId`, sorts `created_desc|created_asc|email_asc|email_desc`, cursor pagination (opaque cursor; sort-aware keyset), `limit` ≤ 100, response `{items, nextCursor, total}`
- ✅ `GET|PATCH|DELETE /contacts/{contactId}` (view / E+) — PATCH absent=unchanged, explicit null=clear
- ✅ `POST /contacts/batch` (E+) — ≤200; invalid email anywhere rejects the whole batch (422); new→created, existing→skipped
- ✅ `GET /contacts/{id}/provenance` (view) — manual/api/import/verification trail
- ✅ `POST /contacts/{id}/tags`, `DELETE /contacts/{id}/tags/{tagId}` (E+)
- ✅ `GET|POST /companies`, `PATCH|DELETE /companies/{id}` (view / E+)
- ✅ `GET|POST /lead-lists`, `PATCH|DELETE /lead-lists/{id}`, `POST|DELETE /lead-lists/{id}/members[/{contactId}]`, `GET .../members` (view / E+) — membership idempotent

Planned:

- ✅ (3.2) `POST /imports/upload?fileName=` (E+, octet-stream ≤25MB) — parses headers + 5-row preview, creates job (status `created`/mapping); 422 on malformed/empty CSV
- ✅ (3.2) `POST /imports/{jobId}/start` `{mapping: {colIndex → field}, duplicateStrategy: skip|update|reject, defaults?, targetListId?}` (E+) — validates mapping (email required, custom fields must exist), enqueues the processing fiber
- ✅ (3.2) `GET /imports` (history) · `GET /imports/{jobId}` (progress: processed/inserted/updated/skipped/invalid/duplicate totals)
- ✅ (3.2) `GET /imports/{jobId}/errors.csv` — per-row error report (row_number, reason)
- ✅ (3.2) `POST /imports/{jobId}/retry` (E+) — failed/completed only; idempotent: provenance UNIQUE (import_job_id, row_number) skips already-imported rows; startup marks interrupted `running` jobs failed for retry
- ⬜ (3.3) verification + suppression APIs
- ⬜ (3.4) segments, bulk ops, merge, export

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
