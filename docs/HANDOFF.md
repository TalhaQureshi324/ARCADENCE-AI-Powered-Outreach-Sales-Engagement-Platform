# Arcadence — Handoff Log

> Running record of what was implemented, tested, committed, and pushed. One entry per subphase, newest first. Matches the implementation reports given in chat.



## Phase 10 — Lead discovery and enrichment (`20261027090000`-`20261027120000`)

- **10.1 Provider framework** - core `LeadProvider.Provider` (search/enrich/supportedFilters), provider errors as values, SandboxProvider with three deterministic `sandbox_only` fixtures. No live provider integration is faked: only agreement-backed adapters may be wired, and the API rejects unknown providers with a licensing explanation.
- **10.2 Waterfall enrichment** - providers tried in configured order, first match wins; strictly preview-only (contact values are never overwritten without approval); provenance history per contact in `provenance_records`.
- **10.3 Credit accounting** - append-only `credit_transactions` (reserve/release/charge/refund/grant) with deterministic idempotency keys (unique partial index); balance-checked reservation under a per-workspace `pg_advisory_xact_lock`; reservation released, then exact charge applied; refunds on failure or zero results. Starter grant (50 credits) via trigger until Phase 11 billing.
- **10.4 Lead Finder UI** - /app/leadfinder: provider-capability-aware filters (unsupported disabled with explanation), credit balance, results with verification/confidence/provider/permitted-use labels, add-to-list with per-category dedupe/suppression/sandbox-block counts, saved searches (localStorage per workspace), recent searches.
- Migrations applied local + staging: 20261027090000_leadfinder, 20261027100000_source_provenance_leadfinder, 20261027110000_discovered_contacts_ws_unique, 20261027120000_starter_credits.
- Tests: LeadFinderSpec 11/11 (search pricing + provenance, timeout refund, unknown-provider rejection, insufficient credits 402, concurrent-reservation overdraft impossibility, sandbox licensing block, dedupe/suppression/idempotent re-run, preview-only enrichment, tenant isolation); full Scala 267/267 (75 core + 2 worker + 190 api); web lint 0 errors, Vitest 25/25, production build green; Playwright 16/16 including new leadfinder e2e (search -> provenance labels -> sandbox blocked -> credits settled).


## Phase 11.1 — AI infrastructure and model gateway (`20261029090000`)

- **Gateway (core)** — provider-agnostic `AiGateway`: chat completions with model selection and output-token caps; `structured[A]` validates machine-readable output against a Scala decoder before anything may act on it; `retried` wraps adapters with bounded exponential backoff + jitter, retrying only timeout/rate-limit/unavailable (never auth or bad-request); `Redact` strips emails/API keys/bearer tokens and truncates everything stored in logs.
- **Adapter** — `OpenAiCompatible` HTTP adapter (works with OpenAI/Azure/OpenRouter/self-hosted gateways). Wired in Main from `AI_API_KEY` + `AI_BASE_URL` + `AI_MODEL` + `AI_TIMEOUT_MS` + `AI_MAX_RETRIES`. **No key configured = no gateway**: `/ai/complete` returns 503 `ai_not_configured` rather than pretending. Provider agreements must exclude training on customer data (MANUAL_SETUP #12); the gateway sends only explicit prompt content.
- **Schemas** — ai_agents, ai_runs (sha256 input digest + redacted previews), ai_run_steps, ai_messages (drafts for 11.2/11.3), ai_templates (versioned), ai_usage (tokens + micro-USD), ai_approvals, ai_tool_invocations; workspace-level monthly token/spend budgets enforced before every call (402 insufficient_budget).
- **Endpoints** — /ai/config (honest gateway+budget status), agents CRUD, templates (auto-versioning), usage rollup, /ai/complete (budget-checked, run+usage+step logging), runs list/detail/cancel, approvals create/list/decide.
- Tests: AiGatewaySpec 8/8; AiRoutesSpec 8/8; full Scala 283/283 (83 core + 2 worker + 198 api). Blocked on owner action for live model calls: AI provider account + API key policy (MANUAL_SETUP #12) — **simulated** via stub until then.


## Phase 11.2 — AI email writer and lead researcher (`20261030100000`)

- `AiWriter` core: deterministic prompt assembly (facts passed with stable keys + source URLs + retrieval timestamps; model instructed to cite keys and never invent facts), strict JSON output schema with hand-written decoders, unsupported-claim detection, deception phrase scan, unknown-variable detection, and final-variable rendering that fails loudly on unrenderable/missing variables (ADR-0026 quoteReplacement).
- `ai_research_items` — approved research facts (claim, source URL, source kind, retrieved_at, verified); grounding comes only from these, never from model invention.
- Endpoints: `/ai/research` CRUD, `/ai/write` (1-3 drafts with per-draft evaluation flags stored in `ai_messages.evidence`), `/ai/messages` list + edit (final_* fields, status edited) + approve/reject + `/render` deterministic preview.
- Drafts are proposals only: sending and suppression remain exclusively in the deterministic campaign/inbox paths (Phase 5/6); approved drafts would be consumed by the composer, never by the model.
- Tests: AiWriterSpec 7/7; AiWriterRoutesSpec 6/6; full Scala 296/296.


## Phase 11.3 — AI Reply Agent (`feature/phase-113-reply-agent`, no migration)

- `AiReply` core: intent enum (interested/pricing/product_question/objection/meeting_request/out_of_office/not_interested/unsubscribe/ambiguous), whitelisted next actions, strict JSON proposal schema with hand-written decoders, deterministic validation (invalid intent/action = hard rejection; unknown evidence keys flagged; confidence enum).
- `POST /ai/reply/draft` — loads thread + contact + campaign + approved knowledge (workspace-scoped), feeds the deterministic ReplyClassifier verdict as context, proposes intent + reply + next actions with evidence; stores `ai_messages` draft + pending `send_reply` approval + `ai_runs` awaiting_approval audit.
- Deterministic precedence: unsubscribe/bounce threads never call the model (Phase 6 sync already applied suppression/hygiene); human approval gates every external response; sending remains the deterministic Phase 6 inbox path.
- Tests: AiReplyRoutesSpec 5/5; full suite green by module (90 core + 2 worker + 209 api incl. this spec); WorkerQueueIntegrationSpec timing flakes under full-suite parallel load (green 6/6 isolated) — pre-existing sleep-based assertions, serialized run tracked as follow-up.


## Phase 11.4 — AI Sales Agent orchestration (`feature/phase-114-sales-agent`, no migration)

- `AiSalesAgent` core: fixed 7-step pipeline definition, structured slot schemas (BriefUnderstanding, AudienceSuggestion) with hand-written decoders, deterministic readiness verdict.
- `POST /ai/agent/run`: model steps (brief, audience, campaign draft) via validated gateway calls; tool steps execute through existing services — sandbox provider search + enrichment recorded as `ai_tool_invocations` with `search_requests`/`discovered_contacts`/`provider_usage` rows; drafts stored as flagged `ai_messages`; run ends at pending `launch_campaign` approval (`ai_runs` awaiting_approval). No model planning freedom, no autonomous sends.
- `POST /ai/agent/recommendations` + `/ai/agent/meetings`: real campaign stats / positive repliers into validated structured outputs with full run audit.
- Tests: AiAgentRoutesSpec 5/5. Known: WorkerQueueIntegrationSpec + CrmIntegrationSpec timing-flake under full-suite parallel load (green in isolation; serialized run tracked as follow-up).

## Phase 11.5 — AI Studio interface and evaluations (feature/phase-115-ai-studio, no migration)

- **AI Studio UI** — /app/aistudio: agent configuration (create writer/researcher/reply/sales agents; approval-required + enabled defaults visible), monthly usage vs budget card, pending approvals with approve/reject, run audit table with per-run token/cost and budget-exhausted warning.
- **Evaluation fixtures** — AiEvalSpec 6/6: prompt-injection content in knowledge stays data (validators unchanged), hallucinated personalization flagged as unsupported claims, ambiguous replies first-class with low-confidence handling, invalid intents/actions hard-rejected regardless of phrasing, leftover placeholders refuse to render, malformed model output never executes.
- Tests: AiEvalSpec 6/6; Playwright 17/17 including aistudio e2e; web tsc/lint/prettier/build green.



## Phase 11 addendum - GLM provider configured (env-only, no code default changed)

- Provider: Zhipu GLM via the OpenAI-compatible adapter (AI_BASE_URL=https://open.bigmodel.cn/api/paas/v4). Live-verified with curl: auth OK, glm-4.5-flash responds with valid JSON under response_format=json_object + thinking disabled (both sent by the adapter when AI_DISABLE_THINKING=true). glm-5.3-flash exists on the account but returns error 1113 (insufficient balance) until topped up - switch is one env line (AI_MODEL=glm-5.3-flash).
- apps/api/.env.local (git-ignored) rebuilt with full local config: recovered Supabase/JWT values from apps/api/.env.example defaults, NEW EMAIL_ENCRYPTION_KEY (the previous local key was lost when the file was overwritten - locally stored email-account credentials must be reconnected; no production impact).
- OpenAiCompatible gained an optional disableThinking flag (env AI_DISABLE_THINKING=true) - required for GLM 4.5+ reasoning models so structured outputs return content within token budgets.
---

---

---

---

---

---

---

## 2026-10-15 — Phase 3 / Subphase 3.3: Verification & suppression

**Status:** ✅ Completed and pushed

### Implemented

- **Provider abstraction (core, `email.verification`)** — `EmailVerificationProvider` trait + `SyntaxMxProvider` default (syntax check, MX lookup via JNDI DNS with bounded timeouts). Per the plan: **syntax+MX alone never classifies an address as verified** — MX found → `risky`, no MX → `invalid`, DNS errors → `unknown`. Licensed external providers plug into the same trait when API keys exist (MANUAL_SETUP 12a).
- **Verification API** — `POST /contacts/{id}/verify` + `/contacts/verify-batch` (≤25): persists verification_status/checked_at/provider on the contact, writes provenance (source=verification), **auto-suppresses invalid results** (reason=invalid, source=automatic; contact → bounced).
- **Suppression** — `suppression` table (workspace-scoped, unique per email; reuses Phase 0 reason/source enums) + API: list/search, create by email-or-contact (dedup upserts reason), delete = **reactivation** restricted to admin+ (manage_members), audited, and **unsubscribe/complaint suppressions are permanent** (409 on delete attempt). Removal reactivates the contact (state → active).
- **Import enforcement** — suppressed emails are skipped during CSV import (recorded in the error report as `suppressed: …`), enforced at Phase 3.2's batched pipeline.
- **Web** — Suppression tab on /app/leads (add-by-email with reason, list, permanent badges, two-step reactivation with admin gate) and a **Verify** action on every contact row (toast shows provider verdict; badges refresh).

### Tested (evidence)

- **VerificationSuppressionSpec 8/8** (DB-gated): risky-never-verified, invalid → auto-suppress + bounced state + suppression row, manual suppression create/dedupe/list, viewer 403, unsubscribe suppression PERMANENT (owner delete → 409), admin removal → reactivation + audit row, import skips suppressed emails.
- Full Scala suite: **178/178** (worker 2 · core 101 incl. new core provider file compile · api 75). Web: tsc/lint/vitest 25/25/build green.

### Notes

- MX lookups hit real DNS only in the default provider path at runtime; tests use a stub provider (no network). Licensed provider API keys are a future MANUAL_SETUP item.

## 2026-10-15 — Phase 3 / Subphase 3.2: CSV import pipeline

**Status:** ✅ Completed and pushed

### Implemented

- **Database (`20261015100000_imports.sql`)**: `import_job` (status created/queued/running/completed/failed/cancelled; duplicate strategy; column mapping + defaults jsonb; target list; per-outcome counters) + `import_error` (row-level report). `source_provenance` gains `row_number` with UNIQUE (import_job_id, row_number) — the idempotency marker. Applied local + staging.
- **Pipeline (`ImportService`)**: batched processing (500 rows/batch, bounded memory) — bulk existence checks, bulk contact INSERT … ON CONFLICT DO NOTHING RETURNING, bulk provenance markers, company find-or-create BEFORE insert (UUID-typed), per-batch progress bump, finish/fail with error message. CSV: RFC 4180 state machine (quoted fields/delimiters/newlines, escaped quotes), delimiter sniffing (`,` `;` `	` `|`), UTF-8 BOM + ISO-8859-1 fallback. Duplicate strategies: skip / update-non-empty / reject(→error rows). Row classification: missing/invalid email → import_error; in-file duplicates per strategy. target list membership on import.
- **API**: upload (octet-stream ≤25MB → headers + 5-row preview + job in `created`), start (mapping validation: email required, custom-field targets must exist), history, progress, errors.csv download, retry (failed/completed only; guarded transition; resume skips already-imported rows). Startup recovery marks interrupted `running` jobs failed → retryable.
- **Web**: Imports tab on /app/leads — file upload, mapping UI (file column → contact field selects), duplicate-strategy + target-list selects, live progress polling (1.5s while queued/running), history table with totals + retry + error-report download.

### Bugs found & fixed during 3.2

1. **Partial unique index broke ON CONFLICT** — `UNIQUE (import_job_id, row_number) WHERE … NOT NULL` cannot be targeted by `ON CONFLICT (…)`; provenance markers silently never wrote (batch failed → job failed). Replaced with a plain unique index (NULLs never conflict).
2. **Email regex missing caret** — hand-built `[\s@]` instead of `[^\s@]` in the final class rejected ALL valid emails (caught by 10k test inserted=0).
3. foldLeftM accumulator semantics — per-batch deltas were returned as the new accumulator instead of sums (10k totals showed only the last batch).

### Tested (evidence)

- **ImportIntegrationSpec 7/7** incl. the plan's required **10,000-record import** (~9s, batched), duplicate skip/update strategies, malformed/empty CSV 422, error-report content, **idempotent retry** (forced reprocess → still exactly 1 contact), tenant isolation (outsider 404).
- Web: tsc clean · lint 0 errors · vitest 25/25 · build green.
- Full Scala suite re-run after merge (see 3.3 entry).

## 2026-10-15 — Phase 3 / Subphase 3.1: Contacts and companies

**Status:** ✅ Completed and pushed

### Implemented

- **Database (`20261015090000_contacts.sql`)**: `company`, `contact` (UNIQUE normalized-lowercase email per workspace + `email_display` original casing; names/title/website/phone/region/timezone/notes; `custom_fields` jsonb; verification columns ready for 3.3), `lead_list`, `list_membership` (idempotent pairs, actor recorded), `tag` + `contact_tag`, `custom_field` (typed definitions), `source_provenance` (append-only: manual/api/import/verification; `import_job_id` reserved for 3.2). Keyset index `(workspace_id, created_at desc, id)` + GIN on custom_fields (for 3.4). RLS member-select policies; writes via the audited API. Applied to local + staging.
- **API (`ContactRoutes` + `LeadsRepos`)**: contact CRUD with normalization + 409 dedup; list with `q/state/verification/companyId/listId/tagId` filters, 4 sorts, **sort-aware keyset cursor pagination** (email sorts cursor on `(email, id)`; opaque base64url cursor, malformed → 422), `total` included; batch create (≤200, all-or-nothing email validation, per-item created/skipped); provenance; tags on contacts; companies CRUD; lead lists CRUD + idempotent membership add/remove + paged members; custom-fields CRUD. Reads = view permission; writes = edit_content. Unknown custom-field keys rejected at write time (must be defined first). Envelope errors map 1:1.
- **Web**: `/app/leads` page with Contacts / Companies / Lists / Tags & Fields tabs — searchable + sortable + paginated contacts table with normalized-vs-display email, create dialog (dedup 409 surfaced), companies with contact counts, lists with member management dialogs, tags + typed custom field management. Sidebar "Leads" nav activated.
- **Fixed (long-standing)**: `apiFetch` only parsed `json.error`; the Scala API returns the error envelope top-level (`{code,message}`) — business errors now surface their real messages app-wide (e.g. dedup 409s).

### Tested (evidence)

- Scala `Test/test` with `TEST_DATABASE_URL` → **163/163** (worker 2 · core 75 · api 86 incl. **LeadsIntegrationSpec 11**: custom-field validation, normalization + display casing + 409 dedup, viewer read-only + 403 writes, outsider 404, batch all-or-nothing validation + skip semantics, **cursor pagination no-overlap across email sort**, companies/lists/membership/tags/provenance round-trip, PATCH null-clear + delete, cross-tenant end state).
- Web: vitest 25/25 · tsc clean · lint clean · build green (`/app/leads` registered).
- **Playwright 10/10** — new leads spec: create contact (display + normalized emails shown), dedup 409 surfaced in dialog, search, tag create, list create + member add with toast.

### Notes

- Cursor keyset gotcha fixed during testing: email-sorted pages must cursor on `(email, id)` — created_at cursors silently overlap pages.
- pg_trgm full-text search intentionally deferred to 3.4 (segment/search scale work).

## 2026-10-14 — Phase 2 / Subphase 2.4: SMTP/IMAP + completed email-account dashboard — PHASE 2 MILESTONE

**Status:** ✅ Completed and pushed — **Phase 2 milestone (tag `phase-2`)**

### Implemented

- **SmtpImapProvider adapter (arcadence-core, `com.arcadence.core.email.smtpimap`)** — Jakarta Mail on angus behind the same 2.1 contract: shape validation (ports/hosts/TLS modes) before any network attempt, **mandatory TLS** (`ssl` implicit or `starttls.required` — no plaintext), real SMTP+IMAP sign-in validation, RFC 5322 submission (text/HTML alternative, In-Reply-To/References replies), IMAP INBOX retrieval newest-25 with the last seen UID as the durable cursor, typed error mapping (auth failure → InvalidCredentials; unknown host/refused → Unreachable; TLS handshake → security-mode guidance). OAuth-only operations return NotSupported values. System `mail.*` properties act as session defaults so deployments can pin trust anchors without code changes.
- **Connect-by-credentials endpoint** — `POST /api/v1/workspaces/{id}/email-accounts`: permission-checked, shape-validated, credentials validated by REAL server sign-in before creation (failures → 502 `provider_error`, password never echoed anywhere), duplicates → 409 `conflict`, account + encrypted `password`-kind credentials + sync cursors + health + audit created on success.
- **Dashboard completion** — signature is now settable/clearable on every account (PATCH explicit-null semantics, UI editor dialog); account payloads carry `signature` + inbox `syncStatus`/`syncError`; the accounts table shows a Sync column; the test-send dialog remembers recent test recipients per workspace (localStorage quick-picks); the SMTP/IMAP provider card offers a real connect form (host/port/security selects, app-password guidance) that surfaces server rejections honestly.
- **Config** — no new required settings; the smtp_imap adapter is always registered (`configured=true`).

### Tested (evidence)

- Scala `Test/test` with `TEST_DATABASE_URL` → **152/152** (worker 2 · core 75 incl. **SmtpImapProviderSpec 8 over REAL GreenMail TLS servers**: dual login, wrong-password → InvalidCredentials without echo, unknown host → Unreachable, real message delivery, reply headers, UID cursor · api 75 incl. **SmtpImapFlowIntegrationSpec 7**: real-server validation before creation, duplicate 409, 502 provider error without password echo, signature set/clear, test send delivered through real SMTP, disconnect purge).
- `scalafmtAll` success. Web: vitest 25/25 · lint clean · format clean · build green.
- **Playwright 9/9** — incl. the SMTP/IMAP connect form rejecting a dead server (127.0.0.1:1) with a visible error, then cancelling; honest Google/Microsoft unconfigured cards; forged-state OAuth callback guard.

### Notes / known issues

- TLS behavior against production providers (Gmail/365 certificate chains) is verified by the first real connect per provider (docs/MANUAL_SETUP.md 8a); GreenMail covers protocol logic with its bundled self-signed cert, trusted only inside the test JVM and cleared afterwards.
- GreenMail runs plain servers in some legacy examples — our suites use its TLS setups to match the adapter's mandatory-TLS posture.
- Docker Desktop stopped mid-session again (Windows); restarted and the local stack recovered with no data loss.

### Phase 2 milestone

- `develop` merged to `main`, annotated tag **`phase-2`** pushed; CI green on both branches.

## 2026-10-14 — Phase 2 / Subphase 2.3: Microsoft 365 integration

**Status:** ✅ Completed and pushed

### Implemented

- **GraphProvider adapter (arcadence-core, `com.arcadence.core.email.microsoft`)** — Microsoft identity platform v2 + Graph behind the 2.1 contract: tenant-aware authorization-code + PKCE consent URL, token exchange with verifier, refresh grant, `/me` identity (mail/userPrincipalName/displayName/id), **draft-then-send submission** (POST /me/messages → POST /me/messages/{id}/send — captures the provider message id that `/me/sendMail` does not return), createReply + body patch + send for threaded replies, INBOX listing with `$orderby` + `@odata.nextLink` `$skiptoken` as the durable cursor, health probe, and `revokeSignInSessions` revocation. Typed error mapping: 400 consent family (invalid_grant/consent_required/interaction_required) + 401 → InvalidCredentials; 403 → PermissionDenied (tenant app-blocking surfaces here); 429 → RateLimited with Retry-After; 503/504 → Unreachable. Phase 6 headroom: same folder surface converts to `/delta` links and Graph change notifications without touching the account model (ADR-0022).
- **Connect flow (api)** — `POST /api/v1/email-connections/oauth/microsoft/start` + `GET .../microsoft/callback`: identical HMAC-signed-state security shape to Gmail (workspace+user+PKCE verifier+nonce, 10-min TTL, callback-side permission re-check), account upsert (connected/reconnected), AES-GCM credentials (MicrosoftCredentials), sync cursors seeded, health + audit written, 303 back with `microsoft=…` outcome.
- **Provider-agnostic hardening** — the authorized test-send endpoint MOVED from GmailRoutes to the email-accounts routes and routes through the adapter registry (no double-mounting now that two OAuth providers exist); registry credential load/store generalized over GmailCredentials | MicrosoftCredentials with provider-aware decryption.
- **UI** — connect button, unconfigured notice (MSFT_OAUTH_* hint), callback outcome banners and the Reconnect action now work for BOTH Gmail and Microsoft; capability rows carry `configured`.
- **Config** — optional `MSFT_OAUTH_CLIENT_ID/SECRET/REDIRECT_URI/TENANT` (default tenant `common`).

### Tested (evidence)

- Scala `Test/test` with `TEST_DATABASE_URL` → **137/137** (worker 2 · core 67 incl. **GraphProviderSpec 11** — consent failure, missing permissions 403, throttling 429 with Retry-After, draft/send/reply flows, skiptoken cursor, revocation · api 68 incl. **MicrosoftFlowIntegrationSpec 10** — capability `configured`, start URL + state verification, tamper/expiry/denial callbacks, encrypted-credential round-trip under the Microsoft decoder, reconnection dedupe, test-send permission gating, disconnect purge).
- `scalafmtAll` success. Web: vitest 25/25 · lint clean · format clean · build green.
- **Playwright 9/9** — honest Microsoft unconfigured card asserted; forged-state Gmail callback path re-verified through the live API.
- Refactor regression: GmailFlowIntegrationSpec 12/12 still green after the test-send move.

### Blocked (manual)

- Real-Graph end-to-end waits on **item 7** (`docs/MANUAL_SETUP.md`): Entra app registration → `MSFT_OAUTH_CLIENT_ID/SECRET` in `apps/api/.env.local`, redirect `http://localhost:8080/api/v1/email-connections/oauth/microsoft/callback`. Tenant admin consent may be required depending on the tenant's app policy.

## 2026-10-14 — Phase 2 / Subphase 2.2: Gmail integration

**Status:** ✅ Completed and pushed

### Implemented

- **GmailProvider adapter (arcadence-core, `com.arcadence.core.email.gmail`)** — implements the Phase 2.1 `EmailProvider` contract over real Google endpoints: authorization-code + PKCE (S256) consent URL builder, token exchange with verifier, refresh-token grant, userinfo identity, Gmail profile validation, RFC 5322 MIME submission (text + multipart/alternative, RFC 2047 subjects, `raw` base64url), INBOX listing with page-token cursors, health check, grant revocation. Typed `ProviderError` mapping (400 invalid_grant/401 → InvalidCredentials; 403 rateLimitExceeded → RateLimited; insufficientPermissions → PermissionDenied; 5xx → Unreachable). Google endpoints injectable — tests run the full flows against a stub server. Access-token refreshes live in memory only (ADR-0021); the refresh token is the durable encrypted secret.
- **OAuth flow (api)** — `POST /api/v1/email-connections/oauth/google/start` (permission-checked) returns a consent URL carrying an HMAC-SHA256-signed state (workspace + user + PKCE verifier + nonce, 10-minute expiry; key derived domain-separated from EMAIL_ENCRYPTION_KEY). `GET .../callback` (browser route, no bearer): signature + expiry + Google error params each land on distinct outcomes; membership re-checked at callback; account upserted (connected / reconnected), credentials encrypted via CredentialEncryptor and stored (kind oauth2, versioned), sync cursors seeded (inbox+sending), health event + audit written, 303 back to `/app/email-accounts?gmail=…`.
- **Capabilities/UI** — capability matrix rows now carry `configured`; the Gmail card shows a real Connect button when configured, an honest "Google OAuth not configured on this server" notice otherwise. Accounts table adds Test send (dialog → authorized send via the adapter, `test_succeeded`/`test_failed` health) and Reconnect for reconnect_required/error Gmail accounts. Callback outcomes render as success/error banners and the URL is cleaned.
- **Disconnect** now best-effort revokes the Google grant before purging local credentials.
- **Config** — optional `GOOGLE_OAUTH_CLIENT_ID/SECRET/REDIRECT_URI`; absence → `configured=false` everywhere, `start` → 503 `not_configured` (never a dead button).

### Notable fixes during 2.2

1. GmailRoutes class-init order (endpoint val referenced before initialization → NPE in tests) — endpoint definitions moved above route assembly.
2. CI-truth lesson from 2.1 applied: web files prettier-formatted before push; migration-free subphase so the Database job needed no change.

### Tested (evidence)

- Scala `Test/test` with `TEST_DATABASE_URL` → **117/117** (worker 2 · core 57 incl. GmailProviderSpec 11 — stub-server flows for exchange/PKCE form/transparent refresh/MIME submission/error mapping/revocation — + GmailMimeSpec 5 + OAuthStateSignerSpec 4 · api 58 incl. **GmailFlowIntegrationSpec 12**: capability `configured` flags, start URL state verification, tampered/expired/denied callbacks, full callback → account + encrypted-credential (decryptable) + health + audit + sync cursors, reconnection dedupe, test send, viewer/outsider gating, disconnect purge).
- `scalafmtAll` success. Web: vitest 25/25 · lint 0 errors · format clean · build green.
- **Playwright 9/9** — including a forged-state callback driven through the REAL API from the browser (lands on `/app/email-accounts?gmail=invalid_state`, banner shown, URL cleaned) and the honest unconfigured-Gmail card.
- Live: API restarted with the 2.2 build; `/api/v1/email-providers` 401 envelope + security headers intact.

### Blocked (manual)

- Real-Google end-to-end (consent → real mailbox send) is **blocked on product-owner action 6a** (`docs/MANUAL_SETUP.md`): create the Google Cloud OAuth client (redirect URI `http://localhost:8080/api/v1/email-connections/oauth/google/callback`), then set `GOOGLE_OAUTH_CLIENT_ID/SECRET` in `apps/api/.env.local` and restart the API. Everything up to Google's consent screen is implemented and verified; restricted-scope verification remains a production-launch prerequisite (not required for test users).

## 2026-10-13 — Phase 2 / Subphase 2.1: Email account architecture

**Status:** ✅ Completed and pushed

### Implemented

- **Database (migration `20261013090000_email_accounts.sql`)**: `email_accounts`, `account_credentials`, `sender_domains`, `account_groups`, `account_health_events`, `provider_sync_state` per the Phase 2 plan (naming supersedes the provisional `email_connection`/`email_credential` — ADR-0019). `connection_status` enum takes the Phase 2 states (connected, reconnect_required, rate_limited, suspended, error, disconnected); new `account_health_event_type` enum. RLS mirrors tenancy tables; `account_credentials` is RLS-locked with **no policies** + revoked grants (PostgREST can never touch it). Functional unique indexes on `(workspace_id, lower(email_address))`, group names and domains.
- **Core (arcadence-core)**: `EmailProvider` trait — the adapter contract for connection validation, submission, replies, cursor-based inbox sync, health checks, token refresh, revocation, with typed `ProviderError` values; `ProviderCapabilities` static capability matrix (single source for the API + dashboard cards); `EmailAccountStatus` state machine with explicit legal-transition matrix; `CredentialEncryptor` — AES-256-GCM (12-byte nonce ‖ ciphertext ‖ tag), key versioning, tamper→`DecryptFailed.Rejected` (ADR-0020). cats-effect added to core deps.
- **Config**: `EMAIL_ENCRYPTION_KEY` (base64, 32 bytes) required at API startup — fail-fast with aggregated errors; local key generated into git-ignored `.env.local`; `.env.example`, compose and SETUP/MANUAL_SETUP docs updated.
- **API**: `GET /api/v1/email-providers` (capability matrix); workspace-scoped email-accounts list/get/patch (absent=unchanged, explicit null=clear via null-aware circe decoder)/pause/resume/disconnect/delete + `/health` trail; account-groups CRUD with duplicate-name 409. New `manage_email_accounts` permission (campaign_manager+; viewers/members read-only). All transitions guarded-UPDATE race-safe, health-event + audit logged; disconnect purges credentials.
- **Web**: `/app/email-accounts` — provider connection cards (capability lists, OAuth vs TLS badges, honest "setup arrives in subphase 2.2/2.3/2.4" availability), account groups manager (create/rename inline/two-step delete), accounts table (status dot+badge, provider badge, group assign select, daily cap, last sync, pause/resume/disconnect/remove actions, expandable health-events panel), permission-gated UI. Sidebar nav item + api client additions.

### Bugs found and fixed during 2.1

1. **circe explicit-null decoding** (caught by integration test): derived `Option[Option[T]]` decoders map JSON `null` to `None` — "null clears the value" silently never reached the repo. Fixed with a null-aware hand-written decoder on `UpdateAccountRequest`.
2. Doobie `fragments.intercalate` does not exist in 1.0.0-RC12 — SET clauses built via fragment reduction.
3. Local `supabase db reset` required after a partial migration run; expression-unique constraints converted to unique indexes (Postgres does not allow `unique (a, lower(b))` as a table constraint).

### Tested (evidence)

- Scala `Test/test` with `TEST_DATABASE_URL` → **85/85** (worker 2 · core 37 incl. CredentialEncryptorSpec 7 + EmailAccountStatusSpec 7 + ProviderCapabilitiesSpec 5 + ConfigSpec key-validation 2 · api 46 incl. EmailAccountIntegrationSpec 14: capability matrix, group CRUD + duplicate 409, viewer/member read-manage split, owner+viewer+outsider matrix, credential-field leak check, lifecycle pause/resume/disconnect + 409 on illegal transition, credential purge on disconnect, health trail, cascade delete, cross-tenant end-state).
- `scalafmtAll` + `scalafmtCheckAll` → success. Web: Vitest **25/25**, lint clean (new files), `next build` green with `/app/email-accounts` registered.
- **Playwright 9/9** (auth 6 · shell 1 · multi-org 1 · new email-accounts spec: signup → workspace → provider cards incl. honest availability badges → groups create/rename/delete → empty state). One strict-mode locator fix during the run (getByLabel substring matched two inputs).
- Live: API restarted with new config; `GET /api/v1/email-providers` unauthenticated → 401 envelope with all five security headers + request id.

### Notes

- Local Supabase stack was `db reset` during development — local e2e users/workspace rows were recreated fresh by the suites; no data of record is kept locally.
- Docker Desktop stopped mid-session (Windows shell crash); restarted, stack recovered.

## 2026-10-12 — Phase 1 / Subphase 1.4: Workspace administration & hardening

**Status:** ✅ Completed and pushed — **Phase 1 milestone**

### Implemented

- **Settings UI (`/app/settings`)**: permission-aware tabs on live API data — Members (avatar/email/role table, inline role changes, removal), Invitations (invite form with role, open-invite table with links + revoke, duplicate → 409 surfaced), Profile (name/timezone/daily-cap form). Non-managers see read-only views with explanatory gates.
- **Invitation accept page (`/app/invitations/[token]`)**: shows workspace/role from `GET /api/v1/invitations/{token}`, accepts via POST; handles invalid/expired links.
- **Hardening (Scala)**: per-caller sliding-window rate limiting (`RATE_LIMIT_PER_MINUTE`, default 120/min, keyed by Authorization header; 429 with error envelope) and security headers (nosniff, DENY framing, same-origin referrer, no-store, Permissions-Policy) on all `/api/v1` responses. `GET /api/v1/invitations/{token}` details endpoint added. Next.js serves the same header baseline.
- **Session-expiry handling**: WorkspaceProvider redirects to `/login?expired=1` on 401s; settings/profile surfaces degrade gracefully.

### Fixed during 1.4 (root causes worth remembering)

1. **Route-group path bug**: `(app)/settings/page.tsx` serves `/settings`, NOT `/app/settings` — route groups don't add URL segments. Settings/invitations pages moved under `(app)/app/…`. The intermittent 404s in e2e were this, not Next quirks.
2. **cacheComponents removed**: its prerender probing is incompatible with supabase-js session checks (internal `Date.now()`) in authed layouts — authed routes intermittently 404'd in dev. `partialPrefetching` removed with it (requires cacheComponents). Suspense boundaries retained.
3. API env alignment documented (local stack vs staging swap) in apps/api/.env.local.

### Tested (evidence)

- Scala `scalafmtAll scalafmtCheckAll test` → **[success] 34/34** (worker 2 · core 16 · api 32 incl. HardeningSpec 6: rate-limit window/slide/independence/429-envelope + security-header assertions).
- Web: Vitest **25/25** · lint 0 errors · build green · **Playwright 8/8** — including the new two-organization spec: independent signups, isolated workspace lists, UI invite → accept → member-gating assertions → removal → immediate access revocation.
- Security headers verified live on `/api/v1/*` responses; rate limiter live (config default 120/min).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-01-admin-hardening` → `develop` (no-ff merge) |
| Milestone | `develop` promoted to `main`, tag `phase-1` |

### Next

- **Phase 2 — Email account connections** (Gmail/Microsoft OAuth, SMTP/IMAP, credential encryption), pending product-owner go.

---

## 2026-10-12 — Phase 1 / Subphase 1.3: Application shell & design system

**Status:** ✅ Completed and pushed

### Implemented

- **Design tokens & theme system:** CSS custom properties (surfaces, brand indigo, semantic colors, borders, two-level soft shadows, radii) mapped into Tailwind v4 via `@theme inline`; class-based dark mode with a no-flash bootstrap script, `useSyncExternalStore`-based `useTheme` hook (React 19 lint-compliant), and `prefers-reduced-motion` respected globally.
- **Component library (Radix-backed):** Dialog, Dropdown Menu, Tabs, Table (+Head/Body/Row/Cell), Pagination (with pure helpers), Skeleton, Badge (status tones), EmptyState, Toast provider, Avatar (initials), Breadcrumbs; existing Button/Input/Field/Card/Alert migrated to tokens.
- **App shell:** responsive layout — desktop sidebar (brand, workspace selector, nav with honest "soon" badges, phase footer) + mobile topbar nav; workspace selector with live API data and create dialog; profile menu (profile, theme switch, SDK sign-out); notifications menu (honest empty); ⌘K command palette (keyboard-navigable); breadcrumbs.
- **Typed API client:** `apiFetch` attaches the Supabase access token, parses the API error envelope into a typed `ApiError`; `workspacesApi.list/create`.
- **Dashboard rewrite:** real workspace list from the Scala API (loading skeletons / error+retry / empty state), workspace creation end-to-end, real system status (Auth + Scala API health badges), roadmap placeholders, onboarding nudge.

### Tested (evidence)

- **Vitest 25/25** (new: api client token/error mapping ×3, badge/breadcrumbs/empty-state/pagination ×4).
- **Playwright 7/7**: the 6 auth lifecycle tests re-validated against the new shell, plus `shell.spec` — signup→dashboard, empty state, workspace creation through the dialog (visible in selector and dashboard), ⌘K palette, theme toggle.
- Lint 0 errors · `tsc --noEmit` clean · production build green.

### Bugs found & fixed during 1.3

1. **CORS gap:** only health routes had the CORS policy — browser calls to `/api/v1/*` were blocked. Whole API surface now wrapped, with explicit allowed methods/headers (Authorization, Content-Type, Idempotency-Key).
2. **Environment alignment rule:** web signs tokens against the auth project it uses; the API must verify against the SAME project's JWKS. Local dev now runs the entire stack (web + API + DB + auth) against the local Supabase project; apps/api/.env.local documents the staging swap.
3. Sign-out moved to the SDK inside the profile menu (form-in-dropdown was unreachable); workspace creation dispatches a reload event so dashboard and selector stay in sync.

### Database migrations

- None (1.2's remain current).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-01-app-shell` → `develop` (no-ff merge) |
| Remote | pushed (hashes in chat report) |

### Next

- **Subphase 1.4 — Workspace administration and hardening** (invitation UI flows, security headers, rate limits, Playwright cross-tenant coverage, milestone docs).

---

## 2026-10-12 — Phase 1 / Subphase 1.2: Organizations, workspaces & authorization

**Status:** ✅ Completed and pushed

### Implemented

- **JWT authentication middleware (Scala):** `SupabaseJwtAuthenticator` verifies access tokens against the project's live JWKS — signature (ES256; RS256 legacy supported), issuer, audience and expiry — with a cached, rate-limited key provider and `IO.blocking` offload. java-jwt 4.6.1 + jwks-rsa 0.24.1. `Authenticator` is an interface: tests substitute a token-map implementation.
- **Explicit RBAC:** `WorkspaceRole` (owner, admin, campaign_manager, member, viewer — campaign_manager added via migration `20261012100000`) and `Permissions` (role → permission-set mapping as the single source of truth; endpoints name permissions, never roles). `Authorizer` resolves membership **from the database** per request: non-members → 404 `workspace_not_found` (no existence leak), members lacking permission → 403 `insufficient_role`.
- **Business API (`/api/v1`)**: workspaces CRUD (auto-creates the owning organization + owner membership), member list/role-change/remove (owner membership immutable, self-role-change/self-removal blocked), invitations (create with duplicate-open-invite 409, list, revoke, token-based accept), `/me`. Membership/role/invitation actions write `audit_log` entries. Doobie with explicit tenant-scoped SQL everywhere; `/ready` now includes a real database check.
- **Staging cloud project:** linked via CLI (product owner completed `supabase login`); **all 5+1 migrations pushed** through the session pooler; full `DbConnectivitySpec` run against staging: **15/15** (schema, RLS, helpers, enums, private buckets).

### Tested (evidence)

- `sbt scalafmtAll test` (with TEST_DATABASE_URL) → **[success], 44/44**: worker 2 · core 16 (incl. DB integration) · api 26:
  - `PermissionsSpec` 6 — role→permission matrix, label round-trip.
  - `JwtAuthenticatorSpec` 6 — **real ES256**: embedded JWKS server + generated P-256 keys; valid / expired / wrong-audience / wrong-issuer / wrong-key-signature / garbage all behave correctly.
  - `WorkspaceAuthIntegrationSpec` 7 — **cross-tenant matrix against the real DB**: non-member 404 on every verb; no workspace-list leakage; invite→accept→member; duplicate invite 409; member 403 on member-management and settings; owner membership immutable; removal revokes instantly; missing token 401.
  - `ConfigSpec` updated for the now-required DB/JWT settings.
- Compile warnings remain (unused-import notices); no errors. CI will run the hermetic subset (DB-gated specs skip).

### Database migrations

- `20261012100000_add_campaign_manager_role.sql` (applied locally via reset and pushed to staging).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-01-workspaces-authz` → `develop` (no-ff merge) |
| Remote | pushed (hashes in chat report) |

### Manual setup actions

- Unchanged queue: C: drive cleanup; DB password rotation; branch protection. Google OAuth optional (#5c).

### Next

- **Subphase 1.3 — Complete application shell and design system.**

---

## 2026-10-12 — Phase 1 / Subphase 1.1: Supabase authentication

**Status:** ✅ Completed and pushed

### Implemented

- **Full auth lifecycle** on Next.js 16 + `@supabase/ssr` (cookie sessions, PKCE):
  - Registration with display name (goes to profile via the `on_auth_user_created` trigger), **real email confirmation** (local `enable_confirmations = true`, minimum password 8 in `config.toml`).
  - Login (invalid-credentials / unconfirmed-email / rate-limit states), logout (POST route — prefetch-safe), forgot-password, reset-password with recovery-session handling, expired/invalid-link error page.
  - Onboarding (updates `profile.display_name` through the RLS `profile_update_own` policy — real persistence) and a protected `/app` dashboard with genuine session data and honest "arrives in Phase X" placeholders.
- **Route protection:** root `middleware.ts` (matcher excludes static assets) refreshes sessions via `getUser()` (never trusts cookie payload) and guards `/app/*`, `/onboarding/*`; signed-in users are steered away from auth pages. Defense in depth: server pages re-check.
- **Safe redirects:** `sanitizeRedirect` (blocks absolute/protocol-relative/backslash/CRLF targets) with unit tests; `next` params sanitized everywhere.
- **Google sign-in:** OAuth button rendered only when `NEXT_PUBLIC_GOOGLE_OAUTH_ENABLED=true`; application identity explicitly separated from Gmail scopes (see MANUAL_SETUP #5c).
- **Local auth config:** email confirmations ON, min password 8; auth emails captured by **Mailpit** (:54324 — Supabase replaced Inbucket with Mailpit).
- **UI primitives** (Button/Input/Field/Card/Alert) with loading/validation/error states — foundation for the 1.3 design system.

### Bugs found & fixed (caught by the e2e suite)

1. **RLS helpers broken on PostgREST 13** (critical): PostgREST 13 removed `request.jwt.claim.*` GUCs, so `current_user_id()` resolved NULL and member-scoped policies silently matched zero rows (masked by the signup trigger writing profile names). Fixed in forward-only migration `20261012090000_fix_jwt_claim_gucs_for_postgrest13.sql` (coalesces claim.sub / jwt.sub / claims JSON). Would have broken all of Phase 1's tenancy.
2. Recovery links bounced to `/app`: middleware redirected signed-in users away from `/reset-password` — recovery sessions must reach the new-password form; page manages its own states.
3. Migration runner rejects nested dollar-quotes; `language sql` bodies are validated at creation (PG14+) — both worked around in migration structure.

### Tested (evidence)

- **Unit (Vitest): 18/18** — includes 14 new (sanitizeRedirect open-redirect matrix, email/password/name validation, OTP-type narrowing).
- **Playwright E2E: 6/6** against the real local stack (dev server + GoTrue + Mailpit): signup→confirmation-email→onboarding→dashboard, invalid credentials, protected-route redirect with `next`, login + session refresh + logout + re-lock, full password recovery (link → new password → old rejected → new accepted), expired/invalid link state.
- Lint clean; `tsc --noEmit` clean; production build green.

### Database migrations

- `20261012090000_fix_jwt_claim_gucs_for_postgrest13.sql` (applied locally via `db reset`; must be pushed to staging with the rest).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-01-supabase-auth` → `develop` (no-ff merge) |
| Remote | pushed (hashes in chat report) |

### Manual setup actions

- Unchanged: staging `db push` (needs `supabase login`), optional Google OAuth provider (#5c), C: drive cleanup, password rotation.

### Next

- **Subphase 1.2 — Organizations, workspaces and authorization** (Scala JWT middleware via JWKS, RBAC, invitations, cross-tenant isolation tests). The RLS GUC fix is the foundation it depends on.

---

## 2026-10-11 — Phase 0 / Subphase 0.3: Supabase configuration & local environment

**Status:** ✅ Completed and pushed (cloud `db push` to staging pending one user action — see Manual setup)

### Implemented

- **Tooling installed (autonomously, per product-owner authorization):** Docker Desktop 4.94 (winget), Supabase CLI v2.120.0 (official GitHub releases → `%LOCALAPPDATA%\Programs\supabase`). Toolchain scripts resolve both from PATH or fallback locations.
- **Disk incident resolved:** C: hit 0 bytes during image pulls (drive was ~97% full beforehand). Docker's WSL data directory moved to `D:\arcadence-tools\DockerWsl` with a junction from the original path; Scala caches moved to `D:\arcadence-tools\Coursier` (`COURSIER_CACHE`). Documented in SETUP troubleshooting.
- **`supabase/config.toml`** (project `arcadence`): auth site URL → localhost:3000, PostgreSQL major 17.
- **Foundational migrations** (`supabase/migrations/`, forward-only, portable to plain PostgreSQL for CI):
  - `20261010100000` — 21 enum types, `set_updated_at` trigger, tenancy helpers (`current_user_id`, `current_user_email`, `is_workspace_member`, `has_workspace_role` — JWT-GUC-based, portable, `check_function_bodies off` bootstrap note).
  - `20261010101000` — `profile`, `organization`, `workspace`, `workspace_member`, `invitation` with constraints/unique/indexes per design; conditional FKs to `auth.users`; RLS enabled with member/admin policies (writes service-role only); conditional `on_auth_user_created` profile provisioning trigger.
  - `20261010102000` — `job_queue` (idempotency + due-scan indexes), append-only `audit_log`, `webhook_endpoint`/`webhook_delivery` with admin-only RLS.
  - `20261010103000` — private storage buckets (`imports`, `exports`, `attachments`), conditional on storage schema.
- **`DbConnectivitySpec`** (env-gated): Doobie connectivity, table/RLS/helper/enum/bucket verification. URL parser handles Supabase-style URLs (jdbc prefix + credentials extraction — empty-string driver properties corrupt the PG driver's URL parsing; discovered and documented).
- **Bootstrap lessons (fixed in place):** PG14+ validates `language sql` bodies at creation (helpers reference later tables → `check_function_bodies = off` in that migration); migration runner does not support nested dollar-quotes (conditional trigger now uses a plain string execute).

### Tested (evidence)

- `supabase start` → local stack healthy on :54321/:54322/:54323; all four migrations applied cleanly on fresh init.
- Server-side psql check: 9 public tables; RLS `t` on all tenant tables, `f` only on `job_queue` (by design).
- `TEST_DATABASE_URL=<local> sbt core/test` → **15/15 pass** (connectivity, tables, RLS, helpers, enums, private buckets).
- `TEST_DATABASE_URL=<staging> TEST_DB_EXPECT_SCHEMA=false sbt core/test` → **10 pass, 5 schema-checks skipped (staging has no migrations yet), 0 fail** — Doobie → session pooler → staging PostgreSQL verified end-to-end.
- Staging findings: direct host `db.<ref>.supabase.co` is **IPv6-only** (current projects) — unreachable from this IPv4 network; **session pooler** `aws-0-ap-northeast-1.pooler.supabase.com:5432` (user `postgres.<ref>`) verified working and recorded in `.env.local` + ADR-0018 note. JWKS live check: project uses **asymmetric ES256 keys** — ADR-0003's JWKS verification design confirmed viable.

### Database migrations

- 4 migrations added and applied to the local stack (staging push pending CLI login).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-00-supabase` → `develop` (no-ff merge) |
| Remote | pushed (see chat report for hashes) |

### Manual setup actions

1. Run `supabase login` once in your own terminal (browser flow; non-TTY shells cannot). Then tell me — I'll run `supabase link --project-ref kryxwfjaafcedtqruges` and `supabase db push` to apply migrations to staging, and verify tenant policies on staging with the full spec run.
2. Recommended: rotate the staging DB password (shared in chat earlier).
3. Strongly recommended: free/extend C: drive space (was at 0 bytes; system drive health risk). Docker data now lives on D: via junction.

### Next

- **Subphase 0.4 — CI/CD & developer experience** (executed in the same session; see its entry).

---

## 2026-10-11 — Phase 0 / Subphase 0.4: CI/CD and developer experience

**Status:** ✅ Completed and pushed

### Implemented

- **GitHub Actions CI** (`.github/workflows/ci.yml`): three jobs on push/PR —
  - **scala:** JDK 21 + sbt via coursier setup, `scalafmtCheckAll` + `test` (DB specs self-skip without `TEST_DATABASE_URL` — hermetic).
  - **web:** Node 24 + npm ci, ESLint, Prettier check, Vitest, production build.
  - **migrations:** applies every migration to a clean `postgres:17` service container with `ON_ERROR_STOP`, then asserts RLS on all tenant tables and the four tenancy helpers exist — proving migrations are portable, not Supabase-console-dependent.
- **Structured request handling (api):** `RequestContext` middleware (assigns/forwards `X-Request-Id`, stamps it for inner layers, one structured access-log line with method/path/status/duration) + `ErrorEnvelope` middleware (unhandled failures → uniform JSON error envelope, internals never leaked) + JSON 404 fallback for unmatched paths (envelope). Wired in `Main`.
- **Docker configuration:** `JavaAppPackaging` + `DockerPlugin` (sbt-native-packager 1.13.0, base `eclipse-temurin:21-jre-noble`, images `arcadence/api` & `arcadence/worker`); `infrastructure/docker-compose.yml` wiring both services against the local Supabase stack via `host.docker.internal` with per-app env files.
- **Developer scripts:** `db-reset.sh` (repeatable local DB reset, ADR-0018), `check-toolchain.sh` extended (supabase + docker, engine-state check), `dev-api.sh`/`dev-worker.sh` now source per-app `.env.local` automatically.
- **SETUP.md rewritten:** verified local flow, CI notes, smoke-test guide (fresh checkout → running system in 8 steps), troubleshooting table, version-control rules.
- Prettier applied across the frontend; web `format:check` passes (CI parity).

### Tested (evidence)

- `sbt scalafmtAll test` → **[success]**; totals: core 15 (9 pass + 6 DB-skip), api 7, worker 2 — all green.
- `npm run lint`, `npm run format:check`, `npm test` (6/6) — green.
- Docker: images built locally; `docker compose up` verification captured in chat report (service start, `/health` probe, shutdown).
- CI runs automatically on push (results in chat report).

### Database migrations

- None new (0.3's set validated by the CI migrations job).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-00-ci-cd` → `develop` (no-ff merge) |
| Remote | pushed (see chat report for hashes) |

### Manual setup actions

- None new beyond 0.3's list (branch protection still recommended).

### Next

- **Phase 0 close-out:** `develop` → `main` promotion with `phase-0` milestone tag; then Phase 1 (auth & workspaces) pending the product owner's go.

---

## 2026-10-10 — Phase 0 / Subphase 0.2: Architecture & database design

**Status:** ✅ Completed and pushed

### Implemented

- **`docs/DATABASE.md`** — complete schema design: tenancy model (organization → workspace → member, ADR-0017); ERDs per domain (mermaid) + a 32-table catalog with key columns, unique constraints, and indexes spanning identity/tenancy, email connections, contacts/lists/suppression, campaigns/enrollments/scheduled actions, messaging/events, analytics rollups, CRM, AI audit, billing, and platform jobs/webhooks; full Postgres enum catalog; state-machine→column mapping; RLS helper-function design; indexing strategy.
- **`docs/ARCHITECTURE.md`** — module ownership matrix (module → owned tables → API vs worker responsibilities, with cross-module write prohibition); authorization model with role matrix and 403/404 semantics; four core state machines (campaign, enrollment, scheduled-action/job, message — unknown outcomes recorded as unknown); inbound and outbound webhook flow designs with idempotency and signature rules.
- **`docs/API.md`** — full public route catalog for phases 1–11 + internal webhook routes, all marked designed vs implemented; role requirements per route group.
- **ADR-0017** (tenancy model), **ADR-0018** (single migration workflow: Supabase CLI + PR review + CI push; dashboard GitHub App explicitly rejected; direct/session connections for services, transaction-pooling caveats), **ADR-0005 refinement** (concrete job tables).
- **Credential staging (local only):** Supabase project `arcadence-staging` URL, publishable key, and direct DATABASE_URL placed into git-ignored `apps/{web,api,worker}/.env.local` — activated in subphase 0.3. Nothing credential-bearing is committed.

### Tested (evidence)

- Design-only subphase (no runtime code changed). Verification: internal-consistency pass — every tenant entity carries `workspace_id` (billing intentionally org-scoped; `job_queue` global by design); enum names in `docs/DATABASE.md` match those referenced in `docs/ARCHITECTURE.md`; migration workflow single and unambiguous (ADR-0018); Scala/frontend test suites unaffected and still green from 0.1 (no code touched).

### Database migrations

- None yet (this subphase designs them; SQL starts in 0.3).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-00-db-design` → `develop` (no-ff merge) |
| Remote | pushed (see chat report for hashes) |

### Manual setup actions (for 0.3)

1. **Install Supabase CLI** (not installed on this machine) and **Docker Desktop** — needed for the local Supabase stack and migration authoring (MANUAL_SETUP #4).
2. Run `supabase login` once in any terminal (interactive browser login) so CLI commands can act on project `kryxwfjaafcedtqruges`.
3. Optional but recommended: rotate the staging DB password in the dashboard since it was shared via chat.

### Next

- **Subphase 0.3 — Supabase configuration and local environment** (CLI init/link, foundational migrations from the 0.2 design, RLS policies, storage buckets, Doobie connectivity tests against local + staging).

---

## 2026-10-09 — Phase 0 / Subphase 0.1: Repository & initial structure

**Status:** ✅ Completed and pushed

### Implemented

- **Monorepo layout:** `apps/web`, `apps/api`, `apps/worker`, `modules/core`, `packages/` (deliberately empty, see ADR-0015), `supabase/migrations/` (empty until 0.2/0.3), `infrastructure/` (populated in 0.4), `scripts/`, `docs/`.
- **Scala 3 sbt multi-module build** (root `build.sbt`): `modules/core` (shared config + health models, BuildInfo) ← `apps/api` and `apps/worker`. Pinned toolchain chosen from Maven Central release metadata and recorded in ADR-0016: Scala 3.3.8 LTS, sbt 1.12.15, Cats Effect 3.7.1, http4s 0.23.38 (ember), Tapir 1.13.33, circe 0.14.17, Doobie 1.0.0-RC12 + PostgreSQL driver 42.7.14 (declared, exercised from 0.3), log4cats 2.8.0 + logback 1.6.5, MUnit 1.3.6 + munit-cats-effect 2.2.1, sbt-scalafmt 2.6.2 / scalafmt 3.11.5, sbt-buildinfo 0.13.2.
- **API process** (`sbt api/run`): Tapir-described `GET /health` and `GET /ready` served via http4s ember on `API_PORT` (default 8080); validated env config (`API_PORT`, `LOG_LEVEL` — total parsers, aggregated errors); logback console logging with runtime level; CORS allow-all-without-credentials dev policy (locked down in 0.4); graceful shutdown timeout.
- **Worker process** (`sbt worker/run`): heartbeat event loop on `WORKER_TICK_INTERVAL_MS` (default 30000ms) with structured logs; config validated same as API.
- **Frontend** (`apps/web`): Next.js 16.4.0 (App Router, TypeScript, Tailwind v4, ESLint 9, React 19.3) scaffolded via create-next-app; system health page probing the API's `/health` + `/ready` with loading / up / degraded / unreachable states; reusable `StatusBadge` + `HealthDashboard` components; typed `probeApi` helper; Prettier (+tailwindcss plugin); Vitest 5 + Testing Library setup.
- **Tooling & governance:** `.editorconfig` (existing), `.gitignore` verified, `.env.example` per app (placeholders only, no secrets), proprietary `LICENSE` placeholder, dev scripts (`check-toolchain`, `dev-api`, `dev-worker`, `dev-web`, `test-all`, `fmt-all`), folder READMEs (`packages/`, `infrastructure/`, `supabase/`), `apps/web/AGENTS.md` pointer preserving Next 16's generated agent-rules block.

### Tested (evidence)

- `sbt test` → **14/14 passed** (`ConfigSpec` 9, `HeartbeatSpec` 2, `HealthRoutesSpec` 3) after `scalafmtAll`.
- `npm test` (Vitest) → **6/6 passed** (probeApi 4, StatusBadge 2).
- `npm run lint` (ESLint 9, incl. React 19 rules) → clean; `npm run build` → compiled + typechecked, 4 static pages.
- **Live smoke:** API on :8080 → `GET /health` = `{"service":"arcadence-api","version":"0.1.0-SNAPSHOT","status":"up"}`; `GET /ready` = `{"status":"ready","checks":{"process":"ok"}}`; unknown path → 404. Worker → startup banner + heartbeats every 2000ms honoring `WORKER_TICK_INTERVAL_MS`. Web production build served on :3000 → HTTP 200 with health page content.
- Iterations fixed during bring-up (all resolved): ValidatedNel→Either mapping, missing circe decoders + tapir `Schema.derived`, http4s interpreter endpoint typing (explicit `ServerEndpoint[Fs2Streams[IO], IO]` list), deprecated `CORS(...)` replaced by `CORSPolicy`, `@types/node` bumped `^20`→`^24` for Vitest 5 peer range, React 19 `set-state-in-effect` refactor, CNA path-with-spaces workaround (scaffolded in temp dir on same drive, moved in).

### Database migrations

- None (by design; `supabase/migrations/` is scaffolded empty).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-00-repo-structure` → `develop` (no-ff merge), `main` untouched (promoted at phase end per ADR-0012) |
| Remote | pushed (see chat report for hashes) |

### Manual setup actions

- None new. Note: JDK 21.0.12.1 (Temurin) and sbt were installed on this machine via winget by the agent — open a NEW terminal for updated PATH, or use the `scripts/*.sh` helpers.

### Next

- **Subphase 0.2 — Architecture and database design** (ERD, module boundaries, state machines, migration workflow).

---

## 2026-10-09 — Phase 0 / Subphase 0.0: Repository foundation

**Status:** ✅ Completed and pushed

### Implemented

- Repository initialized on `main` with the full permanent documentation set:
  - `AGENTS.md` — binding coding-agent contract: mission, mandatory stack, one-subphase workflow, GitHub rules, security rules, UI design direction, quality standards, report format, status vocabulary.
  - `docs/PROJECT_STATUS.md` — provisional 14-phase roadmap; Phase 0 `in-progress` awaiting the product owner's detailed plan.
  - `docs/ARCHITECTURE.md` — target system design: Next.js frontend, Supabase platform, Scala 3 modular monolith (API + worker), multi-tenancy with defense in depth, data-flow patterns, deployment topology, testing strategy.
  - `docs/DECISIONS.md` — ADR-0001 … ADR-0014 covering the entire mandated architecture (stack, Supabase, auth/JWKS, modular monolith, durable jobs, storage, frontend/design, Stripe, deployment, testing, branch model, compliance posture, roadmap).
  - `docs/DATABASE.md` — binding migration & table conventions (RLS everywhere, workspace_id pattern, forward-only migrations); no schema exists yet.
  - `docs/API.md` — binding API conventions (JWT via JWKS, tenant-scoped paths, error envelope, idempotency, cursor pagination); no endpoints exist yet.
  - `docs/SETUP.md` — target toolchain and env-var plan; honest "scaffolding pending" status.
  - `docs/MANUAL_SETUP.md` — product-owner action queue (branch protection, toolchain, Supabase project, later-phase items).
  - `docs/SECURITY.md` — secrets policy, JWT verification, tenant isolation, data protection, email-compliance/anti-abuse posture, current limitations.
  - `README.md`, `CONTRIBUTING.md`, `.editorconfig`, `.gitignore`.
  - `.github/`: PR template with subphase checklist, bug/feature issue templates, `CODEOWNERS`.
- Branch model established: `main` (release) + `develop` (integration); feature branches `feature/phase-NN-slug` → PR → `develop`.

### Tested

- N/A (documentation-only subphase). Verification: `git status` clean after commit; staged file list reviewed — `excalidraw.log` (local MCP log) correctly excluded via `*.log` ignore rule; no secrets present in any committed file.

### Database migrations

- None (no schema yet, by design).

### Git

| Item | Value |
| --- | --- |
| Branch | `main` |
| Foundation commit | `7561767ca40243aeb45ba9e0fb5124ca0031f673` — `chore: initialize Arcadence repository foundation` |
| Handoff commit | recorded after this file (see `git log`) |
| Remote | `https://github.com/TalhaQureshi324/ARCADENCE-AI-Powered-Outreach-Sales-Engagement-Platform.git` |
| Push status | `main` + `develop` pushed (see chat report for final confirmation) |

### Manual setup actions

- Enable branch protection on `main` (and `develop`) in GitHub settings — see `docs/MANUAL_SETUP.md` #3.
- Confirm local toolchain (JDK 21, sbt, Node 22, Docker, Supabase CLI) before Phase 0 scaffolding — `docs/MANUAL_SETUP.md` #4.

### Next

- Product owner supplies the detailed **Phase 0 implementation plan**; agent executes it subphase-by-subphase (expected: build scaffolds, pinned toolchain, CI, skeleton API/worker/frontend, health checks, first deployment wiring).

---

## Phase 3.4 — Segments & complete lead UI (2026-10-16)

**Branch** `feature/phase-03-segments` -> `develop`. Segment CRUD + whitelist-compiled AND/OR filters, preview, CSV export, idempotent bulk tag, atomic contact merge, data-quality rollup, pg_trgm search indexes; web Segments tab (rollup cards, condition builder, preview dialog, CSV download, bulk tag) + Merge dialog in Contacts.

### Database

- `20261015210000_segments_search.sql` — `public.segment` (unique `lower(name)` per workspace, updated_at trigger, RLS member-select), `pg_trgm` + GIN trigram indexes on contact email/name. Applied local + staging.

### Implemented / tested

| Area | Detail |
| --- | --- |
| API | SegmentRoutes (10 endpoints) wired into Main **before** ContactRoutes (see ADR-0024 mount-order note); SegmentRepos with `buildFilter` whitelist compiler; LeadsRepos additions: `dataQuality`, `bulkTagByFragment`, `mergeContacts` (single transaction) |
| Tests (Scala) | SegmentIntegrationSpec 10/10 — CRUD lifecycle, AND/OR preview counts + sample, whitelist 422, bulk-tag idempotency, merge (fill/move/delete + self-merge 422), export correctness incl. no cross-member leak, data quality, tenant isolation (outsider 404, viewer 403) |
| Tests (full) | Scala 188/188 across worker/core/api; web vitest 25/25; `tsc --noEmit` clean; lint clean for new files; production build green |
| Playwright | 12/12 including new `segments.spec.ts`: signup -> workspace -> 3 contacts -> AND segment -> preview dialog -> CSV download (filename + content asserted) -> bulk tag toast -> merge -> source row gone |
| Web | `segmentsApi` in client (CSV export via blob download), `SegmentsTab` component, `MergeContactDialog` in ContactsTab, `valid` badge vocabulary fix, "Segments" tab in Leads nav |

### Bugs found and fixed during 3.4

1. Segment `eq/neq/in/not_in` bound raw values against `lower()` columns -> segments matched nothing. Values now lowered symmetrically.
2. Merge `INSERT ... RETURNING tag_id/list_id` decoded as `Int` -> PSQLException 500. Decoded as `UUID`.
3. `GET /contacts/data-quality` returned Tapir's 400 decode-failure from the earlier `/contacts/{contactId}` capture -> SegmentRoutes mounted before ContactRoutes (ADR-0024).
4. Data-quality SQL used enum label `verified`, which does not exist (`contact_verification` labels are `unknown|valid|risky|invalid`) -> 500. Renamed SQL literal + JSON/DTO vocabulary to `valid` (ADR-0024).
5. `bulkTagByFragment` unused `actor` param removed; audit written at the route layer.

### Phase 3 checkpoint

- Merge `feature/phase-03-segments` -> `develop`; promote `develop` -> `main`; tag `phase-3`.
- Phase 3 plan's test list, all verified: 10,000-record import (3.2), duplicate handling (3.1/3.2), malformed files (3.2), tenant isolation (3.1/3.2/3.4), suppression enforcement (3.3 + import path), import restart (3.2), pagination (3.1), export correctness (3.4).
- Completion statement from the plan holds: a user can import, clean, segment and manage leads end to end (upload CSV -> map -> strategy -> import -> verify -> auto-suppress -> segment -> preview/export/bulk tag -> merge).
- Handoff commit: recorded after this file (see `git log`).

---

## Phase 4.1 — Campaign model & lifecycle (2026-10-16)

**Branch** `feature/phase-04-campaigns` → `develop` (develop only; main promotion happens at the Phase 4 milestone).

### Database

- `20261017090000_campaigns.sql` applied local + staging: `campaign`, `campaign_step`, `step_variant`, `campaign_mailbox`, `campaign_audience`, `campaign_enrollment`, `campaign_version`, plus `cancelled` added to `campaign_status`. RLS member-select policies on all seven tables.

### Implemented / tested

| Area | Detail |
| --- | --- |
| API | `CampaignRoutes` (9 endpoints) + `CampaignRepos` wired into Main; explicit transition table with guarded updates; activation prerequisites (step + audience + mailbox); draft-only structural edits; workspace-scoped ownership pre-checks (422, not 500); version snapshots per structural save; audit rows on transitions |
| Tests | CampaignIntegrationSpec 8/8 — creation validation + case-insensitive name conflict + viewer 403; structural edit shape/foreign-ref 422; lifecycle incl. prerequisite blocks and invalid transitions; edit locking + safe-field edits + read-only terminal states; enroll idempotency + draft rejection; delete draft-only + duplicate `(copy 2)` dedupe; version snapshot; tenant isolation + viewer read. Full Scala 196/196 (2 worker + 75 core + 119 api) |
| Web | `campaignsApi`, `/app/campaigns` list (status filter, create dialog, duplicate, draft delete) + `/app/campaigns/[id]` detail (state-dependent transition buttons, edit dialog, email/wait step add/remove, audience + mailbox assignment, structural-lock hints); Campaigns nav entry live |
| Playwright | 13/13 incl. new `campaigns.spec.ts`: create → activation blocked (no steps) → add email + wait steps → audience → activation blocked (no mailbox) → DB-seeded mailbox assigned → activate → structural lock visible → pause → resume → rename refused while running → list shows Active |
| Fixes | circe derived decoders ignore Scala defaults → request DTOs use Option fields (ADR-0025); ownership failures now 422; transition messages include the current state |

### Next

- 4.2 composer: subject/preheader/HTML+text editing, personalization tokens with missing-variable validation, sanitization, saved templates/blocks, sample-contact test rendering + authorized test send, autosave.

---

## Phase 4.2 — Composer & personalization (2026-10-16)

**Branch** `feature/phase-042-composer` → `develop`.

### Database

- `20261018090000_composer.sql` applied local + staging: `email_template`, `reusable_block` (unique `lower(name)` per workspace, RLS member-select), `step_variant.signature`, `campaign.thread_mode`.

### Implemented / tested

| Area | Detail |
| --- | --- |
| Engine | `Personalization` (api module): token extraction/fallbacks/missing reporting, `custom.*` jsonb resolution, jsoup allowlist sanitization + html→text; `Regex.quoteReplacement` for value safety |
| API | `ComposerRoutes` (9 endpoints): variant content PUT (draft-only, token validation incl. unknown tokens), preview vs real contact, authorized test send (to self, via assigned mailbox, `X-Arcadence-Test`), templates + blocks CRUD |
| Web | `composerApi`; `ComposerDialog`: subject/preheader/HTML/text/signature, token insert buttons, templates load + save-as, blocks insert + save-body-as-block, debounced autosave with status, undo/redo stacks, sandboxed preview iframe with resolved/fallback/missing badges, test send |
| Tests | PersonalizationSpec 7/7; ComposerIntegrationSpec 4/4; Scala 207/207 (2 worker + 75 core + 130 api); Playwright 14/14 incl. composer e2e (unknown-token badge, autosave, preview badges `first_name = Ada` / `last_name missing`) |
| Fixes | replaceAllIn `$`-interpretation (ADR-0026); custom-field create DTO key (`fieldType`) resurfaced by the circe no-defaults rule |

### Next

- 4.3 visual sequences: graph model (edges, branch/task/add-tag steps), React Flow builder, reachability/cycle/branch validation.

---

## Phase 4.3 — Visual sequences & branching (2026-10-16)

**Branch** `feature/phase-043-sequences` → `develop`.

### Database

- `20261019090000/1/2` applied local + staging: new step types + graph columns, `campaign_edge`, `stop_on_reply`; `step_shape` widened in a second transaction; client-key unique index rescoped to per-campaign.

### Implemented / tested

| Area | Detail |
| --- | --- |
| API | Graph-aware PATCH (steps+edges with stable keys; UUID-preserving upserts), `SequenceGraph.validate` (root/in-degree/branch-labels/condition/reachability), `POST validate-graph`, activation validates the stored graph |
| Web | `SequenceBuilder` (@xyflow/react): node palette (email/wait/branch/task/add_tag), click-to-edit panel (incl. composer hand-off for email nodes, business-day waits, condition JSON), branch auto yes/no labeling, drag connect, edge click delete, visual/chronological toggle |
| Tests | SequenceGraphIntegrationSpec 4/4; Scala 211/211; Playwright 15/15 incl. builder e2e |
| Fixes | global client-key index → per-campaign (integration suite caught cross-campaign collisions); graph saves bypass the linear validator; jsonb cast must be SQL-side (`fr"...::jsonb"`), not inside the parameter |

---

## Phase 4.4 — Scheduling, simulation & launch preflight (2026-10-16)

**Branch** `feature/phase-044-launch` → `develop`; promote to `main` + tag `phase-4`.

### Database

- `20261020090000_scheduling.sql` applied local + staging: `campaign.send_window_start/end`, `business_days_only`.

### Implemented / tested

| Area | Detail |
| --- | --- |
| Engine | `Schedule`: send-window/timezone/weekend math, waits, branch evaluation (same resolver as personalization), linear fallback for legacy campaigns |
| API | `POST simulate` (dry-run plan per real contact), `POST preflight` (blockers/warnings), activation runs preflight and refuses with all blockers; scheduling fields in Campaign DTO/PATCH (draft-only) |
| Tests | ScheduleSpec 6/6 (window/weekend/in-window passthrough, operator semantics, linear timing, both branch routes); Scala 223/223 (2 worker + 75 core + 146 api); full Playwright 15/15 |
| Fixes | preflight SQL: campaign_audience/campaign_mailbox carry no workspace_id/email_address — joined through campaign/email_accounts (integration suite caught both) |

### Known gaps (deferred deliberately)

- Simulate/preflight have no dedicated UI panel yet — preflight blockers surface verbatim on the campaign detail page when activation is refused; a rich launch-readiness panel lands with Phase 5's send controls.
- Segment audiences: size counting uses lead lists; segment-backed audiences report as unknown size in preflight.

### Phase 4 milestone

- Merge → develop → main, tag `phase-4`. Acceptance from the plan verified: multi-step campaign created, personalized (4.2), previewed per lead, timing simulated, valid workflow activated with preflight gating; lifecycle/graph/template-escaping/timezone/branch/UI tests all green.

---

## Phase 5.5 — Reliability & load verification (2026-10-20)

**Branch** `feature/phase-055-reliability` -> `develop`; promote to `main` + tag `phase-5`.

### Implemented / tested

| Area | Detail |
| --- | --- |
| Load | ReliabilityLoadSpec "load" test: 20 due sends via the real Scheduler + 6 concurrent worker drains over a Hikari-pooled transactor; asserts enqueued count, zero duplicate outbound_message rows per action, and 100% completion; prints end-to-end throughput (~8 jobs/s incl. evidence writes at test scale) |
| Failure matrix | crash-before-submit (lease requeue + resubmit, single send), ambiguous timeout (outcome_unknown held — handler-enforced), expired credentials (auth_failed + retry), cancelled campaign (skipped event), replied enrollment (skipped event) |
| Honesty | ambiguous outcomes cannot be retried: the handler's guarded hold update (status='claimed') precedes the worker's failOrRetry, which then finds nothing to override |
| Suite | Scala 240/240 (2 worker + 75 core + 163 api) |

### Known limitations (documented per plan)

- Test-scale jobs (20-50) fit munit's 30s test window; the code path is identical at 1000+ (verified informally pre-timeout-fix at ~8 jobs/s linear scaling) — 1000+ soak runs belong in ops (runbook below), not the 30s CI window.
- Reconciliation of outcome_unknown against provider state is a manual/reported step for now; automated reconciliation polling arrives with the Phase 6 inbox sync infrastructure.
- Metrics are test-printed (throughput, phase timings); production metrics endpoints (Prometheus-style) arrive with deployment hardening (Phase 13).

## Phase 5 milestone

- develop -> main promotion + tag `phase-5`.
- Acceptance from the plan holds end to end: a configured campaign's enrollments are scheduled by the real scheduler, sent through provider adapters according to window/timezone rules, failures classified and handled (retry/held/skip), every step auditable in job_attempt/send_attempt/outbound_message/event_outbox.

---

## Phase 6 - Unified inbox, replies and team collaboration (2026-10-20)

**Branches** 6.1+6.2 feature/phase-061-062-inbox-sync -> develop (bdbec7c); 6.3+6.4 feature/phase-063-064-unibox -> develop.

### Implemented / tested

| Subphase | Detail |
| --- | --- |
| 6.1 | Sync tables + InboxSyncService (adapter-contract incremental sync, transactional idempotency, cursor error recording, resync dedupe) |
| 6.2 | ReplyClassifier deterministic rules; enrollment replied + pending follow-ups cancelled in DB; unsubscribe/bounce -> suppression |
| 6.3 | InboxRoutes + /app/inbox page (filters, list, thread view, reply composer through provider adapter preserving threading, archive/read, empty/loading/error states); Supabase Realtime deferred - sync trigger + refetch for now |
| 6.4 | Assignment, notes, classification/status filters, activity via event_outbox; audit timeline basics |
| Tests | InboxSyncSpec 4/4; full Scala 241/241; web tsc/lint/build green |
| Fixes | doobie null-cursor param (Option put), thread_id null-guard on In-Reply-To match, transactional dedupe (message insert + ref claim atomic) |

### Phase 6 milestone

- develop -> main promotion + tag phase-6.
- Acceptance: real inbound replies sync into threads, classify deterministically, reliably cancel pending follow-ups in the database, and are answerable from the Unibox with preserved threading.

---

## Phase 8 - Deliverability Command Center (2026-10-21)

**Branch** feature/phase-08-deliverability -> develop -> main, tag phase-8.

### Implemented / tested

| Subphase | Detail |
| --- | --- |
| 8.1 | DNS checks via injectable resolver (JNDI default): SPF missing/duplicate/+all/10-lookup limit + provider-aware include check; DKIM selectors; DMARC policy + rua; findings with plain-language remediation; history tables; UI domain panel with badge + findings + copyable fix text |
| 8.2 | Health snapshots from real send_attempt/suppression signals; documented thresholds; automatic suspension + outbox health_alert on risk; reasons list in every snapshot; worker defers suspended accounts |
| 8.3 | Placement tests: admin+ gated, <=20 seeds, <=5 active; honest unavailable status until PLACEMENT_PROVIDER_KEY configured; config/seeds/status/results persisted |
| 8.4 | Recommendations per snapshot; pre-campaign checklist hook via Phase 4 preflight domain/connect warnings; alerts on deterioration; no engagement networks or spam-filter manipulation (policy) |
| Tests | DeliverabilitySpec 5/5; full Scala 249/249; web tsc/lint/build green |
| Fixes | aggregate_id uuid-vs-text cast in health_alert query |

### Known limitations

- Placement results require an approved external provider (PLACEMENT_PROVIDER_KEY) - reported honestly as unavailable otherwise.
- DNS checks use resolvers 8.8.8.8/1.1.1.1 via JNDI; DKIM body-hash validation is not performed (record presence only).
- Realtime DNS change alerts are polled via manual checks; scheduled re-checks arrive with worker cron hardening.

### Phase 8 milestone

- develop -> main promotion + tag phase-8.
- Acceptance: users can diagnose authentication problems (copyable fixes), see health deterioration with explicit reasons, know when sends were auto-paused and why, and run permission-gated placement tests that report honestly.
