# Arcadence — Local Setup

> Status: **Phase 0 complete.** Everything below is verified on the reference machine (Windows 11, Git Bash). Commands are reproducible via `scripts/`.

**Last updated:** 2026-10-10

---

## 1. Prerequisites

| Tool | Version target | Purpose |
| --- | --- | --- |
| JDK | 21 LTS (Temurin) | Scala backend builds & runtime |
| sbt | 1.x (project pins 1.12.15) | Scala build tool |
| Node.js | 22+ (24 works) | Next.js frontend & tooling |
| npm | 11.x | Frontend package management |
| Docker Desktop | current (engine running) | Local Supabase stack, service images |
| Supabase CLI | 2.x | Local Supabase stack + migrations |
| Playwright browsers | via `npx playwright install` | E2E tests (once UI exists) |

Verify any machine with `scripts/check-toolchain.sh`. Windows notes: winget installs update the PATH in the registry — open a new terminal afterwards; the `scripts/*.sh` helpers resolve `docker`/`supabase` from known fallback locations, so they work even before that.

## 2. Environment variables

Copy each app's `.env.example` to `.env.local` in the same directory — dev scripts source these automatically. **Never commit real values** (`.env*` except `.env.example` is git-ignored).

| Variable | Scope | Secret? | Notes |
| --- | --- | --- | --- |
| `API_PORT`, `LOG_LEVEL`, `WORKER_TICK_INTERVAL_MS` | backend | no | Live today |
| `DATABASE_URL` | backend | **yes** | Postgres for Doobie. Local stack default: `postgresql://postgres:postgres@127.0.0.1:54322/postgres` |
| `TEST_DATABASE_URL` | backend tests | **yes** | Activates `DbConnectivitySpec` (skipped when unset) |
| `TEST_DB_EXPECT_SCHEMA=false` | backend tests | no | Connectivity-only runs against a DB without migrations yet |
| `NEXT_PUBLIC_API_URL` | frontend | no | Scala API base URL |
| `NEXT_PUBLIC_SUPABASE_URL` / `NEXT_PUBLIC_SUPABASE_ANON_KEY` | frontend | no (public by design) | Publishable key only |
| `SUPABASE_JWT_ISSUER` / `SUPABASE_JWKS_URL` / `SUPABASE_PROJECT_REF` | backend | no | Activate in Phase 1 (JWT verification) |

## 3. Local development flow (verified)

```bash
scripts/check-toolchain.sh     # everything present? docker engine running?

supabase start                 # local Supabase stack (first run pulls ~10 images)
scripts/db-reset.sh            # drop + recreate local DB, apply all migrations
supabase status                # local URLs & keys (db on :54322, api :54321, studio :54323)

scripts/dev-api.sh             # API on :8080 (env from apps/api/.env.local)
scripts/dev-worker.sh          # worker heartbeat loop
scripts/dev-web.sh             # web on :3000

# Tests
scripts/test-all.sh            # sbt test + npm test
TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:54322/postgres sbt core/test
                               # DB connectivity + RLS verification (needs local stack)
cd apps/web && npm run test:e2e
                               # Playwright auth lifecycle (needs local stack; starts the dev
                               # server itself; confirmation emails are read via Mailpit :54324)

# Formatting
scripts/fmt-all.sh             # scalafmtAll + prettier

# Docker services (images built locally)
sbt api/docker:publishLocal worker/docker:publishLocal
cd infrastructure && docker compose up -d
curl http://localhost:8080/health
docker compose down
```

Frontend behavior: with the API down, the health page shows an explicit "API unreachable" state — intentional honest error states, not a bug.

## 4. CI (GitHub Actions)

`.github/workflows/ci.yml` runs on every push/PR:
- **scala** — `sbt scalafmtCheckAll test` (DB specs self-skip without `TEST_DATABASE_URL`)
- **web** — `npm ci` → lint → prettier check → Vitest → production build
- **migrations** — applies all migrations to a clean `postgres:17` service container and asserts RLS + helper functions exist (proves migrations are portable, not Supabase-console-dependent)

No repository secrets are required yet. When CI later pushes migrations to staging (Phase 1+), add `SUPABASE_ACCESS_TOKEN` as a GitHub secret — never in the repo.

## 5. Smoke test guide (fresh checkout → running system)

1. `scripts/check-toolchain.sh` — all OK.
2. `cd apps/web && npm install` (once) — then back to root.
3. `supabase start` → wait for "Started supabase local development setup."
4. `scripts/db-reset.sh` — expect migration file names to print, ending "Local database reset complete."
5. `TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:54322/postgres sbt core/test` — all green including RLS checks.
6. `scripts/dev-api.sh` → `curl localhost:8080/health` → `{"service":"arcadence-api",...,"status":"up"}`.
7. `scripts/dev-web.sh` → http://localhost:3000 shows "API unreachable" state until step 6, then the health page goes green.
8. **Auth lifecycle:** http://localhost:3000/signup → register → open **Mailpit** (http://localhost:54324, the local mail catcher — Supabase replaced Inbucket with Mailpit) → click the confirmation link → onboarding → dashboard. Or run `cd apps/web && npm run test:e2e` for the automated suite.
9. Optional: `docker compose -f infrastructure/docker-compose.yml up -d` after building images; repeat step 6 on port **18080** (compose mapping; 8080 is taken by a Windows service on some machines).

## 6. Troubleshooting

| Symptom | Cause & fix |
| --- | --- |
| `sbt` first command hangs for minutes | First run downloads sbt + the dependency graph; subsequent runs are fast. Don't kill mid-resolution. |
| `supabase start` fails: "docker: command not found" | Docker CLI not on the shell's PATH — restart the terminal after installing Docker Desktop, or run `export PATH="/c/Program Files/Docker/Docker/resources/bin:$PATH"` first. |
| `supabase start` fails: cannot connect to Docker | Docker Desktop engine not running — start it and wait ~30s (`docker info` succeeds when ready). |
| First `supabase start` very slow | It pulls ~10 container images once; subsequent starts are seconds. |
| `db reset` shows migration error | The migration set is forward-only; check the failing statement against `docs/DATABASE.md`, fix in a NEW migration, never edit applied ones. |
| Health page shows "API unreachable" | API process not running (or CORS blocked). Start `scripts/dev-api.sh`; CORS is open-without-credentials for local dev. |
| `Cannot use automatic login flow inside non-TTY environments` | `supabase login` needs an interactive shell — run it in your own terminal once; the token is stored locally. |
| Auth e2e tests fail: "No email addressed to … arrived in Mailpit" | The local stack is running with stale auth config — run `supabase stop && supabase start` so GoTrue recreates with `enable_confirmations` from `config.toml` (Docker Desktop restarts can silently revive old containers). |
| RLS policies match zero rows despite correct data | PostgREST 13 removed `request.jwt.claim.*` GUCs; the helpers coalesce the new sources (migration `20261012090000`). Ensure it is applied. |
| Port 8080/3000/54322 already in use | Another process (often a previous sbt run, or `AgentService` on 8080) holds it — stop it or change `API_PORT`/dev port. Compose maps the API to 18080 for this reason. |
| Git warns "LF will be replaced by CRLF" | Normal on Windows (`core.autocrlf`); files are stored with LF in the repository. |

## 7. Version-control rules (from `AGENTS.md` §4 — short form)

- `main` = protected release branch; `develop` = integration branch; work lands via `feature/phase-NN-slug` branches and PRs.
- Conventional commits; no force-push, no history rewrites, no secrets ever.
- Every subphase: tests pass → push → implementation report → stop.
- At phase completion, `develop` is merged to `main` and tagged (e.g. `phase-0`).
