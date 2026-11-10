# Staging Deployment Specification — Phase 15.2

**Goal:** put Arcadence online for **private testing** — Vercel for the Next.js frontend, Docker-capable hosting for the Scala API + worker — preserving the documented migration path to a Hostinger VPS (Phase 15.6). **Not a public launch.** Stripe stays dormant.

---

## 1. Architecture evaluation

### Option A — Next.js on Vercel + Scala API/worker on a Docker host *(RECOMMENDED)*

Frontend deploys natively on Vercel (the platform's core product: zero-config Next.js 16, preview deployments on every push, deployment protection). The Scala API and worker run as the existing sbt-native-packager Docker images (`arcadence/arcadence-api`, `arcadence/arcadence-worker`, non-root uid 1001, `eclipse-temurin:21-jre-noble`) behind Docker Compose on any Docker-capable VPS.

- ✅ Long-running JVM processes with persistent DB connections are first-class — the worker is a polling loop (`FOR UPDATE SKIP LOCKED` claim every 5s); a VPS just runs it.
- ✅ Staging and the future Hostinger production use **the same Docker Compose topology** — the Phase 15.6 migration becomes a host change, not an architecture change.
- ✅ No beta risk, no quota stoppage, full control of JVM memory limits.
- ⚠️ Owner operates a small VPS (updates, TLS via Caddy — 15.6 provides the compose spec).

### Option B — All-on-Vercel: Services (API) + Daemons (worker)

Verified against Vercel's documentation as of 2026-11-10 (public beta per `vercel.com/docs/daemons`):

| Fact (verified) | Value |
|---|---|
| Status | **Public Beta**, all plans |
| Environments | **Production deployments only** — no preview, no `vercel dev` |
| Instance sizes | 1/2/4/8 GiB memory, 1–4 vCPU, linux/amd64 |
| Hobby quota | **5 Active CPU-hours + 420 GB-hours Provisioned Memory/month** — one 2 GiB instance running 24/7 exhausts it in ~9 days; **when exceeded, daemons stop until the next month** |
| Pro | 16 vCPU / 32 GiB concurrent; usage-billed (Active CPU + Provisioned Memory) |
| Scaling | Manual instance counts only; no autoscaling in beta |
| Storage | Ephemeral only |
| Liveness | Optional HTTP GET probe — **our worker serves no HTTP; a health endpoint is a code change** |
| Shutdown | SIGTERM + 30s default grace (up to 3600s) |
| Inbound traffic | Daemons cannot receive HTTP/TCP; Services would host the API |

**Verdict on B:** technically possible (containers are JVM-agnostic), but wrong for us today: a 24/7 JVM worker burns the Hobby Provisioned Memory quota in ~9 days and then **silently stops sending until the month rolls over**; the beta is production-only (no preview isolation for private testing); manual instance management without autoscaling; and Pro adds recurring cost during a free-first launch. It also creates a second operational topology that the Hostinger migration would abandon.

### Decision

**Option A.** Simplest reliable option for private testing; identical to the target VPS topology; Vercel stays exactly where it excels. The Scala worker is not rewritten; frontend, API, and worker keep separate runtimes and separate deploy cadences.

---

## 2. Staging topology

```
Browser ──TLS──▶ Vercel (Next.js, apps/web)          https://<app>.vercel.app
                     │  fetch + Bearer JWT
                     ▼
                VPS (Docker Compose)
                ├─ caddy        :443 → api:8080 (TLS termination, HSTS)
                ├─ api          arcadence-api  :8080
                └─ worker       arcadence-worker (no inbound port)
                     │  JDBC (pooled via Supabase pooler, see §5)
                     ▼
                Supabase staging project (managed Postgres + Auth + Storage)
```

VPS candidate: the planned **Hostinger VPS** (1–2 vCPU / 4–8 GiB) so staging→production is a host migration only. Any Docker-capable VPS is equivalent for this spec.

## 3. Component responsibilities (unchanged)

| Runtime | Responsibility | Deploys via |
|---|---|---|
| Next.js (Vercel) | UI, Supabase SSR auth sessions (`@supabase/ssr` cookie sessions, edge refresh), calls API with `Authorization: Bearer <access_token>` | Vercel Git integration |
| Scala API (Docker) | All business logic, authorization, sending orchestration, webhooks | `sbt api/docker:publishLocal` → registry → compose pull |
| Scala worker (Docker) | Queue claims (`send_email`), lease recovery | `sbt worker/docker:publishLocal` → registry → compose pull |

No JavaScript reimplementation of Scala functionality; no shared process boundaries.

## 4. Frontend origin and routing (Vercel)

> **Phase 15.4:** the click-by-click deployment runbook now lives in **`docs/VERCEL_DEPLOYMENT.md`** (project settings, env vars, deployment protection, Supabase Auth URL wiring, post-deploy smoke checklist, URL record). The summary below remains valid.

- Project root: `apps/web` (monorepo; Next.js framework auto-detected; install `npm ci`, build `npm run build`).
- Routes: everything is served by Next.js; no rewrites needed (API is called cross-origin by the client via `NEXT_PUBLIC_API_URL`).
- **Deployment Protection:** enable Vercel Authentication (or password) so the staging UI is private — the 15.5 browser tests run behind it.
- Secrets in Vercel project env vars (see `docs/ENVIRONMENT.md` frontend section): `NEXT_PUBLIC_SUPABASE_URL`, `NEXT_PUBLIC_SUPABASE_ANON_KEY`, `NEXT_PUBLIC_API_URL`, optional `NEXT_PUBLIC_GOOGLE_OAUTH_ENABLED`. All PUBLIC-classified; no server-only secrets in the frontend.

## 5. Scala API base URL, DB connections, pooling

- Public API origin: `https://api.<staging-domain>` (Caddy TLS → api:8080). `NEXT_PUBLIC_API_URL` points here.
- `DATABASE_URL` (API + worker): use the **Supabase connection pooler** (port 6543, transaction mode) rather than direct 5432 — the API/worker open a JDBC connection per transaction (deliberate ADR-0018, no Hikari); the pooler caps Postgres backends regardless. Session mode (5432 via pooler) if any prepared-statement incompatibility appears; verify during 15.3 smoke tests.
- `APP_ORIGIN=https://<app>.vercel.app` — **required in staging** (15.2 code change: invite links + OAuth redirects previously hardcoded `localhost:3000`).
- Rate limit: keep `RATE_LIMIT_PER_MINUTE=120` initially.

## 6. TLS and CORS

- TLS terminates on **Caddy on the VPS** (automatic Let's Encrypt; HSTS on). Vercel terminates TLS for the frontend automatically.
- **CORS:** the API currently reflects any origin (`withAllowOriginAll`, credentials disabled, bearer-only). Acceptable for private testing; hardening (origin allowlist from `APP_ORIGIN`) is a follow-up before public launch — tracked in `docs/audit/15.1-comprehensive-repo-audit.md` §4.

## 7. Authentication callbacks

- Supabase Auth (staging project): email OTP/magic-link flows confirm via `https://<app>.vercel.app/auth/confirm` (PKCE `exchangeCodeForSession` + `token_hash` verify — existing routes).
- **Supabase Auth URL configuration:** Site URL = frontend origin; additional redirect URLs = frontend origin (+ `https://api.<staging-domain>` if email links ever land there — they should not).

## 8. OAuth redirect URLs (mailbox connections)

- Google Cloud Console OAuth client (staging): authorized redirect URI `https://api.<staging-domain>/api/v1/email-connections/oauth/google/callback` → set `GOOGLE_OAUTH_REDIRECT_URI`.
- Microsoft Azure app (staging): redirect URI `https://api.<staging-domain>/api/v1/email-connections/oauth/microsoft/callback` → set `MSFT_OAUTH_REDIRECT_URI` (do not rely on the `/google/`→`/microsoft/` string-replace default with a custom Google URI).
- Both consoles must also accept the production origins later (15.6 migration item).

## 9. Environment secrets

Full inventory: `docs/ENVIRONMENT.md`. Staging provisioning:

| Where | Secret | Class |
|---|---|---|
| Vercel project env | 4 `NEXT_PUBLIC_*` vars | PUBLIC |
| VPS compose env (api) | `DATABASE_URL`, `SUPABASE_JWT_*` (3), `EMAIL_ENCRYPTION_KEY`, `APP_ORIGIN`, OAuth client ids/secrets (optional), `AI_API_KEY`/`AI_BASE_URL`/`AI_MODEL` (optional) | SENSITIVE/SERVER-ONLY |
| VPS compose env (worker) | `DATABASE_URL`, `LOG_LEVEL`, `WORKER_*` tuning | SENSITIVE/SERVER-ONLY |
| **Not provisioned** | `STRIPE_*` (deferred by product decision), `PLACEMENT_PROVIDER_KEY` (until placement tests are wanted) | — |

`EMAIL_ENCRYPTION_KEY` for staging: generate fresh (`openssl rand -base64 32`); it is staging-scoped and independent from any future production key.

## 10. Worker configuration (private-testing safety)

- `WORKER_POLL_SECONDS=5`, `WORKER_BATCH_SIZE=5`, `WORKER_LEASE_SECONDS=300` (conservative batch for one small instance).
- Sending safety switch + test-only recipient enforcement are **15.3 work** (staging config hardening); until then, staging sends go only through the manual composer test-send (recipient = the signed-in user) because campaign scheduling is inert in production wiring (audit C1 — deliberate until remediation wave 1).
- Idempotency, suppression guards, and dead-lettering are already enforced in the send path (verified Phase 15.1).

## 11. Health checks

- API: `GET /health` (process liveness) and `GET /ready` (DB check) — wired and public by design; Caddy/monitor probes `/health`, uptime checks probe `/ready`.
- Worker: logs a heartbeat every `WORKER_TICK_INTERVAL_MS` (30s); no HTTP endpoint — health = "heartbeat in logs within the last tick × 2" (uptime monitor on log delivery, or the 15.3-added health endpoint if implemented).
- Containers: `restart: unless-stopped` (compose) — matches sbt-packager images which run the JVM directly.

## 12. Structured logs

- API/worker: slf4j/logback to stdout — JSON-ish key lines with request id, method, path, status, duration (`Middleware.scala` access log; no bodies, no secrets — verified 15.1).
- Vercel: build + runtime logs in dashboard; enable Log Drains → Vercel Log Sink or external (e.g., Axiom free tier) if retention is needed.
- VPS: compose `logging: json-file` with rotation (`max-size: 10m`, `max-file: 3`), or Caddy access logs off / errors only.

## 13. Monitoring

Staging minimum (owner action): uptime monitor on `GET /ready` (API) and the frontend URL; log-based alert on worker heartbeat silence (>2 min) and on `send_attempt` rows with `outcome_unknown`. Full observability remains the documented 14.4 owner action (not provisioned yet).

## 14. Resource limits

| Component | Limit | Rationale |
|---|---|---|
| api container | `mem_limit: 1g`, JVM heap default | JVM baseline + headroom on a 4 GiB VPS |
| worker container | `mem_limit: 768m` | Single polling loop; modest |
| caddy | `mem_limit: 256m` | TLS proxy only |
| Postgres | Supabase-managed | No local DB on the VPS |
| API rate limit | 120 req/min/caller | Existing default |

## 15. Deployment rollback

| Layer | Rollback |
|---|---|
| Frontend | Vercel **Instant Rollback** to the previous production deployment (no rebuild) |
| API/worker | compose pull of the previous image tag (tags = git SHA, never `latest`-only) + `docker compose up -d`; JVM restart < 30s; in-flight jobs recover via lease requeue |
| Database | Supabase staging PITR/backups (owner enables scheduled backups); migrations are forward-only — a bad migration is reverted by a corrective forward migration |
| Worker safety | Rollout rule: deploy worker and API from the same commit; the worker refuses sends when campaign state changed under it (pre-send guards, verified) |

## 16. Phase 15.3 execution status (2026-11-10)

- **Staging Supabase identity verified:** linked ref `kryxwfjaafcedtqruges` = `arcadence-staging` (ap-northeast-1), matching `MANUAL_SETUP.md`.
- **Migration gap found and closed:** staging was missing the last 4 migrations (`billing`, `domains_security`, **`rls_hardening`**, `founding_access`) — applied forward-only via `supabase db push --remote` (purely additive; no reset, no data touched). Staging now records all 43 migrations.
  - ⚠️ Notably the **Phase 14.1 RLS hardening had never reached staging** — the 12 engine tables were still RLS-off there. Post-push anon probes confirm the deny-by-default posture is live (`send_attempt`, `job_queue`, `inbound_events` now return empty arrays to anon instead of being readable).
- **Post-push verification:** `founding_program` exists with the intentional public counter policy (200, zero rows); member-scoped tables filter anon to empty sets (correct RLS behavior); `billing_*` tables created (plans catalog is code-level, not a table).
- **Images built:** `arcadence/arcadence-api` + `arcadence/arcadence-worker` via sbt-native-packager (non-root uid 1001); SHA-tagging convention documented in `infrastructure/README.md`.
- **Staging configuration shipped:** `infrastructure/docker-compose.staging.yml` (healthchecks, memory limits, log rotation, graceful-stop periods), `infrastructure/Caddyfile` (TLS + HSTS), `infrastructure/staging.env.example` (no secrets).
- **Worker staging safety shipped (env-gated, tested):** `SENDING_ENABLED` switch (default **false on staging** via compose — pending jobs stay queued, never failed; lease recovery continues), `TEST_RECIPIENT_ALLOWLIST` enforcement in the send handler (rejects non-allowlisted recipients before any provider contact), conservative `WORKER_BATCH_SIZE=2` / `WORKER_POLL_SECONDS=10`. Idempotency and suppression guards pre-existed (verified 15.1).
- **Container-level smoke executed locally** (see the 15.3 commit message / HANDOFF for recorded results): API image serves `/health` + `/ready` against the local stack; worker image starts, announces `sending DISABLED — queue paused`, heartbeats, and claims nothing.
- **Not done (blocked on owner):** VPS provisioning + DNS (MANUAL_SETUP #10), real staging `DATABASE_URL` credentials, remote deployment + `/ready` smoke over TLS, Supabase staging backups toggle (MANUAL_SETUP #16).
