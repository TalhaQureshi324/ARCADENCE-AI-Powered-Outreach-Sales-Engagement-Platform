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
