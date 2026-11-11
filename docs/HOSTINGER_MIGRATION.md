# Hostinger VPS Migration Plan — Phase 15.6 (readiness; nothing moves yet)

**Decision of record:** `docs/STAGING_DEPLOYMENT.md` Option A. **Supabase remains the managed database** — any change to that is a future explicit owner decision. Deployment-provider settings live exclusively under `infrastructure/` (compose, Caddy, env templates); core Scala code reads environment variables only — no provider is coupled to business logic.

**Current state → target state:**

| Layer | Today (private testing) | Target (Hostinger VPS) |
| --- | --- | --- |
| Frontend | Vercel (`apps/web`, Hobby plan) | Same topology on the VPS (`web` container) — migrated when the owner decides; Vercel path stays available as instant rollback |
| Scala API + worker | Docker Compose on any VPS (15.3 bundle) | Same images, `docker-compose.hostinger.yml` |
| TLS | Vercel + Caddy | Caddy (Let's Encrypt, automatic) |
| Database | Supabase staging project | Supabase **production** project (managed; staging stays for testing) |

**Bundle contents (committed):** `docker-compose.hostinger.yml` (validated), `Caddyfile.hostinger`, `hostinger.env.example`, `apps/web/Dockerfile` + `.dockerignore` (self-host web container, built and smoke-tested in 15.6), staging bundle from 15.3.

---

## 1. Precondition checklist (owner, before anything moves)

- [ ] Hostinger VPS provisioned (recommended ≥2 vCPU / 8 GB; Ubuntu 24.04), Docker + Compose plugin installed, SSH key auth.
- [ ] Supabase **production** project created; migrations applied forward-only (`supabase db push --remote` after `supabase link`); scheduled backups/PITR enabled (MANUAL_SETUP #16 pattern).
- [ ] DNS zone accessible for `app.<domain>` / `api.<domain>` records.
- [ ] OAuth consoles updated (§4).
- [ ] Production secrets generated and stored in the owner's secret manager (§7).
- [ ] Images built and SHA-tagged, transferred to the VPS (§2 of `infrastructure/README.md`).

## 2. DNS

1. **T-1 day:** lower the TTL of existing records to 300s.
2. Add `A` records: `app.<domain>` → VPS IPv4; `api.<domain>` → VPS IPv4 (AAAA if IPv6). During dual-run both Vercel and the VPS are alive; the switch is a re-point.
3. Cutover = flipping `app.<domain>`/`api.<domain>` to the VPS IP. Old Vercel URLs (`*.vercel.app`) keep working for rollback without DNS.

## 3. TLS

- Caddy requests Let's Encrypt certificates automatically for `{$APP_HOST}`/`{$API_HOST}` when DNS resolves and ports 80/443 are open — no manual cert steps.
- HSTS + security headers are in `Caddyfile.hostinger`; API hardening headers already served by the app too.
- Vercel TLS continues serving `*.vercel.app` during dual-run — no interference.

## 4. OAuth callback URLs (BEFORE cutover)

Add these in the Google Cloud console (authorized redirect URIs) and Microsoft Entra app registration — both may coexist with the staging URIs:

```
https://api.<domain>/api/v1/email-connections/oauth/google/callback
https://api.<domain>/api/v1/email-connections/oauth/microsoft/callback
```

Set `GOOGLE_OAUTH_REDIRECT_URI` / `MSFT_OAUTH_REDIRECT_URI` in the VPS `.env` (do not rely on the derived defaults with custom origins). Unverified apps stay in testing mode with explicit test users until the owner completes verification (MANUAL_SETUP #6/#7).

## 5. Supabase Auth allowed redirects

Supabase production → Authentication → URL Configuration:

- **Site URL:** `https://app.<domain>`
- **Redirect URLs:** `https://app.<domain>` (+ `https://<vercel-app>.vercel.app` kept during dual-run so both frontends can confirm emails)

## 6. Cookies and CORS

- Sessions are `@supabase/ssr` **same-origin cookies** on whatever origin serves the app — HTTPS on the VPS via Caddy behaves identically to Vercel. No code change.
- **CORS:** the API currently reflects any origin (15.1 audit §4, tracked hardening). It therefore works unchanged against any frontend origin. The pre-public-launch hardening step — restricting to `APP_ORIGIN`-derived allowlist — is explicitly part of the VPS go-live checklist, not a migration prerequisite.

## 7. Secret transfer

- Production `.env` is **authored fresh on the VPS** from `infrastructure/hostinger.env.example`; values come from the owner's secret manager. `chmod 600 .env`; never committed; never copied from staging (`EMAIL_ENCRYPTION_KEY` and DB credentials MUST be new production values — reusing the staging encryption key would make production mailbox credentials share fate with staging).
- `NEXT_PUBLIC_*` values are **build-time** for the self-hosted web container (inlined into the bundle) — passed as Docker build args; changing them requires an image rebuild (documented in `apps/web/Dockerfile`).
- API secrets (`DATABASE_URL`, `SUPABASE_JWT_*`, `EMAIL_ENCRYPTION_KEY`, OAuth client secrets, AI key) are runtime env — set once, rotated via re-deploy.

## 8. Data compatibility

- **No data migration happens in this step.** The production Supabase project starts empty: migrations applied via `supabase db push` (forward-only, version-controlled). Staging test data is deliberately **not** copied; production data is never copied casually in either direction.
- Schema compatibility is guaranteed by the migration discipline (one migration history; the schema-wide RLS CI gate asserts coverage).
- Historical/first-run data: the founding program row is seeded by API bootstrap with production-defaults (capacity 10,000, status active) — verify post-deploy via `GET /api/v1/founding-program`.

## 9. Worker handoff and duplicate-send prevention

The sending role must be held by exactly **one** worker against any database:

1. Staging worker runs against the staging Supabase project — a *different database* — so production cutover does not create overlap by construction.
2. If a worker is ever moved between environments sharing a database: **stop the old worker first**, wait out the lease window (`WORKER_LEASE_SECONDS`, ≤300s), then start the new one. Leases expire and interrupted jobs requeue — no orphan claims.
3. Duplicate-send safety nets that hold regardless of handoff: per-action idempotency keys (unique index), prior-evidence check before submission, unique-violation reconciliation in the send handler, and per-workspace suppression re-check immediately pre-send (all verified in 15.1/15.5).
4. Production ships with `SENDING_ENABLED=false` (compose default). The owner flips it only after mailbox verification, suppression review, and quota confirmation — the same staging-safety switches from 15.3 gate this.

## 10. Rollback procedures

| Layer | Rollback |
| --- | --- |
| DNS | Re-point `app.`/`api.` records to the previous target (Vercel / prior host). TTL was lowered to 300s in advance → minutes. Vercel instant-rollback remains available while the frontend lives there. |
| API/worker containers | `API_IMAGE_TAG`/`WORKER_IMAGE_TAG` are git-SHA-pinned; re-deploy the previous tag (`docker compose up -d` after re-pull) — JVM restart < 30s, interrupted jobs requeue via leases. |
| Web container | Same tag-pinning; previous image re-pull. |
| Database | Supabase PITR / scheduled backups (owner-managed); migrations are forward-only — a bad migration is corrected by a forward fix, never by reverting history. |
| Config | `.env` is file-based on the VPS; keep a dated copy of each change in the owner's secret manager for point-in-time config recovery. |

## 11. Downtime minimization

- **Dual-run:** Vercel and the VPS app both serve; the API domain moves once. Verify the VPS stack fully (§12) BEFORE any DNS change.
- **Cutover = DNS flip** (minutes, off-peak), not a data migration or long write.
- Database is untouched (managed Supabase) — the largest historical downtime source doesn't exist here.
- Worker: pause (`SENDING_ENABLED=false`) → cutover → verify → enable. The queue persists in Postgres; nothing is lost while paused.
- Scheduled sends continue from queued state after restart (lease requeue + idempotency keys prevent double-fire).

## 12. VPS go-live verification checklist

1. `docker compose -f docker-compose.hostinger.yml up -d` → all four containers healthy (`docker compose ps`).
2. `curl https://api.<domain>/health` → 200; `curl .../ready` → 200 `database: ok`.
3. `curl https://app.<domain>/login` → 200 with security headers.
4. Signup → email confirmation → onboarding → workspace creation (Supabase production project).
5. Worker log shows heartbeat + `sending DISABLED — queue paused` (until enabled).
6. OAuth connect round-trip for one test mailbox (after §4).
7. Queue-health queries clean (`infrastructure/README.md`).

## 13. Backup & restore

- **Database (primary data):** Supabase production scheduled backups + PITR (owner enables; verify with a monthly restore drill into a scratch project). The VPS holds **no application data** — containers are stateless by design (only Caddy certificate volumes `caddy_data`/`caddy_config`).
- **TLS certificates:** backed by the `caddy_data` volume; loss is self-healing (Caddy re-issues from Let's Encrypt).
- **Configuration:** `.env` dated copies in the owner's secret manager (§10); the entire deployable bundle lives in git.
- **Restore drill:** rebuild the stack from git + `.env` copy on a fresh host, point at the Supabase PITR target, run the §12 checklist — this is the disaster-recovery path and doubles as the Hostinger→any-VPS portability proof.

## 14. Provider isolation (standing rule)

Provider-specific settings exist only in `infrastructure/*` and environment variables. No Scala module, worker, or API route references Vercel, Hostinger, Docker, or Caddy. Moving hosts again means writing new compose/env files — zero application-code change. Frontend on Vercel vs self-hosted is likewise invisible to the API (`NEXT_PUBLIC_API_URL` + `APP_ORIGIN` are the only coupling points).
