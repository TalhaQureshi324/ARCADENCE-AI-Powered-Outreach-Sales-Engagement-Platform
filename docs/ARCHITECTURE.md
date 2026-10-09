# Arcadence — System Architecture

> Status: **Foundation.** The architecture below is the agreed target design. Concrete module scaffolding, pinned dependency versions, and deployment wiring land with Phase 0 and are marked ✅ as they become real.

---

## 1. System context

```mermaid
flowchart LR
    Browser(["Browser"])

    subgraph FE ["Frontend — Next.js + TypeScript (Vercel or Next.js-compatible host)"]
        UI["React UI · Tailwind · shadcn/ui<br/>React Flow workflow canvases"]
    end

    subgraph SB ["Supabase"]
        PG[("PostgreSQL<br/>versioned migrations · RLS")]
        AUTH["Auth (SSR sessions)"]
        ST["Storage<br/>private buckets"]
        RT["Realtime"]
        Q["Queues · pgmq"]
    end

    subgraph BE ["Backend — Scala 3 modular monolith (Dockerized JVM)"]
        API["API process<br/>http4s + Tapir"]
        WRK["Worker process<br/>scheduling · sending · sync · AI"]
    end

    EXT["External providers<br/>Google · Microsoft · SMTP/IMAP<br/>AI · Stripe · CRM · Data providers"]

    Browser -->|"Supabase SSR auth"| AUTH
    UI -->|"live updates"| RT
    API -->|"JWT via JWKS"| AUTH
    API --> PG
    WRK --> PG
    WRK --> Q
    WRK --> ST
    WRK -->|"OAuth/SMTP/IMAP/APIs"| EXT
    UI -.->|"file up/down via signed URLs"| ST
```

## 2. Components

### 2.1 Frontend — Next.js App Router

- **React 18+/19 + TypeScript**, server components by default, client components where interactivity demands.
- **Tailwind CSS + shadcn/ui (Radix primitives)** for accessible, themeable components. Design language per `AGENTS.md` §6: sophisticated minimalism, subtle spatial depth, restrained neumorphism, indigo/navy palette, light+dark themes.
- **React Flow** for campaign/automation workflow canvases.
- **Supabase Auth SSR** for session management; only public (`NEXT_PUBLIC_*` / anon) configuration reaches the browser.
- **Supabase Realtime** subscribes the browser directly to customer-facing live updates (e.g. inbox events) where suitable.
- Every feature ships loading, success, empty, validation, error, and permission-denied states.

### 2.2 Supabase (platform of record)

| Capability | Usage |
| --- | --- |
| PostgreSQL | System of record; all schema via version-controlled SQL migrations; RLS on every tenant table |
| Auth | User identity, SSR sessions; Scala verifies JWTs via Supabase JWKS |
| Storage | Private buckets for imports, attachments, exports; access only through signed URLs generated server-side |
| Realtime | Customer-facing live updates where appropriate (inbox, campaign progress) |
| Queues (pgmq) | Durable queues when a queue semantic beats a plain table/outbox; else PostgreSQL-backed job tables |

**Connection policy:** the Scala backend uses direct or session-pooled connections appropriate to a small number of long-lived JVM services (transaction pooling is evaluated per deployment; PgBouncer/Supavisor mode is recorded in `docs/DECISIONS.md` once chosen in Phase 0).

### 2.3 Backend — Scala 3 modular monolith

One deployable codebase, **two runnable processes**:

- **API process** — http4s HTTP server, Tapir-derived endpoints + OpenAPI docs, request validation, authorization, orchestration.
- **Worker process** — durable job execution: email scheduling/sending, mailbox sync, bounce/feedback processing, webhook ingestion, AI pipelines, CRM sync, maintenance.

Internal modules (boundaries are package-level, one DB schema, no network hops inside the monolith):

| Module | Responsibility (target) |
| --- | --- |
| `identity` | Users, sessions verification, profiles |
| `workspaces` | Tenants, members, roles/invitations, RBAC |
| `emailconnections` | Gmail/Microsoft OAuth, SMTP/IMAP accounts, credential lifecycle |
| `leads` | Leads, lists, custom fields, import/export, suppression |
| `campaigns` | Campaigns, sequences, steps, personalization, scheduling |
| `sending` | Send queue, provider submission, tracking, bounce/complaint handling |
| `inbox` | Conversation aggregation, two-way sync, reply threading |
| `crm` | Accounts/contacts/deals/pipelines, activity timeline |
| `deliverability` | Reputation signals, monitoring, guidance (no abuse-evasion features) |
| `analytics` | Event rollups, campaign/analytics reporting |
| `ai` | LLM-assisted copy, replies, scoring; provider-agnostic adapter |
| `billing` | Stripe subscriptions, plans, usage limits |
| `platform` | Shared kernel: config, logging, errors, auth middleware, job framework |

**Stack (pinned — ADR-0016):** Scala 3.3.8 (LTS), sbt 1.12.15, Cats Effect 3.7.1, http4s 0.23.38 (ember) — the stable line; Tapir 1.13.33's own POM targets it and the 1.0 series is still milestone-only — Tapir 1.13.33, Doobie 1.0.0-RC12, PostgreSQL JDBC 42.7.14, log4cats 2.8.0 + logback 1.6.5.

### 2.4 External providers

Google (Gmail API), Microsoft (Graph), arbitrary SMTP/IMAP, AI providers, Stripe, CRMs, and B2B data providers. All integrations sit behind module-level adapters so providers are replaceable; provider credentials are stored encrypted (see `docs/SECURITY.md`).

## 3. Multi-tenancy & authorization model

- Every tenant is a **workspace** (`workspace.id`). All domain rows carry `workspace_id`.
- **Defense in depth:**
  1. **RLS policies** in PostgreSQL scope every table to the caller's workspace (via Supabase JWT claims).
  2. **Scala-side authorization** independently resolves the caller → user → membership → role for the workspace addressed by the request. The service-role key (which bypasses RLS) is used only in tightly scoped server jobs and never replaces tenant checks.
- **RBAC:** workspace roles (owner / admin / member — refined per phase) checked in Scala middleware and re-checked at the query layer.
- **Cross-tenant isolation tests** are mandatory for every new resource.

## 4. Data flow patterns

- **Request path:** Browser → Next.js (SSR auth session) → Scala API (JWT verified via JWKS, tenant check) → PostgreSQL (RLS as backstop).
- **Command/side-effect path (email, sync, AI):** API writes an intent row + enqueues a durable job (outbox/job table or pgmq) → worker claims with `FOR UPDATE SKIP LOCKED`, executes with idempotency keys → outcomes recorded; retries with backoff; unknown outcomes (e.g. SMTP timeout) are recorded as unknown, never assumed delivered or failed.
- **Live path:** worker/API writes → Supabase Realtime → browser subscription.
- **Files:** browser ↔ Supabase Storage via short-lived signed URLs minted by the API after authorization.

## 5. Deployment topology (target)

- **Scala API + Worker:** separate Docker images from one build; deployed to a VPS or container host; horizontal scaling of workers first.
- **Frontend:** Vercel or another Next.js-compatible host.
- **Database:** Supabase (hosted), with staged migrations applied via CI or Supabase CLI.
- **Secrets:** environment-injected per environment; never in the repository (see `docs/SECURITY.md`).

## 6. Observability & operations

- Structured JSON logging with request/job IDs and workspace correlation IDs (no PII or credentials in logs).
- Health/readiness endpoints on both processes (Phase 0).
- Metrics hooks (JVM + domain counters) and alerting hooks planned with the deployment phase.
- Audit trail tables for security-relevant actions (membership changes, credential changes, exports).

## 7. Testing strategy

| Layer | Tooling |
| --- | --- |
| Scala domain | ScalaTest / MUnit |
| Scala HTTP/DB integration | http4s test harness + Testcontainers/integration DB |
| Frontend units | Vitest |
| E2E / UX | Playwright (run whenever UI behavior changes) |
| Cross-tenant security | Dedicated isolation suites per resource |

## 8. What is real today

- ✅ Repository, documentation set, branch model, conventions.
- ✅ Monorepo scaffold (`apps/web`, `apps/api`, `apps/worker`, `modules/core`, `supabase/migrations/`, `infrastructure/`, `scripts/`) — ADR-0015.
- ✅ Scala API process: `GET /health`, `GET /ready` (Tapir → http4s ember), validated env config, logging — 14/14 Scala tests pass.
- ✅ Scala worker process: heartbeat event loop with validated env config.
- ✅ Next.js app: system-health page probing the API with honest loading/up/degraded/unreachable states — 6/6 Vitest tests, clean lint, production build.
- ⬜ Architecture & database design (ERD, state machines) — Phase 0.2.
- ⬜ Supabase configuration, migrations, RLS, Doobie connectivity — Phase 0.3.
- ⬜ CI/CD, structured request logging, Docker — Phase 0.4.
