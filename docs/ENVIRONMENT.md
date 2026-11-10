# Environment Variable Inventory

**Source of truth:** every variable below is read from **actual source code** (grep-verified 2026-11-10, post-Phase 15.1). No invented variables. When a variable is added to or removed from source, update this file in the same subphase.

**Classification:**
- **PUBLIC** — safe to expose to the browser (`NEXT_PUBLIC_*` / anon key); shipped in the client bundle.
- **SERVER-ONLY** — read by the Scala API / worker / Next.js server; not secret, but not for the browser.
- **SENSITIVE** — a credential; lives only in a secret manager / host env; never committed, never browser-visible.
- **OPTIONAL** — the service starts and degrades honestly without it (documented behavior).

---

## Scala API (`apps/api`)

| Variable | Required | Default | Class | Purpose (source site) |
|---|---|---|---|---|
| `DATABASE_URL` | **yes** | — | SENSITIVE | Postgres/Supabase JDBC URL (`core/config/ApiConfig.scala` — fail-fast) |
| `SUPABASE_JWT_ISSUER` | **yes** | — | SERVER-ONLY | JWT issuer check (`JwtAuth` claim validation) |
| `SUPABASE_JWT_AUDIENCE` | **yes** | — | SERVER-ONLY | JWT audience check |
| `SUPABASE_JWKS_URL` | **yes** | — | SERVER-ONLY | JWKS endpoint for Supabase signature verification |
| `EMAIL_ENCRYPTION_KEY` | **yes** | — | **SENSITIVE** | Base64 32-byte AES-256-GCM key; encrypts OAuth/SMTP credentials + signs OAuth state. Startup aborts when missing/invalid |
| `APP_ORIGIN` | no | `http://localhost:3000` | SERVER-ONLY | Browser-facing frontend origin used for workspace invite links and OAuth callback redirects (15.2). Must be set on any deployed environment |
| `API_PORT` | no | `8080` | SERVER-ONLY | HTTP listen port (validated 1–65535) |
| `LOG_LEVEL` | no | `INFO` | SERVER-ONLY | Root log level |
| `RATE_LIMIT_PER_MINUTE` | no | `120` | SERVER-ONLY | Per-caller sliding-window limit |
| `GOOGLE_OAUTH_CLIENT_ID` | no | — | SERVER-ONLY | Gmail connect; without it the flow reports `not configured` honestly |
| `GOOGLE_OAUTH_CLIENT_SECRET` | no | — | **SENSITIVE** | Gmail token exchange |
| `GOOGLE_OAUTH_REDIRECT_URI` | no | derived from `API_PORT` | SERVER-ONLY | Registered OAuth callback override |
| `MSFT_OAUTH_CLIENT_ID` | no | — | SERVER-ONLY | Microsoft Graph connect |
| `MSFT_OAUTH_CLIENT_SECRET` | no | — | **SENSITIVE** | Microsoft token exchange |
| `MSFT_OAUTH_REDIRECT_URI` | no | derived (⚠ replaces `/google/` in the Google URI — always set explicitly when `GOOGLE_OAUTH_REDIRECT_URI` is custom) | SERVER-ONLY | Microsoft callback override |
| `MSFT_OAUTH_TENANT` | no | — | SERVER-ONLY | Azure tenant (`common` for multi-tenant) |
| `IMPORT_SPOOL_DIR` | no | `ImportService.defaultSpoolRoot` | SERVER-ONLY | CSV upload spool directory |
| `STRIPE_SECRET_KEY` | no (dormant) | — | **SENSITIVE** | Absent ⇒ checkout returns 503 `billing_not_configured` (fail-closed). Deferred by product decision |
| `STRIPE_WEBHOOK_SECRET` | no (dormant) | — | **SENSITIVE** | Absent ⇒ webhook returns 503 (fail-closed) |
| `AI_API_KEY` | no | — | **SENSITIVE** | External AI provider key (GLM). Absent ⇒ AI endpoints return honest 503 `ai_not_configured` |
| `AI_BASE_URL` | no | `https://api.openai.com/v1` | SERVER-ONLY | OpenAI-compatible endpoint (GLM: `https://api.z.ai/api/coding/paas/v4`) |
| `AI_MODEL` | no | `gpt-4o-mini` | SERVER-ONLY | Model id (GLM: `glm-4.5-flash` / `glm-5.3-flash` when funded) |
| `AI_TIMEOUT_MS` | no | `30000` | SERVER-ONLY | AI call timeout (⚠ garbage value crashes startup — parsed outside `ApiConfig`) |
| `AI_MAX_RETRIES` | no | `2` | SERVER-ONLY | Bounded AI retry count (⚠ same parsing caveat) |
| `PLACEMENT_PROVIDER_KEY` | no | — | **SENSITIVE** | Deliverability placement-test provider key; without it placement tests are unavailable |

## Worker (`apps/worker`)

| Variable | Required | Default | Class | Purpose |
|---|---|---|---|---|
| `DATABASE_URL` | **yes** | — | SENSITIVE | Same Postgres as the API (parsed separately in `worker/Main.scala`) |
| `LOG_LEVEL` | no | `INFO` | SERVER-ONLY | Root log level |
| `WORKER_TICK_INTERVAL_MS` | no | `30000` | SERVER-ONLY | Heartbeat cadence (`core/config/WorkerConfig.scala`, validated) |
| `WORKER_ID` | no | `worker-<random8>` | SERVER-ONLY | Lease owner identity (`SendWorker.Config`) |
| `WORKER_POLL_SECONDS` | no | `5` | SERVER-ONLY | Queue poll interval (⚠ invalid values silently fall back — Phase 15.1 audit finding) |
| `WORKER_BATCH_SIZE` | no | `10` | SERVER-ONLY | Jobs claimed per poll (⚠ same silent-fallback caveat) |
| `WORKER_LEASE_SECONDS` | no | `300` | SERVER-ONLY | Claim lease duration (⚠ same silent-fallback caveat) |

**Staging sending safety (15.3 plan):** the worker will also honor a send-enable switch and reduced quotas introduced as part of the staging hardening subphase — listed here only after they exist in source.

## Frontend (`apps/web`) — browser-visible

| Variable | Required | Default | Class | Purpose |
|---|---|---|---|---|
| `NEXT_PUBLIC_SUPABASE_URL` | **yes** (deployed) | `http://127.0.0.1:44321` local | **PUBLIC** | Supabase project URL (`lib/supabase/client.ts`, `server.ts`, `middleware.ts`) |
| `NEXT_PUBLIC_SUPABASE_ANON_KEY` | **yes** (deployed) | local dev key | **PUBLIC** | Publishable anon key only — the service-role key must never appear here |
| `NEXT_PUBLIC_API_URL` | **yes** (deployed) | `http://localhost:8080` | **PUBLIC** | Scala API base (`lib/config.ts`) |
| `NEXT_PUBLIC_GOOGLE_OAUTH_ENABLED` | no | — | **PUBLIC** | Capability flag surfacing Gmail connect in the UI |

No other `NEXT_PUBLIC_*` variables exist in `apps/web/src`. `.env.local` files are git-ignored; only `.env.example` templates are committed.

---

## Secret handling rules (binding)

1. Secrets live in environment variables / secret managers only — never in code, never in committed files, never in `NEXT_PUBLIC_*`.
2. The Supabase **service-role key is not used by any Arcadence component**; do not introduce it. Tenant checks are enforced in the Scala API independent of RLS.
3. Rotation: `EMAIL_ENCRYPTION_KEY` rotation requires re-encrypting stored credentials — treat it as infrastructure, not a toggled secret.
