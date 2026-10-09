# Arcadence

**AI-powered outreach & sales engagement platform** — a production-oriented, multi-tenant SaaS for cold-email outreach, B2B lead management, campaign automation, unified inbox, CRM, deliverability intelligence, analytics, and AI-assisted sales workflows.

> **Status:** Phase 0 foundation in progress — monorepo scaffolded, both applications build/run/test (see [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md)).

---

## Architecture at a glance

```mermaid
flowchart LR
    Browser(["Browser"])

    subgraph FE ["Frontend — Next.js + TypeScript"]
        UI["React UI · Tailwind · shadcn/ui<br/>React Flow canvases"]
    end

    subgraph SB ["Supabase"]
        PG[("PostgreSQL<br/>RLS + migrations")]
        AUTH["Auth"]
        ST["Storage<br/>(private buckets)"]
        RT["Realtime"]
        Q["Queues · pgmq"]
    end

    subgraph BE ["Backend — Scala 3 (Dockerized JVM)"]
        API["http4s + Tapir API"]
        WRK["Scheduling · Workers<br/>Email · CRM · AI"]
    end

    EXT["External providers<br/>Google · Microsoft · SMTP/IMAP<br/>AI · Stripe · CRM · Data providers"]

    Browser --> UI
    UI --> AUTH
    UI --> RT
    UI -- "REST (JWT)" --> API
    API --> PG
    API --> AUTH
    WRK --> PG
    WRK --> Q
    WRK --> ST
    WRK --> EXT
```

**Key principles**

- **Multi-tenancy is enforced twice:** Row-Level Security in Supabase *and* independent workspace authorization in the Scala API. The service-role key never reaches the browser.
- **Durable by default:** background work runs on PostgreSQL-backed queues/outbox with idempotent processing; email submission treats unknown outcomes as unknown (at-least-once, never assumed exactly-once).
- **Compliance first:** consent, suppression, and opt-out are first-class features. No mechanisms intended to evade email-provider abuse controls.

## Technology stack

| Layer | Technology |
| --- | --- |
| Backend | Scala 3 · sbt · Cats Effect · http4s · Tapir · Doobie |
| Frontend | Next.js App Router · React · TypeScript · Tailwind CSS · shadcn/ui · React Flow |
| Database & platform | Supabase (PostgreSQL, Auth SSR, Storage, Realtime, pgmq queues) |
| Payments | Stripe |
| Testing | ScalaTest/MUnit · Vitest · Playwright · integration tests |
| Deployment | Dockerized JVM services on VPS/container host · Vercel for the frontend |

## Repository layout

```
├── AGENTS.md            # Binding instructions for coding agents — read first
├── build.sbt            # Scala build spanning modules/ and apps/ (Scala 3.3.8, sbt 1.12.15)
├── apps/
│   ├── web/             # Next.js 16 · TypeScript · Tailwind v4 · Vitest
│   ├── api/             # Scala API process — http4s + Tapir (GET /health, /ready)
│   └── worker/          # Scala worker process — heartbeat/job execution
├── modules/
│   └── core/            # Shared Scala library (config, health contracts)
├── packages/            # Shared frontend packages (intentionally empty — ADR-0015)
├── supabase/migrations/ # Version-controlled SQL migrations (from Phase 0.2/0.3)
├── infrastructure/      # Docker / CI / deployment (from Phase 0.4)
├── scripts/             # check-toolchain · dev-api · dev-worker · dev-web · test-all · fmt-all
├── docs/                # Permanent project documentation
│   ├── PROJECT_STATUS.md    # Phase/subphase status (single source of truth)
│   ├── ARCHITECTURE.md      # System design
│   ├── DECISIONS.md         # Architecture decision records (ADR-0001…0016)
│   ├── DATABASE.md          # Schema overview & migration conventions
│   ├── API.md               # API conventions & endpoint docs
│   ├── SETUP.md             # Local development setup (verified commands)
│   ├── MANUAL_SETUP.md      # External account tasks for the product owner
│   ├── HANDOFF.md           # Implementation/handoff log
│   └── SECURITY.md          # Security posture & decisions
└── .github/             # PR/issue templates, CODEOWNERS
```

## Development quickstart

```bash
scripts/check-toolchain.sh    # JDK 21 + sbt + Node 22+ present?
sbt test                      # Scala unit tests
sbt api/run                   # API on :8080 → GET /health, GET /ready
sbt worker/run                # worker heartbeat loop
cd apps/web && npm install
npm run dev                   # http://localhost:3000 — live system-health page
```

Full commands and prerequisites: [docs/SETUP.md](docs/SETUP.md).

## Branch model

- `main` — protected release branch; promoted from `develop` per completed phase.
- `develop` — integration branch; all feature work lands here via PR.
- `feature/phase-NN-slug` — one branch per subphase, conventional commits.

## Contributing / agent workflow

All work follows [AGENTS.md](AGENTS.md): one prompt → one completed subphase → tests → push → implementation report. See [CONTRIBUTING.md](CONTRIBUTING.md) for the short version.

## License

Proprietary — © 2026 Arcadence. All rights reserved. Not licensed for redistribution.
