# Arcadence — Architecture Decision Records

Format: lightweight ADRs. Statuses: `Proposed` → `Accepted` → optionally `Superseded by ADR-XXXX`. "Accepted (PO mandate)" means the product owner fixed the decision in the project charter; agents implement rather than re-litigate.

---

## ADR-0001 — Scala 3 + Typelevel stack as the backend

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate)
- **Context:** Arcadence needs a strongly typed, long-lived backend that handles durable background work, high-volume email I/O, and explicit error handling.
- **Decision:** Scala 3 with sbt, Cats Effect 3, http4s, Tapir, and Doobie over the PostgreSQL JDBC driver. No second primary backend language.
- **Consequences:** Type-level modeling of domain errors; effect-safe concurrency; OpenAPI derived from Tapir descriptions; JVM operational skills required. Supported, mutually compatible versions are pinned at Phase 0 and recorded here.

## ADR-0002 — Supabase PostgreSQL as the system of record

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate)
- **Context:** The product needs relational integrity, RLS-based tenant isolation, and an integrated auth/storage/realtime platform.
- **Decision:** Supabase PostgreSQL holds all domain data. All schema changes are version-controlled SQL migrations; no undocumented production-only changes.
- **Consequences:** Schema evolution discipline (forward-only migrations); RLS required on every tenant table; database access from Scala via Doobie.

## ADR-0003 — Supabase Auth with Next.js SSR; JWT verification via JWKS in Scala

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate)
- **Context:** Server-side rendering needs server-side sessions; the Scala API must independently trust callers.
- **Decision:** Supabase Auth with the current supported SSR integration on the frontend. The Scala API validates every JWT's issuer, audience, expiration, signature, and algorithms using Supabase's JWKS endpoint. Only public/anon configuration is exposed to the browser.
- **Consequences:** One identity provider across stack; Scala middleware owns verification; service-role key never leaves server-side, tightly scoped code.

## ADR-0004 — Modular monolith with separately runnable API and worker processes

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate)
- **Context:** Microservices would add operational overhead unjustified at this stage; a single HTTP app would entangle latency-sensitive API traffic with long-running email work.
- **Decision:** One Scala codebase organized into modules (`identity`, `workspaces`, `campaigns`, `sending`, `inbox`, …), compiled into two deployable processes: **API** and **Worker**. No intra-monolith network hops. Microservices require a documented technical reason and PO approval.
- **Consequences:** Cheap refactoring across modules; independent scaling of workers; module boundaries enforced by package discipline and tests.

## ADR-0005 — Durable PostgreSQL jobs (outbox/job tables), pgmq where appropriate

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate)
- **Context:** Email sending and syncing must survive crashes and deploys, with retries and unknown-outcome safety. Delivery is **at-least-once**, never assumed exactly-once.
- **Decision:** Durable job/outbox tables in PostgreSQL with `FOR UPDATE SKIP LOCKED` claiming, idempotency keys, and backoff retries; Supabase Queues (pgmq) adopted where queue semantics fit better than job tables.
- **Consequences:** Transactional consistency between state changes and queued work; operational simplicity; explicit handling of unknown outcomes (recorded as unknown, never silently assumed delivered/failed).
- **Refinement (2026-10-10, Phase 0.2):** concrete realization is the `scheduled_action` table (campaign sends; idempotency keys; partial index on pending `run_after`), the generic `job_queue` table (platform jobs), and `webhook_delivery` (outbound, retries) — state machine shared (`action_status`), claimed via `FOR UPDATE SKIP LOCKED`. See `docs/DATABASE.md` §6.4/§6.10 and `docs/ARCHITECTURE.md` §7.3.

## ADR-0006 — Supabase Storage with private buckets

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate)
- **Context:** Imports, attachments, and exports contain tenant-sensitive data.
- **Decision:** Private buckets only; browser access exclusively via short-lived signed URLs minted by the Scala API after authorization.
- **Consequences:** No public object URLs; every file access is authorization-checked server-side.

## ADR-0007 — Frontend: Next.js App Router + Tailwind + shadcn/ui (Radix); React Flow for canvases

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate)
- **Context:** The product needs a fast, accessible, themeable UI including complex workflow canvases.
- **Decision:** Next.js App Router with TypeScript, Tailwind CSS, and shadcn/ui (Radix-based, accessible) components. React Flow for campaign/automation canvases. Design is created directly in code — no Figma.
- **Consequences:** Accessible primitives by default; design system codified in code (see ADR-0008); canvas features standardized on one library.

## ADR-0008 — Design language: sophisticated minimalism with restrained neumorphism

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate)
- **Context:** Complex workflow screens (campaigns, inbox, CRM) must stay highly readable while feeling premium.
- **Decision:** Clear typography, disciplined spacing, soft borders, subtle shadows, restrained indigo accents over neutral navy/white/gray, light+dark themes. No gradient overuse, glassmorphism excess, floating decorations, or gratuitous animation. Accessibility and keyboard navigation are requirements. Every feature ships loading, success, empty, validation, error, and permission-denied states.
- **Consequences:** Tokens and component variants encode this language; reviewers reject visual noise as a defect.

## ADR-0009 — Stripe for billing

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate) · **Phase:** billing phase
- **Decision:** Stripe owns subscriptions, plans, and payment flows; the `billing` module maps plans to usage limits and gates features server-side.
- **Consequences:** Webhook ingestion must be idempotent and durable (ADR-0005 path); no billing logic in the frontend.

## ADR-0010 — Deployment: Dockerized JVM services on VPS/container host; frontend on Vercel

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate)
- **Decision:** Scala API and Worker ship as separate Docker images from one build onto a VPS or container host. The Next.js frontend deploys to Vercel or another Next.js-compatible host. Migrations are applied in a controlled CI/CLI step.
- **Consequences:** Worker-first horizontal scaling; environment parity via Docker; final host selection (incl. DB pooling mode) documented when the deployment phase lands.

## ADR-0011 — Testing stack and definition of tested

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate)
- **Decision:** ScalaTest/MUnit for Scala; Vitest for frontend units; Playwright for E2E whenever UI behavior changes; integration tests against a real PostgreSQL (Testcontainers or equivalent). Cross-tenant isolation suites are mandatory per resource.
- **Consequences:** A subphase is "tested" only when the applicable checks have been executed and pass; results are reported verbatim (per `AGENTS.md` §9).

## ADR-0012 — Branch model: `main` + `develop` integration branch, conventional commits

- **Date:** 2026-10-09 · **Status:** Accepted
- **Context:** The product owner wants a professional, reviewable history with release discipline.
- **Decision:** `main` is the protected release branch. `develop` is the integration branch. Each subphase ships on a feature branch `feature/phase-NN-slug` branched from `develop` and lands via PR. Completing a full phase may cut `develop` → `main` with a milestone tag (e.g. `phase-01`). Conventional commits; no force-pushes; no history rewrites.
- **Consequences:** PR-per-subphase review rhythm; branch protection on `main` (and later `develop`) is configured by the product owner in GitHub settings (`docs/MANUAL_SETUP.md`).

## ADR-0013 — Compliance posture: no abuse-evasion mechanisms

- **Date:** 2026-10-09 · **Status:** Accepted (PO mandate)
- **Decision:** Arcadence implements consent capture, suppression lists, opt-out/unsubscribe handling, and honest deliverability *monitoring and guidance*. It never implements mechanisms specifically intended to evade email-provider abuse controls (e.g., fake-engagement warmup networks, fingerprint rotation, spam-filter evasion).
- **Consequences:** Feature requests in this category are rejected by default; deliverability features are framed as monitoring + best-practice enforcement.

## ADR-0014 — Provisional phase roadmap (14 phases)

- **Date:** 2026-10-09 · **Status:** Accepted as provisional
- **Decision:** Delivery is organized into 14 phases broken into independently testable subphases (see `docs/PROJECT_STATUS.md`). The detailed Phase 0 plan is supplied by the product owner; later phases are refined just-in-time. Statuses live in `docs/PROJECT_STATUS.md`, not here.
- **Consequences:** One prompt → one subphase → tests → push → report; no batching of subphases.

## ADR-0015 — Monorepo layout

- **Date:** 2026-10-09 · **Status:** Accepted
- **Context:** Phase 0.1 requires a maintainable monorepo holding the Next.js frontend, the Scala API and worker, shared code, migrations, and infrastructure in one repository with one history.
- **Decision:**
  - `apps/web` — Next.js application (own package.json; scaffolded with create-next-app).
  - `apps/api`, `apps/worker` — runnable Scala processes.
  - `modules/core` — shared Scala library code (config, health contracts, later: platform kernel).
  - `packages/` — reserved for shared frontend packages; **intentionally empty** until a second consumer exists (no speculative abstractions).
  - `supabase/migrations/` — the single version-controlled migration location.
  - `infrastructure/` — Docker/CI/deployment artifacts (from 0.4).
  - `scripts/` — developer shell scripts.
  - The **sbt build lives at the repository root** and spans `modules/*` and `apps/*` via explicit project paths; the frontend is independent under `apps/web`.
- **Consequences:** One PR can span full-stack subphases; sbt module boundaries enforce backend layering; frontend dependency management stays isolated inside `apps/web` until a JS workspace is actually needed.

## ADR-0016 — Pinned toolchain versions (Phase 0.1)

- **Date:** 2026-10-09 · **Status:** Accepted
- **Context:** `AGENTS.md` §2 requires supported, mutually compatible versions, documented. Versions were selected from Maven Central release metadata (latest stable lines), cross-checked against actual POMs.
- **Decision (backend):**

| Dependency | Pin | Rationale |
| --- | --- | --- |
| Scala | 3.3.8 | LTS line; supported |
| sbt | 1.12.15 | Latest mature 1.x stable (1.13/2.x too fresh for plugin ecosystem) |
| Cats Effect | 3.7.1 | Latest stable CE3 |
| http4s | 0.23.38 | Latest **stable** line — the 1.0 series is still milestone-only (1.0.0-M49); Tapir 1.13.33's own POM targets http4s 0.23.38, proving compatibility |
| Tapir | 1.13.33 | Latest stable; drives OpenAPI |
| circe | 0.14.17 | Latest stable (0.15 is milestone) |
| Doobie | 1.0.0-RC12 | Current supported line; RC-line is the published stable of doobie |
| PostgreSQL driver | 42.7.14 | Latest |
| log4cats / logback | 2.8.0 / 1.6.5 | Latest stable |
| Test | MUnit 1.3.6 + munit-cats-effect 2.2.1 | Fast, CE-integrated |
| Tooling | sbt-scalafmt 2.6.2 (scalafmt 3.11.5), sbt-buildinfo 0.13.2 | Formatting + build identity |

- **Decision (frontend):** Next.js 16.4.0 (App Router), React 19.3, Tailwind CSS v4, ESLint 9 + eslint-config-next, TypeScript 5, Vitest 5 + Testing Library, Prettier + prettier-plugin-tailwindcss, `@types/node` ^24 (matches Node 24 runtime and Vitest 5 peer range).
- **Consequences:** Version bumps are deliberate ADR-level changes; the http4s 1.0 migration is deferred until Tapir/http4s ship compatible stable releases.

## ADR-0017 — Tenancy model: organization → workspace → member

- **Date:** 2026-10-10 · **Status:** Accepted (Phase 0.2)
- **Context:** The product serves both single operators and agencies running many client accounts (Instantly's "workspace" concept). We need one unit for billing/legal ownership and a different unit for day-to-day tenant isolation.
- **Decision:**
  - **organization** — billing/legal owner; owns Stripe subscriptions and plan limits (billing tables are org-scoped, no `workspace_id`).
  - **workspace** — the tenant isolation unit; **every** business table carries `workspace_id`; always belongs to exactly one organization.
  - **workspace_member** — user↔workspace with role `owner | admin | member | viewer`; authorization matrix in `docs/ARCHITECTURE.md` §3.
  - `profile` mirrors `auth.users` 1:1 (same UUID PK); Supabase Auth remains the only identity source.
- **Consequences:** Agencies get clean per-client data isolation with one billing relationship; RLS keys on workspace membership; plan limits can be enforced at either org or workspace level (`usage_counter` supports both).

## ADR-0018 — Single migration workflow: Supabase CLI, PR-reviewed, CI-pushed; direct/session connections for services

- **Date:** 2026-10-10 · **Status:** Accepted (Phase 0.2)
- **Context:** Multiple workflows exist in the Supabase ecosystem (dashboard SQL editor, GitHub App auto-deploy, CLI migrations). We need exactly one unambiguous, auditable path from schema change to production.
- **Decision:**
  - Source of truth: `supabase/migrations/<UTC-timestamp>_<name>.sql`, forward-only, PR-reviewed like all code.
  - **Local:** Supabase CLI stack (`supabase start` / `supabase db reset` / `supabase db diff -f <name>` to author migrations).
  - **Staging/production:** `supabase link --project-ref <ref>` + `supabase db push` from CI using a stored access token (staging project `arcadence-staging` first; a separate production project later).
  - The **Supabase dashboard GitHub App integration is not used** — it bypasses PR review flow and duplicates CI responsibility.
  - **Connections:** Scala services use direct (5432) or session-pooled connections appropriate to persistent workers; transaction pooling (6543) only for short-lived request paths with prepared statements disabled (`prepareThreshold=0`) and no cross-transaction session assumptions.
- **Consequences:** Deterministic, reviewable schema evolution; one migration set applies identically from laptop to CI; no dashboard drift.
- **Refinement (2026-10-11, Phase 0.3):** current Supabase projects expose the **direct connection IPv6-only**; on IPv4-only networks/services use the **session pooler** (`aws-<n>-<region>.pooler.supabase.com:5432`, user `postgres.<project-ref>`) — session mode preserves JDBC prepared statements, so the transaction-pooling caveats don't apply. Verified live against staging (`aws-0-ap-northeast-1`). Production host selection (direct vs pooler) re-evaluated at deployment with this constraint.

## ADR-0019 — Email account naming: email_accounts / account_credentials (supersedes provisional email_connection / email_credential)

- **Date:** 2026-10-13 · **Status:** Accepted (Phase 2.1)
- **Context:** The Phase 0 ERD provisionally named the sending-identity tables `email_connection` / `email_credential`. The product owner's Phase 2 plan explicitly specifies `email_accounts`, `account_credentials`, `sender_domains`, `account_groups`, `account_health_events`, `provider_sync_state`. Per AGENTS.md §1 the owner's written plan wins; no dependent tables exist yet (campaign assignments, conversations and messages arrive in Phases 4–6), so the rename is free today and expensive later.
- **Decision:** Implement the Phase 2 names. Later phases FK to `email_accounts.id`. The account state enum also takes the Phase 2 values (`connected`, `reconnect_required`, `rate_limited`, `suspended`, `error`, `disconnected`), replacing the provisional `connection_status` values before any consumer existed (migration `20261013090000`).
- **Consequences:** docs/DATABASE.md §6.2 and docs/API.md §3.3 track the new names; provider-facing code speaks "email account", not "connection". "email_connections" survives only as the OAuth route prefix planned for 2.2/2.3.

## ADR-0020 — Credential encryption: AES-256-GCM with a single managed key, versioned ciphertexts, RLS-locked storage

- **Date:** 2026-10-13 · **Status:** Accepted (Phase 2.1)
- **Context:** Provider credentials (OAuth refresh tokens, SMTP/IMAP passwords) must never reach the browser, logs, or PostgREST. The Scala service is the only trusted writer; Supabase Vault is not used because credentials are read exclusively by the API/worker service connection, not by SQL clients.
- **Decision:**
  - Algorithm: AES-256-GCM (`CredentialEncryptor` in `arcadence-core`). Ciphertext layout: 12-byte random nonce ‖ ciphertext ‖ 16-byte GCM tag. Authentication failures (wrong key, tampering) are indistinguishable by design and surfaced as `DecryptFailed.Rejected`, never exceptions.
  - Key management: exactly one 32-byte key provisioned out-of-band as `EMAIL_ENCRYPTION_KEY` (base64), validated at API startup (fail-fast; wrong length or non-base64 refuses boot). `account_credentials.encryption_key_version` (currently 1) tags ciphertexts so a future rotation re-encrypts lazily without a flag day.
  - Storage: `account_credentials` carries RLS enabled with **no policies** and revoked grants for `anon`/`authenticated` — PostgREST can never read or write it; only the service connection (which bypasses RLS) touches the table.
  - Hygiene: disconnecting an account purges its credentials row; deleting an account cascades credentials. No API response shape includes credential fields (enforced by integration test).
- **Consequences:** Key loss is unrecoverable by design — generation and backup are product-owner manual-setup steps (`docs/MANUAL_SETUP.md`). Key rotation requires a lazy re-encrypt pass (planned with the first rotation need, not before).

## ADR-0021 — Gmail integration: authorization-code + PKCE, minimum scopes, HMAC state, in-memory access refresh

- **Date:** 2026-10-13 · **Status:** Accepted (Phase 2.2)
- **Context:** The Gmail adapter (`GmailProvider`, arcadence-core) must connect mailboxes via real Google OAuth with no secrets in the browser, while supporting an unverified testing app during development.
- **Decision:**
  - **Flow:** authorization-code with PKCE (S256) and `access_type=offline&prompt=consent` for a refresh token. The web app asks the API (`POST /api/v1/email-connections/oauth/google/start`, permission-checked) for a consent URL and navigates the browser; Google returns to the **Scala API callback**, which validates the state, completes the exchange server-side, persists encrypted credentials, and 303-redirects back to `/app/email-accounts?gmail=…`.
  - **State:** stateless HMAC-SHA256-signed payload (workspace + user + PKCE verifier + nonce + 10-minute expiry), keyed by a domain-separated derivation of `EMAIL_ENCRYPTION_KEY`. Tamper/expiry/consent-denial each land on distinct, user-visible outcomes.
  - **Authorization re-check at callback:** the state's user must still hold `manage_email_accounts` on the state's workspace — a role change between start and callback cannot complete a connection.
  - **Scopes (minimum necessary):** `gmail.send` + `gmail.readonly` + `userinfo.email`. `gmail.send`/`gmail.readonly` are **restricted** scopes: production use requires Google OAuth verification including a CASA security assessment; an unverified testing app with explicit test users is **not production approval** (documented in MANUAL_SETUP 6a).
  - **Tokens:** the refresh token is the durable secret (AES-256-GCM at rest, ADR-0020); refreshed access tokens live in memory only (Google allows multiple outstanding access tokens, so re-refreshing is safe and no partial-write state exists). Disconnect purges the stored credentials AND best-effort revokes the grant at Google before the purge.
  - **Placement:** the adapter lives in arcadence-core (http4s-client + circe added) so the worker can drive sync/reply flows in later phases without an API dependency; Google endpoints are injectable for stub-server tests.
- **Consequences:** connect is honest about configuration state (`configured=false` surfaces "not configured" UI, `POST start` → 503 `not_configured`); Gmail message/thread ids and sync cursors persist via `provider_sync_state` (full incremental sync arrives in Phase 6); revocation is best-effort (Google may already have revoked).

## ADR-0022 — Microsoft 365 integration: Graph over delegated Mail.Send/Mail.Read, draft-then-send, skip-token inbox, revokeSignInSessions

- **Date:** 2026-10-14 · **Status:** Accepted (Phase 2.3)
- **Context:** The Microsoft adapter (`GraphProvider`, arcadence-core) must mirror the Gmail flow's security shape (ADR-0021) while respecting Graph specifics: no message id from `sendMail`, OData cursors, tenant-scoped authorities, and tenant-level app blocking.
- **Decision:**
  - **Flow:** authorization-code + PKCE (S256) against `login.microsoftonline.com/{tenant}/oauth2/v2.0` with `response_mode=query`; the same HMAC-signed state (ADR-0021) and callback-side permission re-check. Tenant is configurable (`MSFT_OAUTH_TENANT`, default `common`) and recorded.
  - **Scopes (delegated, minimum):** `Mail.Send`, `Mail.Read`, `User.Read`, `offline_access` (+openid/email). Tenant admins can block the app — consent failures (`invalid_grant`/`consent_required`/`interaction_required`) surface as typed `InvalidCredentials`/`PermissionDenied`, never as silent retries.
  - **Send:** draft-then-send (`POST /me/messages` → `POST /me/messages/{id}/send`) so the provider message id is captured — `POST /me/sendMail` returns only 202 with no id. Replies use `createReply` (thread context) + body patch + send.
  - **Inbox:** folder listing with `$orderby=receivedDateTime desc` and the `@odata.nextLink` `$skiptoken` as durable cursor. **Phase 6 design headroom:** the same folder surface converts to `/delta` links, and Graph change notifications (subscriptions/webhooks) can replace polling without touching the account model — `provider_sync_state.cursor` already stores opaque tokens.
  - **Revocation:** Graph has no per-app token revoke; disconnect calls `POST /me/revokeSignInSessions` (invalidates this app's refresh tokens) best-effort before the local credential purge.
  - **Error mapping:** 400 consent-family → InvalidCredentials; 401 → InvalidCredentials; 403 → PermissionDenied (tenant blocking surfaces here); 429 → RateLimited with Retry-After; 503/504 → Unreachable. Provider codes/messages are preserved in the (secret-free) detail.
- **Consequences:** the dashboard treats Gmail and Microsoft identically (`configured` flag, connect button, reconnect, test send); account selection (`login_hint`) is wired at the authorize URL for future multi-account flows; real-Google/real-Microsoft verification both wait on the respective console credentials (MANUAL_SETUP 6a/7).

## ADR-0023 — SMTP/IMAP adapter: Jakarta Mail (angus), mandatory TLS, server-side credential validation, UID cursors

- **Date:** 2026-10-14 · **Status:** Accepted (Phase 2.4)
- **Context:** The third provider path (configurable SMTP submission + IMAP retrieval) must complete the capability matrix without weakening security: no plaintext, no "disable two-factor" advice, and credentials that never leak through errors, logs, or responses.
- **Decision:**
  - **Library:** Jakarta Mail on the angus-mail implementation (`angus-mail` + `jakarta.mail-api`), blocking calls wrapped in `IO.blocking`. GreenMail (`greenmail`, test scope) provides REAL SMTP/IMAP servers for protocol-level tests, run in implicit-TLS mode with test-scoped JVM trust properties (cleared after the suite; production defaults always validate certificates).
  - **Security modes:** `ssl` (implicit TLS) or `starttls` (`starttls.required=true` — the upgrade is mandatory, never opportunistic). Plaintext is rejected during shape validation, before any network attempt. Deployments may pin socket factories / trust anchors globally via the standard `mail.*` system properties, which the adapter exposes as session-level defaults (never overriding explicit settings).
  - **Validation:** the connect endpoint validates credentials by REALLY signing in to both servers before creating the account; failures map to typed errors (auth failure → 400-family InvalidCredentials surfaced as 502 `provider_error`; unknown host / refused → Unreachable; TLS handshake → guidance to check the security mode). Passwords are never echoed in any detail, response, or log.
  - **Duplicates:** same workspace + lower(email) → 409 `conflict` without a second server round-trip.
  - **Cursors:** inbox sync pages newest-25 with the last seen IMAP UID as cursor (`jakarta.mail.UIDFolder`); CONDSTORE/QRESYNC and the unified sync engine arrive in Phase 6.
  - **OAuth-only operations** (`completeAuthorization`, `refresh`, `revokeAccess`) return `NotSupported` values; disconnect simply purges the local encrypted credentials.
- **Consequences:** all three providers now expose consistent capabilities through one registry; the connect form collects app-password-style credentials with explicit provider guidance (Google App Passwords / Entra app passwords / provider-specific permission notes in MANUAL_SETUP); TLS behavior against real production servers remains a provider-console verification item, noted in MANUAL_SETUP.

## ADR-0024 — Segmentation: whitelist-compiled filter trees, mount-order constraint, `valid` verification vocabulary (Phase 3.4)

**Date:** 2026-10-16 · **Status:** accepted

**Context.** Saved segments let users re-query their contact base with AND/OR rules. The filter payload arrives from the browser as JSON, so the API must translate it to SQL without opening an injection path, and the vocabulary must match the rest of the platform.

**Decision.**
- Filters are a typed tree (`{"op":"and"|"or","conditions":[…]}`; groups nest). `SegmentRepos.buildFilter` whitelists fields (`state, verification_status, company, tag, region, job_title, email`) and operators (`eq, neq, contains, not_contains, in, not_in`); column names come from a closed match, every value is a bound parameter, and unknown fields/operators are rejected as `validation_failed` (validated at segment create/update, not only at query time).
- All text comparisons are case-insensitive: columns go through `lower()` and bound values are lowered symmetrically (`eq/neq/in/not_in` lower the parameter; `contains` lowercases before wrapping in `%…%`). The first implementation bound raw values against lowered columns and silently matched nothing — the integration test caught it.
- Tag conditions compile to `EXISTS (… contact_tag …)` so counts stay correct without fan-out joins.
- `SegmentRoutes` must be mounted **before** `ContactRoutes`: Tapir's default decode-failure handler answers 400 for a failed `path[UUID]` capture, which would shadow the static `/contacts/data-quality` path with a 400 before SegmentRoutes ever saw the request.
- The public verification vocabulary is the enum's own label `valid` (not `verified`): syntax+MX checks can never claim verification (Phase 3.3 rule), so data-quality rollups and DTOs use `valid`.
- Contact merge is one `ConnectionIO` transaction: fill empty kept fields from the source, move tags/list memberships (`INSERT … ON CONFLICT DO NOTHING RETURNING`, returning UUIDs — the row count of actually-moved rows), repoint `source_provenance`, delete the source; audited with the per-field counts.

**Consequences.** Segment previews/exports/bulk actions are injection-safe by construction and case-insensitive by default; adding a filterable field is a one-line whitelist change plus a column mapping; the mount-order constraint is documented in `docs/API.md` to protect future route additions.

## ADR-0025 — Campaign lifecycle: six states, draft-only structural authority, versioned definitions (Phase 4.1)

**Date:** 2026-10-16 · **Status:** accepted

**Context.** Campaigns acquire enrollments when active; mutating what those enrollments must execute (steps, variants, audience, mailboxes) mid-flight corrupts their meaning, and changing scheduling identity (name, timezone) breaks in-progress timing and audit trails.

**Decision.**
- Lifecycle: `draft → active`, `active ⇄ paused`, `active → completed | cancelled`, `paused → cancelled`, `draft | completed | cancelled → archived` (terminal). Enforced in Scala via an explicit transition table and applied with a status-conditional `UPDATE`, so concurrent mutations lose safely (409 with a reload hint).
- Activation requires ≥1 step, ≥1 audience, ≥1 mailbox; deeper preflight checks (contacts, domain authentication, suppressions) arrive in Phase 4.4 and will gate the same endpoint.
- Structural authority is draft-only. Active/paused campaigns accept `description`, `tags`, `daily_cap` edits only; terminal states are fully read-only (duplicate to iterate). Rejections name the active-enrollment count when one exists.
- Every structural save snapshots the step/variant definition into `campaign_version` (append-only), so any enrollment's executed definition stays reconstructable.
- Request DTOs use `Option` fields with defaults applied in the route layer — circe's derived decoders do not honour Scala default values, and missing JSON keys otherwise fail the whole body with an opaque 400 (found and fixed during the 4.1 integration tests).
- Sending-account and audience references are existence-checked against the workspace BEFORE any mutation, mapping failures to 422 validation errors instead of 500s.

**Consequences.** Enrollment semantics stay trustworthy from the first send (Phase 5); the UI can present precise state-dependent controls; Phase 4.3 branch step types extend the `step_type` enum without touching lifecycle logic.

## ADR-0026 — Composer: quoteReplacement token rendering, allowlist sanitization, self-addressed test sends (Phase 4.2)

**Date:** 2026-10-16 · **Status:** accepted

**Decision.**
- Personalization tokens (`{{field}}`, `{{field|fallback}}`, `{{custom.x}}`) render through a regex replacement whose values pass through `Regex.quoteReplacement` — Scala's function-form `replaceAllIn` still routes results through `Matcher.appendReplacement`, so unescaped lead data like `$&` would otherwise act as replacement syntax (found by the unit suite).
- Missing variables render as empty and are reported in the response (`missing`); they are never silently fabricated, and preflight (4.4) treats them as blockers. Fallbacks are counted separately (`fellBack`).
- All campaign HTML (bodies, signatures, blocks, variant writes) is sanitized with a jsoup allowlist (scripts, event handlers, iframes, styles removed; `mailto:` links allowed) at WRITE time, so stored content is already safe and the preview shows exactly what a send contains. Plaintext alternatives derive from the sanitized HTML when the text body is left empty.
- Preview renders against a REAL workspace contact chosen in the UI; there is no synthetic sample contact.
- Test sends render for the chosen sample contact but always deliver to the authenticated user's own address (never to the contact), through one of the campaign's assigned mailboxes, tagged `X-Arcadence-Test`.
- Variant content has a dedicated PUT (draft-only, no version snapshot) so editor autosave does not churn `campaign_version` history.

**Consequences.** Send-time rendering (Phase 5) reuses the same engine; sanitization is centralized; template/block content is safe by construction.

## ADR-0027 — Sequence graphs: client-keyed steps, merge-free validation, stored-graph activation gating (Phase 4.3)

**Date:** 2026-10-16 · **Status:** accepted

**Decision.**
- The editor owns stable client keys; the server maps key → existing step UUID on every save, so step identity (and future enrollment step references) survives edits. Keys are unique per campaign (the first globally-unique index collided across campaigns — caught by the integration suite).
- Validation rules: exactly one root, every other step exactly one incoming edge (merges rejected — execution must be deterministic), branch steps need exactly two outgoing edges labeled `yes`/`no` plus a whitelisted condition, other steps at most one outgoing edge, full reachability from the root. With in-degree ≤ 1, reachability implies acyclicity. Legacy linear campaigns (no edges) validate as position-ordered chains.
- Graph saves bypass the Phase 4.1 linear validator (which only knows email/wait) and run `SequenceGraph.validate` before any mutation; activation re-validates the STORED graph and refuses to activate invalid sequences.
- Branch conditions reuse the segment operator whitelist over contact fields resolvable in Scala (`custom.*` included); they are evaluated per contact by the simulator (4.4) and the scheduler (Phase 5).
- The visual builder (React Flow / @xyflow/react) is view-state only; the backend graph is the single source of truth (node positions are auto-layered, not persisted).

**Consequences.** Phase 5's scheduler executes a validated DAG deterministically; new step types extend one enum + one validator.

## ADR-0028 — Preflight gates activation; simulation requires a real contact (Phase 4.4)

**Date:** 2026-10-16 · **Status:** accepted

**Decision.**
- `preflightFor` computes blockers + warnings once; the transition endpoint runs it on `activate` and refuses with every blocker message (409). The standalone `POST preflight` endpoint exposes the same result for the UI.
- Blockers: missing/invalid sequence graph, unknown personalization tokens, no audience, no mailbox, no CONNECTED mailbox. Warnings: empty lead lists, suppressed contacts in the audience, unverified sending domain (advisory until deliverability enforcement, Phase 8).
- Simulation (`Schedule.simulate`) walks the stored graph for a REAL contact — branch conditions resolve with the same field resolution as personalization, so there is no synthetic contact and no fabricated data. Planned send moments land inside the campaign's window/timezone with weekend skipping.
- Scheduling fields (window, business-days-only) are draft-only edits for the same reason as timezone.

**Consequences.** Only valid campaigns activate; pausing/resuming preserves enrollment state (structural locks unchanged); Phase 5's scheduler reuses `Schedule` for due-time computation.

## ADR-0029 - Sending queue: one SKIP LOCKED queue, outbox for events (Phase 5.1)

**Date:** 2026-10-20 - **Status:** accepted

**Decision.**
- public.scheduled_action is the single sending queue: workers claim due jobs with FOR UPDATE SKIP LOCKED under a lease (locked_at/locked_by, attempts incremented at claim); crashed workers' leases expire and requeueExpiredLeases returns jobs to pending. No in-memory queues, no cron loop.
- Event delivery uses public.event_outbox: events are written with the state change they describe and dispatched at-least-once by the worker to downstream sinks (webhooks arrive later). The outbox never carries sends - scheduling and event delivery have disjoint mechanisms by design.
- public.job_queue (Phase 0) remains for platform-level jobs; same claim pattern, disjoint from sending.
- Idempotency is application-level: every enqueue carries a stable business key (send:{enrollmentId}:{stepId}:{dueDate} from 5.2) enforced by a unique index with ON CONFLICT DO NOTHING, so at-least-once enqueueing cannot create duplicate jobs.
- Failures retry with capped exponential backoff + jitter (Retry-After honored from 5.3) and dead-letter as failed at max_attempts, keeping the full job_attempt trail.
- The worker is a separate process (apps/worker) that recovers expired leases on boot and resumes after restart; ambiguous send outcomes (5.3) are held for reconciliation, never retried blind.

**Consequences.** Duplicate suppression is structural; crash recovery is bounded by the lease timeout; throughput scales horizontally with worker count without coordination beyond SKIP LOCKED.
