# AGENTS.md — Arcadence Coding Agent Instructions

This file is the binding operating contract for every AI coding agent (and human contributor) working on Arcadence. Read it completely before modifying anything. Where this file and a chat prompt disagree, this file wins unless the product owner explicitly overrides it in writing.

---

## 1. Product mission

Arcadence is a production-oriented, multi-tenant SaaS for cold-email outreach, B2B lead management, campaign automation, unified inbox, CRM, deliverability intelligence, analytics, AI-assisted workflows, and sales engagement. The functional target is broad parity with Instantly.ai, followed by improvements in user experience, transparency, reporting, reliability, and agency management.

Do not reproduce copyrighted designs, proprietary code, private APIs, or unlicensed datasets from any competitor.

The software must be **genuinely functional, not a visual prototype.** A feature is never complete if it contains:

- mock data masquerading as live data,
- nonfunctional controls,
- stubbed production endpoints, or
- unresolved critical failures.

## 2. Mandatory technology decisions

These decisions are settled. Do not re-litigate them in normal subphases.

| Area | Decision |
| --- | --- |
| Backend | Scala 3, sbt, Cats Effect, http4s, Tapir, Doobie over PostgreSQL JDBC |
| Frontend | Next.js App Router, React, TypeScript, Tailwind CSS, shadcn/ui or accessible Radix-based components |
| Database | Supabase PostgreSQL, version-controlled SQL migrations |
| Authentication | Supabase Auth with the current supported SSR approach |
| Files | Supabase Storage, private buckets where appropriate |
| Queues | Durable PostgreSQL scheduling/outbox; pgmq (Supabase Queues) when appropriate |
| Payments | Stripe (billing phase) |
| Architecture | Modular monolith with separately runnable API and worker processes |
| Deployment | Dockerized Scala services on a suitable VPS/container host; frontend on Vercel or another Next.js-compatible host |
| Repository | GitHub |
| Testing | ScalaTest/MUnit, Vitest, Playwright, integration tests |

Do not introduce another primary backend language, replace Supabase, or introduce microservices without a documented technical reason and the product owner's approval.

Exact dependency versions are pinned when the build scaffold is created (Phase 0) and recorded in `docs/ARCHITECTURE.md` and the build files. Select currently supported, mutually compatible versions.

## 3. Development workflow — one subphase at a time

The rule for the whole project:

> **One prompt → one completed subphase → tests → GitHub push → implementation report → next prompt.**

Smaller stages reduce broken dependencies, lost context, unnecessary rewrites, and partially completed features. Never batch multiple subphases into one change.

For every subphase:

1. Inspect the repository and existing documentation before modifying anything.
2. Read `AGENTS.md`, `docs/PROJECT_STATUS.md`, `docs/ARCHITECTURE.md`, and `docs/DECISIONS.md`.
3. Identify the requested phase and its next unfinished subphase.
4. Produce a concise task plan and list of affected modules.
5. Implement **only the current subphase**, including database, backend, frontend, integration, and tests as applicable.
6. Preserve working features and maintain backward compatibility unless explicitly authorized to change them.
7. Never mark a feature complete if it violates §1's definition of done.
8. If external credentials or approvals are missing, complete everything possible with test doubles and sandbox integrations, then identify the precise blocking user action in the report and `docs/MANUAL_SETUP.md`.
9. Run the appropriate formatting, build, type checks, unit tests, and integration tests. Run Playwright checks whenever UI behavior changes.
10. Update project documentation, architecture decisions, and progress status.
11. Commit and push the tested subphase to GitHub.
12. Stop and provide the implementation report (§9). Do not automatically start the next subphase.

## 4. GitHub rules — mandatory after every subphase

- Check `git status`, the current branch, and the remote before starting.
- Never overwrite uncommitted user changes.
- Branch model: `main` is the protected release branch; `develop` is the integration branch. Work happens on descriptive feature branches such as `feature/phase-02-gmail-oauth`, branched from `develop`, merged back into `develop` through a PR. `develop` is promoted to `main` per completed phase (or release cut).
- Use meaningful conventional commits, for example: `feat(email): implement Gmail OAuth connection`.
- Push every successfully completed and tested subphase to the configured GitHub remote.
- Use milestone tags after completing a full phase when appropriate (e.g. `phase-01`).
- Do not force-push, overwrite history, disable branch protections, or commit secrets.
- If GitHub is not authenticated or remote access fails, report the exact failure and provide the commands the product owner must run. **Never claim a push succeeded if it did not.**
- A subphase is not "fully completed" until its test results and final Git status are accurately reported.

## 5. Security and data rules

- Never commit API keys, OAuth tokens, passwords, Supabase secret keys, or other credentials. Secrets live in environment variables / secret managers only; `.env` files (except `.env.example` templates) are git-ignored.
- Store only public Supabase configuration in browser-visible environment variables (`NEXT_PUBLIC_*` / anon key). The Supabase service-role key bypasses RLS — it must never ship to the browser and never substitutes for tenant checks.
- Enforce workspace isolation and RBAC in the Scala API **and** through appropriate RLS policies in Supabase. Scala-side authorization must be independent of RLS: every read and write carries an authorization check.
- Validate Supabase JWT issuer, audience, expiration, signature, and authorized algorithms using a supported verification mechanism (JWKS).
- Use version-controlled SQL migrations; never make undocumented production-only schema changes.
- Maintain consent, suppression, opt-out, and compliance controls as first-class features.
- Never build mechanisms specifically intended to evade email-provider abuse controls (no fake-engagement warmup networks, no fingerprint rotation, no spam-filter evasion).
- Never assume network submission guarantees exactly-once email delivery; design for at-least-once with idempotency and safe handling of unknown outcomes.

See `docs/SECURITY.md` for the full security posture.

## 6. UI design direction

Design is created directly in code. No Figma.

**Design language: sophisticated minimalism with subtle spatial depth and restrained neumorphism.**

- Clear typography, disciplined spacing, soft borders, subtle shadows.
- Professional charts, responsive layouts.
- Restrained indigo accents over neutral navy and white/gray surfaces.
- Light/dark themes where feasible.
- No gradient overuse, no glassmorphism excess, no floating decorations, no gratuitous animation.
- Complex workflow screens stay highly readable.
- Accessibility and keyboard navigation are requirements, not niceties.
- Every feature ships with appropriate loading, success, empty, validation, error, and permission-denied states.

## 7. Quality standards

- Real working functionality with documented acceptance criteria.
- Correct database relationships, constraints, and indexes.
- Type-safe Scala domain models and explicit error handling (no thrown-string culture; model errors as values at domain boundaries).
- Request/response validation and API documentation (OpenAPI generated from Tapir).
- Durable background work and idempotent processing.
- Meaningful tests for normal and failure scenarios.
- Structured logs, monitoring hooks, and auditability.
- Responsive, accessible UX.
- No silent errors or misleading success messages.

## 8. Permanent documentation set

These files must be kept current with every subphase:

| File | Purpose |
| --- | --- |
| `AGENTS.md` | Coding agent instructions and workflow (this file) |
| `docs/PROJECT_STATUS.md` | Phase/subphase status |
| `docs/ARCHITECTURE.md` | System design |
| `docs/DECISIONS.md` | Architecture decision records |
| `docs/DATABASE.md` | Schema overview and migration conventions |
| `docs/API.md` | Endpoint documentation and API conventions |
| `docs/SETUP.md` | Local setup |
| `docs/MANUAL_SETUP.md` | Tasks requiring the product owner's external accounts |
| `docs/HANDOFF.md` | What was implemented, tested, committed, and pushed |
| `docs/SECURITY.md` | Security decisions and posture |

If a document is not yet applicable, keep a concise honest version rather than inventing completed functionality.

## 9. Required implementation report after every subphase

1. Subphase completed.
2. Features implemented.
3. Important files changed.
4. Database migrations added.
5. Tests executed, with passing/failing results.
6. Bugs fixed and remaining known issues.
7. Manual setup actions the product owner must perform.
8. Git branch, commit hash, push status, and PR URL if available.
9. What can be verified in the UI.
10. Next subphase and its dependencies.

Always distinguish **implemented**, **tested**, **simulated**, **blocked**, and **planned** work. Do not claim completion without evidence.

## 10. Status vocabulary

- **Implemented** — code exists in the repository.
- **Tested** — implemented and covered by executed, passing checks (unit/integration/Playwright as applicable).
- **Simulated** — exercised against a test double or sandbox; real integration still pending.
- **Blocked** — cannot proceed; the precise blocking user action is documented in `docs/MANUAL_SETUP.md`.
- **Planned** — designed/scheduled but not started.

## 11. Current state pointer

- The repository foundation (documentation, conventions, branch model) is established.
- **Phase 0** detailed implementation plan is provided by the product owner in chat; agents execute it subphase by subphase per §3.
- Always confirm the live status in `docs/PROJECT_STATUS.md` — it is the single source of truth for progress.
