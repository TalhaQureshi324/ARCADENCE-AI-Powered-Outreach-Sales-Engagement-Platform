# Arcadence — Project Status

> Single source of truth for progress. Updated at the end of **every** subphase. Statuses: `planned` · `in-progress` · `implemented` · `tested` · `blocked`. See `AGENTS.md` §10 for definitions.

**Last updated:** 2026-10-10

---

## Current phase

**Phase 0 — Foundation** · status: `in-progress` (subphases 0.0–0.2 complete; 0.3 next — Supabase CLI + Docker Desktop needed)

## Phase roadmap (provisional — ADR-0014)

The phase breakdown below is provisional; the product owner supplies each phase's detailed plan before it starts, and this table is updated to match.

| Phase | Title | Status |
| --- | --- | --- |
| 0 | Foundation — repo, tooling, scaffolds, CI, skeleton services, health checks, deployment wiring | **in-progress** |
| 1 | Authentication & workspaces — Supabase Auth SSR, multi-tenant model, RBAC, RLS | planned |
| 2 | Email account connections — Gmail OAuth, Microsoft OAuth, SMTP/IMAP, credential storage | planned |
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
| 0.3 | Supabase configuration & local environment | planned | **Blocked on tooling:** Supabase CLI + Docker Desktop not installed (see MANUAL_SETUP #4). Project `arcadence-staging` created; credentials staged in git-ignored `.env.local` files. |
| 0.4 | CI/CD & developer experience | planned | |

## Completed milestones

| Date | Milestone | Evidence |
| --- | --- | --- |
| 2026-10-09 | Repository foundation established and pushed | Commits `7561767`, `a1cc95b` on `main`/`develop` |
| 2026-10-09 | Subphase 0.1 — both applications build, run and pass tests | sbt `test` 14/14; Vitest 6/6; live endpoint smoke tests (see `docs/HANDOFF.md`) |
| 2026-10-10 | Subphase 0.2 — complete architecture & database design | `docs/DATABASE.md` (ERD + 32-table catalog), `docs/ARCHITECTURE.md` (ownership, state machines, webhooks), `docs/API.md` (route catalog), ADR-0017/0018 |

## Known blockers

None.

## Manual setup queue

See [`docs/MANUAL_SETUP.md`](MANUAL_SETUP.md) — no items currently block Phase 0. Branch protection (#3) remains recommended before PR-based subphases.
