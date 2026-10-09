# Arcadence — Project Status

> Single source of truth for progress. Updated at the end of **every** subphase. Statuses: `planned` · `in-progress` · `implemented` · `tested` · `blocked`. See `AGENTS.md` §10 for definitions.

**Last updated:** 2026-10-09

---

## Current phase

**Phase 0 — Foundation** · status: `in-progress` (awaiting detailed implementation plan from the product owner; repository foundation complete)

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
| 0.0 | Repository foundation — docs, conventions, branch model, GitHub push | **tested** | This deliverable. Docs complete; pushed to `main` + `develop`. See `docs/HANDOFF.md`. |
| 0.1+ | Pending the product owner's detailed Phase 0 implementation plan | planned | Expected scope (per charter): build scaffolds, pinned toolchain, CI, skeleton API/worker/web, health checks, env conventions. |

## Completed milestones

| Date | Milestone | Evidence |
| --- | --- | --- |
| 2026-10-09 | Repository foundation established and pushed | Initial commits on `main` and `develop`; see `docs/HANDOFF.md` |

## Known blockers

None.

## Manual setup queue

See [`docs/MANUAL_SETUP.md`](MANUAL_SETUP.md) — no items currently block Phase 0.
