# Arcadence — Local Setup

> Status: **Scaffolding pending.** No application code exists yet, so there is nothing to run locally until Phase 0 scaffolding lands. This file documents the target toolchain and conventions now, and gains exact commands as they become real. Anything below marked ⬜ is not yet installed/created.

**Last updated:** 2026-10-09

---

## 1. Prerequisites

| Tool | Version target | Purpose |
| --- | --- | --- |
| JDK | 21 LTS (Temurin recommended) | Scala backend builds & runtime |
| sbt | 1.x (current stable) | Scala build tool |
| Node.js | 22 LTS | Next.js frontend & tooling |
| pnpm or npm | current | Frontend package management (decided at scaffold) |
| Docker Desktop | current | Local PostgreSQL via Supabase CLI, Testcontainers |
| Supabase CLI | current | Local Supabase stack + migrations |
| Git | 2.40+ | Version control |
| Playwright browsers | via `npx playwright install` | E2E tests (once UI exists) |

## 2. Environment variables

Templates will be provided as `.env.example` files per component at scaffold time. **Never commit real values** (`.env*` is git-ignored).

Planned shape:

| Variable | Scope | Secret? | Notes |
| --- | --- | --- | --- |
| `NEXT_PUBLIC_SUPABASE_URL` | frontend | no | Public project URL |
| `NEXT_PUBLIC_SUPABASE_ANON_KEY` | frontend | no | Anon key only — safe for browser |
| `DATABASE_URL` / DB pool config | backend | **yes** | Postgres connection for Doobie |
| `SUPABASE_JWKS_URL` | backend | no | JWT verification (ADR-0003) |
| `SUPABASE_JWT_ISSUER` / `SUPABASE_JWT_AUDIENCE` | backend | no | Claim validation |
| `SUPABASE_SERVICE_ROLE_KEY` | backend (worker/admin jobs only) | **yes** | Bypasses RLS — tightly scoped; see `docs/SECURITY.md` §1 |
| `STORAGE_SIGNED_URL_*`, provider credentials, `STRIPE_*`, AI keys | backend, per phase | **yes** | Introduced with their phases |

## 3. Local development (target flow)

```bash
# ⬜ available after Phase 0 scaffolding
supabase start                 # local Postgres/Auth/Storage/Realtime
supabase db reset              # apply migrations from supabase/migrations/
sbt "api/run"                  # Scala API process
sbt "worker/run"               # Scala worker process
pnpm dev                       # Next.js frontend
npx playwright test            # E2E suite
```

Exact scripts, ports, and seeds are documented here when the scaffold exists.

## 4. Verification before pushing (per `AGENTS.md`)

- ⬜ `sbt scalafmtCheck test` (Scala) — from Phase 0
- ⬜ type-check + unit tests (frontend) — from Phase 0
- ⬜ integration tests — from Phase 1
- ⬜ Playwright — whenever UI behavior changes
- clean `git status`; conventional commit; push to the feature branch

## 5. Troubleshooting

Added as real issues are encountered and solved. Nothing yet.
