# Arcadence — Local Setup

> Status: **Scaffolding pending.** No application code exists yet, so there is nothing to run locally until Phase 0 scaffolding lands. This file documents the target toolchain and conventions now, and gains exact commands as they become real. Anything below marked ⬜ is not yet installed/created.

**Last updated:** 2026-10-09

---

## 1. Prerequisites

| Tool | Version target | Status on the reference machine |
| --- | --- | --- |
| JDK | 21 LTS (Temurin) | ✅ 21.0.12.1 installed via winget |
| sbt | 1.x (project pins 1.12.15) | ✅ installed via winget (launcher; project build.properties drives the exact version) |
| Node.js | 22+ (runtime is 24.x) | ✅ v24.14.0 |
| npm | 11.x | ✅ 11.9.0 |
| Docker Desktop | current | ⬜ needed from Phase 0.3 (local Supabase) |
| Supabase CLI | current | ⬜ needed from Phase 0.3 |
| Playwright browsers | via `npx playwright install` | ⬜ needed when E2E lands |

Run `scripts/check-toolchain.sh` any time to verify. **Note:** winget installs update the PATH in the registry — open a new terminal (or rely on the scripts) after first install.

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

## 3. Local development (verified commands)

```bash
scripts/check-toolchain.sh     # verify JDK/sbt/node are present

# Scala (root of repo)
sbt test                       # all Scala unit tests (14 as of 0.1)
sbt api/run                    # API process on :8080 (GET /health, GET /ready)
sbt worker/run                 # worker process (heartbeat log loop)
sbt scalafmtAll                # format Scala code

# Frontend (apps/web)
cd apps/web
npm install                    # after fresh checkout
npm run dev                    # dev server on :3000 (health page probes the API)
npm test                       # Vitest units (6 as of 0.1)
npm run lint                   # ESLint 9 incl. React 19 rules
npm run build && npm start     # production build + serve on :3000

# Orchestration
scripts/dev-api.sh             # sbt api/run
scripts/dev-worker.sh          # sbt worker/run
scripts/dev-web.sh             # npm run dev
scripts/test-all.sh            # sbt test + npm test
scripts/fmt-all.sh             # scalafmtAll + prettier
```

Frontend behavior: with the API down, the health page shows an explicit "API unreachable" state — this is intentional proof of honest error states, not a bug.

Environment: copy the per-app `.env.example` to `.env.local` in the same directory. `API_PORT`, `LOG_LEVEL`, `WORKER_TICK_INTERVAL_MS` are live today; Supabase variables activate in Phase 0.3.

## 4. Verification before pushing (per `AGENTS.md`)

- ⬜ `sbt scalafmtCheck test` (Scala) — from Phase 0
- ⬜ type-check + unit tests (frontend) — from Phase 0
- ⬜ integration tests — from Phase 1
- ⬜ Playwright — whenever UI behavior changes
- clean `git status`; conventional commit; push to the feature branch

## 5. Troubleshooting

Added as real issues are encountered and solved. Nothing yet.
