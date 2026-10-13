# Arcadence — Project Status

> Single source of truth for progress. Updated at the end of **every** subphase. Statuses: `planned` · `in-progress` · `implemented` · `tested` · `blocked`. See `AGENTS.md` §10 for definitions.

**Last updated:** 2026-10-13 (2.1 — Phase 2 started)

---

## Current phase

**Phase 0 — Foundation** · status: **complete** (promoted to `main`, tag `phase-0`)
**Phase 1 — Auth, tenancy & UI foundation** · status: **complete** (promoted to `main`, tag `phase-1`)
**Phase 2 — Email account connections** · status: **in-progress** (2.1 done)

## Phase roadmap (provisional — ADR-0014)

The phase breakdown below is provisional; the product owner supplies each phase's detailed plan before it starts, and this table is updated to match.

| Phase | Title | Status |
| --- | --- | --- |
| 0 | Foundation — repo, tooling, scaffolds, CI, skeleton services, health checks, deployment wiring | **in-progress** |
| 1 | Authentication & workspaces — Supabase Auth SSR, multi-tenant model, RBAC, RLS | planned |
| 2 | Email account connections — Gmail OAuth, Microsoft OAuth, SMTP/IMAP, credential storage | in-progress |
| 3 | Lead management — leads, lists, import/export, verification hooks, suppression | planned |
| 4 | Campaigns & sequences — builder, scheduling, personalization | planned |
| 5 | Sending engine — durable send queue, provider submission, tracking, bounce handling | planned |
| 6 | Unified inbox — two-way sync, threading, realtime updates | planned |
| 7 | CRM — accounts, contacts, deals, pipelines, activity timeline | planned |
| 8 | Deliverability intelligence — monitoring, reputation signals, guidance | planned |
| 9 | Analytics & reporting | planned |
| 10 | AI workflows — assisted copy, replies, lead scoring | planned |
| 11 | Billing & plans — Stripe subscriptions, usage limits | planned |
| 12 | Agency management — multi-client workspaces, team roles, reporting | planned |
| 13 | Hardening & launch — performance, security audit, production cutover | planned |

## Subphase detail — Phase 0

| # | Subphase | Status | Notes |
| --- | --- | --- | --- |
| 0.0 | Repository foundation — docs, conventions, branch model, GitHub push | **tested** | Docs complete; pushed to `main` + `develop`. |
| 0.1 | Repository & initial structure — monorepo scaffold, Next.js app, Scala 3 multi-module build, health endpoints, tests, tooling | **tested** | 20/20 tests pass (Scala 14, web 6); live smoke verified (API `/health` `/ready`, worker heartbeat, web page). See `docs/HANDOFF.md`. |
| 0.2 | Architecture & database design — full ERD (32 tables), module ownership, state machines, webhook flows, tenancy/RBAC model, migration workflow | **tested** | Design verified for internal consistency: every tenant entity carries `workspace_id` (billing org-scoped by design, platform jobs global); enums cataloged; ADR-0017/0018 added. See `docs/HANDOFF.md`. |
| 0.3 | Supabase configuration & local environment | **tested** | Local stack healthy; 4 foundational migrations applied; RLS + helpers verified via Doobie spec (15/15 local; staging connectivity 10 pass via session pooler — direct host is IPv6-only). Staging `db push` pending product-owner CLI login. |
| 0.4 | CI/CD & developer experience | **tested** | GitHub Actions (scala / web / migrations jobs), request-ID + error-envelope middleware with tests, Docker images + compose verified, db-reset + env-loading scripts, SETUP smoke/troubleshooting/VC rules. |
| — | **Phase 0 complete** — `develop` promoted to `main`, tag `phase-0` | done | See milestones below. |

### Subphase detail — Phase 1

| # | Subphase | Status | Notes |
| --- | --- | --- | --- |
| 1.1 | Supabase authentication — signup, email verification, login, logout, recovery, sessions, protected routes | **tested** | 18/18 unit + **6/6 Playwright e2e** (signup→verify→onboarding→dashboard, invalid creds, protected redirect, session refresh + logout, full password recovery incl. old-password rejection, expired-link state). Google button gated behind `NEXT_PUBLIC_GOOGLE_OAUTH_ENABLED`. Caught + fixed a real RLS bug (PostgREST 13 GUC change) via e2e. |
| 1.2 | Organizations, workspaces & authorization (JWT middleware, RBAC, cross-tenant tests) | **tested** | Supabase JWTs verified via JWKS (ES256, iss/aud/exp); explicit permission model (owner/admin/campaign_manager/member/viewer); workspaces + members + invitations API with audit trail; 7/7 cross-tenant isolation tests. Staging migrations + RLS verified 15/15. |
| 1.3 | Application shell & design system | **tested** | Token-based light/dark theme, 12 UI primitives (Radix dialog/dropdown/tabs, table, pagination, skeletons, badges, toasts, empty states, avatar, breadcrumbs), responsive sidebar shell with workspace selector (live API data), ⌘K command palette, profile/notifications menus; dashboard with real API data + honest empty/roadmap states; 7/7 Playwright e2e incl. workspace creation. |
| 1.4 | Workspace administration & hardening | **tested** | Settings UI (members/invitations/profile tabs, permission-aware, live API data); invitation accept page; per-caller rate limiting; security headers (API + Next.js); session-expiry handling. 8/8 Playwright incl. two-organization cross-tenant lifecycle. |

### Subphase detail — Phase 2

| # | Subphase | Status | Notes |
| --- | --- | --- | --- |
| 2.1 | Email account architecture — 6 tables (email_accounts, account_credentials, sender_domains, account_groups, account_health_events, provider_sync_state), EmailProvider contract + capability matrix + account state machine, AES-256-GCM credential encryption (ADR-0020), email-accounts management UI (provider cards, groups CRUD, health trail) | **tested** | Migration `20261013090000` applied locally; Scala 85/85 (2 worker + 37 core + 46 api incl. 14 integration: capability matrix, group CRUD, permission gating, lifecycle transitions, credential purge, cross-tenant isolation); web unit 25/25, production build green; live API + Playwright run recorded in HANDOFF. Found + fixed real null-semantics bug (circe explicit-null PATCH decoding). |
| 2.2 | Gmail integration — OAuth authorization-code + PKCE + offline refresh (ADR-0021), HMAC-signed state with callback-side permission re-check, GmailProvider adapter (send/reply/profile/inbox-list/revoke; stub-server-tested), authorized test send, reconnect via upsert, disconnect with best-effort Google revocation, configured-aware connect UI + test-send dialog + callback outcome banners | **tested** | Scala 117/117 (2 worker + 57 core incl. GmailProviderSpec 11 + GmailMimeSpec 5 + OAuthStateSignerSpec 4 + 58 api incl. GmailFlowIntegrationSpec 12: tamper/expiry/denial/expiry-of-permission paths, encrypted-credential round-trip, reconnection dedupe, test-send permission gating); live e2e covers the forged-state callback through the real API; MANUAL_SETUP 6a updated (redirect URI now the API callback). Google console credentials still pending (item 6a) — real-Google verification blocked on that. |

## Completed milestones

| Date | Milestone | Evidence |
| --- | --- | --- |
| 2026-10-09 | Repository foundation established and pushed | Commits `7561767`, `a1cc95b` on `main`/`develop` |
| 2026-10-09 | Subphase 0.1 — both applications build, run and pass tests | sbt `test` 14/14; Vitest 6/6; live endpoint smoke tests (see `docs/HANDOFF.md`) |
| 2026-10-10 | Subphase 0.2 — complete architecture & database design | `docs/DATABASE.md` (ERD + 32-table catalog), `docs/ARCHITECTURE.md` (ownership, state machines, webhooks), `docs/API.md` (route catalog), ADR-0017/0018 |
| 2026-10-11 | Subphase 0.3 — Supabase configured, local env verified | Local stack healthy; 4 migrations applied; 15/15 DB specs vs local; staging connectivity via session pooler; JWKS (ES256) live-verified |
| 2026-10-11 | Subphase 0.4 — CI/CD & DX | GitHub Actions 3-job CI, request-ID/error-envelope middleware, Docker images + compose, reproducible scripts |
| 2026-10-11 | **Phase 0 complete** — promoted to `main` | Tag `phase-0`; see `docs/HANDOFF.md` for hashes |

## Known blockers

None.

## Manual setup queue

See [`docs/MANUAL_SETUP.md`](MANUAL_SETUP.md) — no items currently block Phase 0. Branch protection (#3) remains recommended before PR-based subphases.
