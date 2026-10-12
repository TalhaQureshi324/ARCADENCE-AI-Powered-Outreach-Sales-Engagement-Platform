# Arcadence — Handoff Log

> Running record of what was implemented, tested, committed, and pushed. One entry per subphase, newest first. Matches the implementation reports given in chat.

---

---

## 2026-10-12 — Phase 1 / Subphase 1.4: Workspace administration & hardening

**Status:** ✅ Completed and pushed — **Phase 1 milestone**

### Implemented

- **Settings UI (`/app/settings`)**: permission-aware tabs on live API data — Members (avatar/email/role table, inline role changes, removal), Invitations (invite form with role, open-invite table with links + revoke, duplicate → 409 surfaced), Profile (name/timezone/daily-cap form). Non-managers see read-only views with explanatory gates.
- **Invitation accept page (`/app/invitations/[token]`)**: shows workspace/role from `GET /api/v1/invitations/{token}`, accepts via POST; handles invalid/expired links.
- **Hardening (Scala)**: per-caller sliding-window rate limiting (`RATE_LIMIT_PER_MINUTE`, default 120/min, keyed by Authorization header; 429 with error envelope) and security headers (nosniff, DENY framing, same-origin referrer, no-store, Permissions-Policy) on all `/api/v1` responses. `GET /api/v1/invitations/{token}` details endpoint added. Next.js serves the same header baseline.
- **Session-expiry handling**: WorkspaceProvider redirects to `/login?expired=1` on 401s; settings/profile surfaces degrade gracefully.

### Fixed during 1.4 (root causes worth remembering)

1. **Route-group path bug**: `(app)/settings/page.tsx` serves `/settings`, NOT `/app/settings` — route groups don't add URL segments. Settings/invitations pages moved under `(app)/app/…`. The intermittent 404s in e2e were this, not Next quirks.
2. **cacheComponents removed**: its prerender probing is incompatible with supabase-js session checks (internal `Date.now()`) in authed layouts — authed routes intermittently 404'd in dev. `partialPrefetching` removed with it (requires cacheComponents). Suspense boundaries retained.
3. API env alignment documented (local stack vs staging swap) in apps/api/.env.local.

### Tested (evidence)

- Scala `scalafmtAll scalafmtCheckAll test` → **[success] 34/34** (worker 2 · core 16 · api 32 incl. HardeningSpec 6: rate-limit window/slide/independence/429-envelope + security-header assertions).
- Web: Vitest **25/25** · lint 0 errors · build green · **Playwright 8/8** — including the new two-organization spec: independent signups, isolated workspace lists, UI invite → accept → member-gating assertions → removal → immediate access revocation.
- Security headers verified live on `/api/v1/*` responses; rate limiter live (config default 120/min).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-01-admin-hardening` → `develop` (no-ff merge) |
| Milestone | `develop` promoted to `main`, tag `phase-1` |

### Next

- **Phase 2 — Email account connections** (Gmail/Microsoft OAuth, SMTP/IMAP, credential encryption), pending product-owner go.

---

## 2026-10-12 — Phase 1 / Subphase 1.3: Application shell & design system

**Status:** ✅ Completed and pushed

### Implemented

- **Design tokens & theme system:** CSS custom properties (surfaces, brand indigo, semantic colors, borders, two-level soft shadows, radii) mapped into Tailwind v4 via `@theme inline`; class-based dark mode with a no-flash bootstrap script, `useSyncExternalStore`-based `useTheme` hook (React 19 lint-compliant), and `prefers-reduced-motion` respected globally.
- **Component library (Radix-backed):** Dialog, Dropdown Menu, Tabs, Table (+Head/Body/Row/Cell), Pagination (with pure helpers), Skeleton, Badge (status tones), EmptyState, Toast provider, Avatar (initials), Breadcrumbs; existing Button/Input/Field/Card/Alert migrated to tokens.
- **App shell:** responsive layout — desktop sidebar (brand, workspace selector, nav with honest "soon" badges, phase footer) + mobile topbar nav; workspace selector with live API data and create dialog; profile menu (profile, theme switch, SDK sign-out); notifications menu (honest empty); ⌘K command palette (keyboard-navigable); breadcrumbs.
- **Typed API client:** `apiFetch` attaches the Supabase access token, parses the API error envelope into a typed `ApiError`; `workspacesApi.list/create`.
- **Dashboard rewrite:** real workspace list from the Scala API (loading skeletons / error+retry / empty state), workspace creation end-to-end, real system status (Auth + Scala API health badges), roadmap placeholders, onboarding nudge.

### Tested (evidence)

- **Vitest 25/25** (new: api client token/error mapping ×3, badge/breadcrumbs/empty-state/pagination ×4).
- **Playwright 7/7**: the 6 auth lifecycle tests re-validated against the new shell, plus `shell.spec` — signup→dashboard, empty state, workspace creation through the dialog (visible in selector and dashboard), ⌘K palette, theme toggle.
- Lint 0 errors · `tsc --noEmit` clean · production build green.

### Bugs found & fixed during 1.3

1. **CORS gap:** only health routes had the CORS policy — browser calls to `/api/v1/*` were blocked. Whole API surface now wrapped, with explicit allowed methods/headers (Authorization, Content-Type, Idempotency-Key).
2. **Environment alignment rule:** web signs tokens against the auth project it uses; the API must verify against the SAME project's JWKS. Local dev now runs the entire stack (web + API + DB + auth) against the local Supabase project; apps/api/.env.local documents the staging swap.
3. Sign-out moved to the SDK inside the profile menu (form-in-dropdown was unreachable); workspace creation dispatches a reload event so dashboard and selector stay in sync.

### Database migrations

- None (1.2's remain current).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-01-app-shell` → `develop` (no-ff merge) |
| Remote | pushed (hashes in chat report) |

### Next

- **Subphase 1.4 — Workspace administration and hardening** (invitation UI flows, security headers, rate limits, Playwright cross-tenant coverage, milestone docs).

---

## 2026-10-12 — Phase 1 / Subphase 1.2: Organizations, workspaces & authorization

**Status:** ✅ Completed and pushed

### Implemented

- **JWT authentication middleware (Scala):** `SupabaseJwtAuthenticator` verifies access tokens against the project's live JWKS — signature (ES256; RS256 legacy supported), issuer, audience and expiry — with a cached, rate-limited key provider and `IO.blocking` offload. java-jwt 4.6.1 + jwks-rsa 0.24.1. `Authenticator` is an interface: tests substitute a token-map implementation.
- **Explicit RBAC:** `WorkspaceRole` (owner, admin, campaign_manager, member, viewer — campaign_manager added via migration `20261012100000`) and `Permissions` (role → permission-set mapping as the single source of truth; endpoints name permissions, never roles). `Authorizer` resolves membership **from the database** per request: non-members → 404 `workspace_not_found` (no existence leak), members lacking permission → 403 `insufficient_role`.
- **Business API (`/api/v1`)**: workspaces CRUD (auto-creates the owning organization + owner membership), member list/role-change/remove (owner membership immutable, self-role-change/self-removal blocked), invitations (create with duplicate-open-invite 409, list, revoke, token-based accept), `/me`. Membership/role/invitation actions write `audit_log` entries. Doobie with explicit tenant-scoped SQL everywhere; `/ready` now includes a real database check.
- **Staging cloud project:** linked via CLI (product owner completed `supabase login`); **all 5+1 migrations pushed** through the session pooler; full `DbConnectivitySpec` run against staging: **15/15** (schema, RLS, helpers, enums, private buckets).

### Tested (evidence)

- `sbt scalafmtAll test` (with TEST_DATABASE_URL) → **[success], 44/44**: worker 2 · core 16 (incl. DB integration) · api 26:
  - `PermissionsSpec` 6 — role→permission matrix, label round-trip.
  - `JwtAuthenticatorSpec` 6 — **real ES256**: embedded JWKS server + generated P-256 keys; valid / expired / wrong-audience / wrong-issuer / wrong-key-signature / garbage all behave correctly.
  - `WorkspaceAuthIntegrationSpec` 7 — **cross-tenant matrix against the real DB**: non-member 404 on every verb; no workspace-list leakage; invite→accept→member; duplicate invite 409; member 403 on member-management and settings; owner membership immutable; removal revokes instantly; missing token 401.
  - `ConfigSpec` updated for the now-required DB/JWT settings.
- Compile warnings remain (unused-import notices); no errors. CI will run the hermetic subset (DB-gated specs skip).

### Database migrations

- `20261012100000_add_campaign_manager_role.sql` (applied locally via reset and pushed to staging).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-01-workspaces-authz` → `develop` (no-ff merge) |
| Remote | pushed (hashes in chat report) |

### Manual setup actions

- Unchanged queue: C: drive cleanup; DB password rotation; branch protection. Google OAuth optional (#5c).

### Next

- **Subphase 1.3 — Complete application shell and design system.**

---

## 2026-10-12 — Phase 1 / Subphase 1.1: Supabase authentication

**Status:** ✅ Completed and pushed

### Implemented

- **Full auth lifecycle** on Next.js 16 + `@supabase/ssr` (cookie sessions, PKCE):
  - Registration with display name (goes to profile via the `on_auth_user_created` trigger), **real email confirmation** (local `enable_confirmations = true`, minimum password 8 in `config.toml`).
  - Login (invalid-credentials / unconfirmed-email / rate-limit states), logout (POST route — prefetch-safe), forgot-password, reset-password with recovery-session handling, expired/invalid-link error page.
  - Onboarding (updates `profile.display_name` through the RLS `profile_update_own` policy — real persistence) and a protected `/app` dashboard with genuine session data and honest "arrives in Phase X" placeholders.
- **Route protection:** root `middleware.ts` (matcher excludes static assets) refreshes sessions via `getUser()` (never trusts cookie payload) and guards `/app/*`, `/onboarding/*`; signed-in users are steered away from auth pages. Defense in depth: server pages re-check.
- **Safe redirects:** `sanitizeRedirect` (blocks absolute/protocol-relative/backslash/CRLF targets) with unit tests; `next` params sanitized everywhere.
- **Google sign-in:** OAuth button rendered only when `NEXT_PUBLIC_GOOGLE_OAUTH_ENABLED=true`; application identity explicitly separated from Gmail scopes (see MANUAL_SETUP #5c).
- **Local auth config:** email confirmations ON, min password 8; auth emails captured by **Mailpit** (:54324 — Supabase replaced Inbucket with Mailpit).
- **UI primitives** (Button/Input/Field/Card/Alert) with loading/validation/error states — foundation for the 1.3 design system.

### Bugs found & fixed (caught by the e2e suite)

1. **RLS helpers broken on PostgREST 13** (critical): PostgREST 13 removed `request.jwt.claim.*` GUCs, so `current_user_id()` resolved NULL and member-scoped policies silently matched zero rows (masked by the signup trigger writing profile names). Fixed in forward-only migration `20261012090000_fix_jwt_claim_gucs_for_postgrest13.sql` (coalesces claim.sub / jwt.sub / claims JSON). Would have broken all of Phase 1's tenancy.
2. Recovery links bounced to `/app`: middleware redirected signed-in users away from `/reset-password` — recovery sessions must reach the new-password form; page manages its own states.
3. Migration runner rejects nested dollar-quotes; `language sql` bodies are validated at creation (PG14+) — both worked around in migration structure.

### Tested (evidence)

- **Unit (Vitest): 18/18** — includes 14 new (sanitizeRedirect open-redirect matrix, email/password/name validation, OTP-type narrowing).
- **Playwright E2E: 6/6** against the real local stack (dev server + GoTrue + Mailpit): signup→confirmation-email→onboarding→dashboard, invalid credentials, protected-route redirect with `next`, login + session refresh + logout + re-lock, full password recovery (link → new password → old rejected → new accepted), expired/invalid link state.
- Lint clean; `tsc --noEmit` clean; production build green.

### Database migrations

- `20261012090000_fix_jwt_claim_gucs_for_postgrest13.sql` (applied locally via `db reset`; must be pushed to staging with the rest).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-01-supabase-auth` → `develop` (no-ff merge) |
| Remote | pushed (hashes in chat report) |

### Manual setup actions

- Unchanged: staging `db push` (needs `supabase login`), optional Google OAuth provider (#5c), C: drive cleanup, password rotation.

### Next

- **Subphase 1.2 — Organizations, workspaces and authorization** (Scala JWT middleware via JWKS, RBAC, invitations, cross-tenant isolation tests). The RLS GUC fix is the foundation it depends on.

---

## 2026-10-11 — Phase 0 / Subphase 0.3: Supabase configuration & local environment

**Status:** ✅ Completed and pushed (cloud `db push` to staging pending one user action — see Manual setup)

### Implemented

- **Tooling installed (autonomously, per product-owner authorization):** Docker Desktop 4.94 (winget), Supabase CLI v2.120.0 (official GitHub releases → `%LOCALAPPDATA%\Programs\supabase`). Toolchain scripts resolve both from PATH or fallback locations.
- **Disk incident resolved:** C: hit 0 bytes during image pulls (drive was ~97% full beforehand). Docker's WSL data directory moved to `D:\arcadence-tools\DockerWsl` with a junction from the original path; Scala caches moved to `D:\arcadence-tools\Coursier` (`COURSIER_CACHE`). Documented in SETUP troubleshooting.
- **`supabase/config.toml`** (project `arcadence`): auth site URL → localhost:3000, PostgreSQL major 17.
- **Foundational migrations** (`supabase/migrations/`, forward-only, portable to plain PostgreSQL for CI):
  - `20261010100000` — 21 enum types, `set_updated_at` trigger, tenancy helpers (`current_user_id`, `current_user_email`, `is_workspace_member`, `has_workspace_role` — JWT-GUC-based, portable, `check_function_bodies off` bootstrap note).
  - `20261010101000` — `profile`, `organization`, `workspace`, `workspace_member`, `invitation` with constraints/unique/indexes per design; conditional FKs to `auth.users`; RLS enabled with member/admin policies (writes service-role only); conditional `on_auth_user_created` profile provisioning trigger.
  - `20261010102000` — `job_queue` (idempotency + due-scan indexes), append-only `audit_log`, `webhook_endpoint`/`webhook_delivery` with admin-only RLS.
  - `20261010103000` — private storage buckets (`imports`, `exports`, `attachments`), conditional on storage schema.
- **`DbConnectivitySpec`** (env-gated): Doobie connectivity, table/RLS/helper/enum/bucket verification. URL parser handles Supabase-style URLs (jdbc prefix + credentials extraction — empty-string driver properties corrupt the PG driver's URL parsing; discovered and documented).
- **Bootstrap lessons (fixed in place):** PG14+ validates `language sql` bodies at creation (helpers reference later tables → `check_function_bodies = off` in that migration); migration runner does not support nested dollar-quotes (conditional trigger now uses a plain string execute).

### Tested (evidence)

- `supabase start` → local stack healthy on :54321/:54322/:54323; all four migrations applied cleanly on fresh init.
- Server-side psql check: 9 public tables; RLS `t` on all tenant tables, `f` only on `job_queue` (by design).
- `TEST_DATABASE_URL=<local> sbt core/test` → **15/15 pass** (connectivity, tables, RLS, helpers, enums, private buckets).
- `TEST_DATABASE_URL=<staging> TEST_DB_EXPECT_SCHEMA=false sbt core/test` → **10 pass, 5 schema-checks skipped (staging has no migrations yet), 0 fail** — Doobie → session pooler → staging PostgreSQL verified end-to-end.
- Staging findings: direct host `db.<ref>.supabase.co` is **IPv6-only** (current projects) — unreachable from this IPv4 network; **session pooler** `aws-0-ap-northeast-1.pooler.supabase.com:5432` (user `postgres.<ref>`) verified working and recorded in `.env.local` + ADR-0018 note. JWKS live check: project uses **asymmetric ES256 keys** — ADR-0003's JWKS verification design confirmed viable.

### Database migrations

- 4 migrations added and applied to the local stack (staging push pending CLI login).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-00-supabase` → `develop` (no-ff merge) |
| Remote | pushed (see chat report for hashes) |

### Manual setup actions

1. Run `supabase login` once in your own terminal (browser flow; non-TTY shells cannot). Then tell me — I'll run `supabase link --project-ref kryxwfjaafcedtqruges` and `supabase db push` to apply migrations to staging, and verify tenant policies on staging with the full spec run.
2. Recommended: rotate the staging DB password (shared in chat earlier).
3. Strongly recommended: free/extend C: drive space (was at 0 bytes; system drive health risk). Docker data now lives on D: via junction.

### Next

- **Subphase 0.4 — CI/CD & developer experience** (executed in the same session; see its entry).

---

## 2026-10-11 — Phase 0 / Subphase 0.4: CI/CD and developer experience

**Status:** ✅ Completed and pushed

### Implemented

- **GitHub Actions CI** (`.github/workflows/ci.yml`): three jobs on push/PR —
  - **scala:** JDK 21 + sbt via coursier setup, `scalafmtCheckAll` + `test` (DB specs self-skip without `TEST_DATABASE_URL` — hermetic).
  - **web:** Node 24 + npm ci, ESLint, Prettier check, Vitest, production build.
  - **migrations:** applies every migration to a clean `postgres:17` service container with `ON_ERROR_STOP`, then asserts RLS on all tenant tables and the four tenancy helpers exist — proving migrations are portable, not Supabase-console-dependent.
- **Structured request handling (api):** `RequestContext` middleware (assigns/forwards `X-Request-Id`, stamps it for inner layers, one structured access-log line with method/path/status/duration) + `ErrorEnvelope` middleware (unhandled failures → uniform JSON error envelope, internals never leaked) + JSON 404 fallback for unmatched paths (envelope). Wired in `Main`.
- **Docker configuration:** `JavaAppPackaging` + `DockerPlugin` (sbt-native-packager 1.13.0, base `eclipse-temurin:21-jre-noble`, images `arcadence/api` & `arcadence/worker`); `infrastructure/docker-compose.yml` wiring both services against the local Supabase stack via `host.docker.internal` with per-app env files.
- **Developer scripts:** `db-reset.sh` (repeatable local DB reset, ADR-0018), `check-toolchain.sh` extended (supabase + docker, engine-state check), `dev-api.sh`/`dev-worker.sh` now source per-app `.env.local` automatically.
- **SETUP.md rewritten:** verified local flow, CI notes, smoke-test guide (fresh checkout → running system in 8 steps), troubleshooting table, version-control rules.
- Prettier applied across the frontend; web `format:check` passes (CI parity).

### Tested (evidence)

- `sbt scalafmtAll test` → **[success]**; totals: core 15 (9 pass + 6 DB-skip), api 7, worker 2 — all green.
- `npm run lint`, `npm run format:check`, `npm test` (6/6) — green.
- Docker: images built locally; `docker compose up` verification captured in chat report (service start, `/health` probe, shutdown).
- CI runs automatically on push (results in chat report).

### Database migrations

- None new (0.3's set validated by the CI migrations job).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-00-ci-cd` → `develop` (no-ff merge) |
| Remote | pushed (see chat report for hashes) |

### Manual setup actions

- None new beyond 0.3's list (branch protection still recommended).

### Next

- **Phase 0 close-out:** `develop` → `main` promotion with `phase-0` milestone tag; then Phase 1 (auth & workspaces) pending the product owner's go.

---

## 2026-10-10 — Phase 0 / Subphase 0.2: Architecture & database design

**Status:** ✅ Completed and pushed

### Implemented

- **`docs/DATABASE.md`** — complete schema design: tenancy model (organization → workspace → member, ADR-0017); ERDs per domain (mermaid) + a 32-table catalog with key columns, unique constraints, and indexes spanning identity/tenancy, email connections, contacts/lists/suppression, campaigns/enrollments/scheduled actions, messaging/events, analytics rollups, CRM, AI audit, billing, and platform jobs/webhooks; full Postgres enum catalog; state-machine→column mapping; RLS helper-function design; indexing strategy.
- **`docs/ARCHITECTURE.md`** — module ownership matrix (module → owned tables → API vs worker responsibilities, with cross-module write prohibition); authorization model with role matrix and 403/404 semantics; four core state machines (campaign, enrollment, scheduled-action/job, message — unknown outcomes recorded as unknown); inbound and outbound webhook flow designs with idempotency and signature rules.
- **`docs/API.md`** — full public route catalog for phases 1–11 + internal webhook routes, all marked designed vs implemented; role requirements per route group.
- **ADR-0017** (tenancy model), **ADR-0018** (single migration workflow: Supabase CLI + PR review + CI push; dashboard GitHub App explicitly rejected; direct/session connections for services, transaction-pooling caveats), **ADR-0005 refinement** (concrete job tables).
- **Credential staging (local only):** Supabase project `arcadence-staging` URL, publishable key, and direct DATABASE_URL placed into git-ignored `apps/{web,api,worker}/.env.local` — activated in subphase 0.3. Nothing credential-bearing is committed.

### Tested (evidence)

- Design-only subphase (no runtime code changed). Verification: internal-consistency pass — every tenant entity carries `workspace_id` (billing intentionally org-scoped; `job_queue` global by design); enum names in `docs/DATABASE.md` match those referenced in `docs/ARCHITECTURE.md`; migration workflow single and unambiguous (ADR-0018); Scala/frontend test suites unaffected and still green from 0.1 (no code touched).

### Database migrations

- None yet (this subphase designs them; SQL starts in 0.3).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-00-db-design` → `develop` (no-ff merge) |
| Remote | pushed (see chat report for hashes) |

### Manual setup actions (for 0.3)

1. **Install Supabase CLI** (not installed on this machine) and **Docker Desktop** — needed for the local Supabase stack and migration authoring (MANUAL_SETUP #4).
2. Run `supabase login` once in any terminal (interactive browser login) so CLI commands can act on project `kryxwfjaafcedtqruges`.
3. Optional but recommended: rotate the staging DB password in the dashboard since it was shared via chat.

### Next

- **Subphase 0.3 — Supabase configuration and local environment** (CLI init/link, foundational migrations from the 0.2 design, RLS policies, storage buckets, Doobie connectivity tests against local + staging).

---

## 2026-10-09 — Phase 0 / Subphase 0.1: Repository & initial structure

**Status:** ✅ Completed and pushed

### Implemented

- **Monorepo layout:** `apps/web`, `apps/api`, `apps/worker`, `modules/core`, `packages/` (deliberately empty, see ADR-0015), `supabase/migrations/` (empty until 0.2/0.3), `infrastructure/` (populated in 0.4), `scripts/`, `docs/`.
- **Scala 3 sbt multi-module build** (root `build.sbt`): `modules/core` (shared config + health models, BuildInfo) ← `apps/api` and `apps/worker`. Pinned toolchain chosen from Maven Central release metadata and recorded in ADR-0016: Scala 3.3.8 LTS, sbt 1.12.15, Cats Effect 3.7.1, http4s 0.23.38 (ember), Tapir 1.13.33, circe 0.14.17, Doobie 1.0.0-RC12 + PostgreSQL driver 42.7.14 (declared, exercised from 0.3), log4cats 2.8.0 + logback 1.6.5, MUnit 1.3.6 + munit-cats-effect 2.2.1, sbt-scalafmt 2.6.2 / scalafmt 3.11.5, sbt-buildinfo 0.13.2.
- **API process** (`sbt api/run`): Tapir-described `GET /health` and `GET /ready` served via http4s ember on `API_PORT` (default 8080); validated env config (`API_PORT`, `LOG_LEVEL` — total parsers, aggregated errors); logback console logging with runtime level; CORS allow-all-without-credentials dev policy (locked down in 0.4); graceful shutdown timeout.
- **Worker process** (`sbt worker/run`): heartbeat event loop on `WORKER_TICK_INTERVAL_MS` (default 30000ms) with structured logs; config validated same as API.
- **Frontend** (`apps/web`): Next.js 16.4.0 (App Router, TypeScript, Tailwind v4, ESLint 9, React 19.3) scaffolded via create-next-app; system health page probing the API's `/health` + `/ready` with loading / up / degraded / unreachable states; reusable `StatusBadge` + `HealthDashboard` components; typed `probeApi` helper; Prettier (+tailwindcss plugin); Vitest 5 + Testing Library setup.
- **Tooling & governance:** `.editorconfig` (existing), `.gitignore` verified, `.env.example` per app (placeholders only, no secrets), proprietary `LICENSE` placeholder, dev scripts (`check-toolchain`, `dev-api`, `dev-worker`, `dev-web`, `test-all`, `fmt-all`), folder READMEs (`packages/`, `infrastructure/`, `supabase/`), `apps/web/AGENTS.md` pointer preserving Next 16's generated agent-rules block.

### Tested (evidence)

- `sbt test` → **14/14 passed** (`ConfigSpec` 9, `HeartbeatSpec` 2, `HealthRoutesSpec` 3) after `scalafmtAll`.
- `npm test` (Vitest) → **6/6 passed** (probeApi 4, StatusBadge 2).
- `npm run lint` (ESLint 9, incl. React 19 rules) → clean; `npm run build` → compiled + typechecked, 4 static pages.
- **Live smoke:** API on :8080 → `GET /health` = `{"service":"arcadence-api","version":"0.1.0-SNAPSHOT","status":"up"}`; `GET /ready` = `{"status":"ready","checks":{"process":"ok"}}`; unknown path → 404. Worker → startup banner + heartbeats every 2000ms honoring `WORKER_TICK_INTERVAL_MS`. Web production build served on :3000 → HTTP 200 with health page content.
- Iterations fixed during bring-up (all resolved): ValidatedNel→Either mapping, missing circe decoders + tapir `Schema.derived`, http4s interpreter endpoint typing (explicit `ServerEndpoint[Fs2Streams[IO], IO]` list), deprecated `CORS(...)` replaced by `CORSPolicy`, `@types/node` bumped `^20`→`^24` for Vitest 5 peer range, React 19 `set-state-in-effect` refactor, CNA path-with-spaces workaround (scaffolded in temp dir on same drive, moved in).

### Database migrations

- None (by design; `supabase/migrations/` is scaffolded empty).

### Git

| Item | Value |
| --- | --- |
| Branch | `feature/phase-00-repo-structure` → `develop` (no-ff merge), `main` untouched (promoted at phase end per ADR-0012) |
| Remote | pushed (see chat report for hashes) |

### Manual setup actions

- None new. Note: JDK 21.0.12.1 (Temurin) and sbt were installed on this machine via winget by the agent — open a NEW terminal for updated PATH, or use the `scripts/*.sh` helpers.

### Next

- **Subphase 0.2 — Architecture and database design** (ERD, module boundaries, state machines, migration workflow).

---

## 2026-10-09 — Phase 0 / Subphase 0.0: Repository foundation

**Status:** ✅ Completed and pushed

### Implemented

- Repository initialized on `main` with the full permanent documentation set:
  - `AGENTS.md` — binding coding-agent contract: mission, mandatory stack, one-subphase workflow, GitHub rules, security rules, UI design direction, quality standards, report format, status vocabulary.
  - `docs/PROJECT_STATUS.md` — provisional 14-phase roadmap; Phase 0 `in-progress` awaiting the product owner's detailed plan.
  - `docs/ARCHITECTURE.md` — target system design: Next.js frontend, Supabase platform, Scala 3 modular monolith (API + worker), multi-tenancy with defense in depth, data-flow patterns, deployment topology, testing strategy.
  - `docs/DECISIONS.md` — ADR-0001 … ADR-0014 covering the entire mandated architecture (stack, Supabase, auth/JWKS, modular monolith, durable jobs, storage, frontend/design, Stripe, deployment, testing, branch model, compliance posture, roadmap).
  - `docs/DATABASE.md` — binding migration & table conventions (RLS everywhere, workspace_id pattern, forward-only migrations); no schema exists yet.
  - `docs/API.md` — binding API conventions (JWT via JWKS, tenant-scoped paths, error envelope, idempotency, cursor pagination); no endpoints exist yet.
  - `docs/SETUP.md` — target toolchain and env-var plan; honest "scaffolding pending" status.
  - `docs/MANUAL_SETUP.md` — product-owner action queue (branch protection, toolchain, Supabase project, later-phase items).
  - `docs/SECURITY.md` — secrets policy, JWT verification, tenant isolation, data protection, email-compliance/anti-abuse posture, current limitations.
  - `README.md`, `CONTRIBUTING.md`, `.editorconfig`, `.gitignore`.
  - `.github/`: PR template with subphase checklist, bug/feature issue templates, `CODEOWNERS`.
- Branch model established: `main` (release) + `develop` (integration); feature branches `feature/phase-NN-slug` → PR → `develop`.

### Tested

- N/A (documentation-only subphase). Verification: `git status` clean after commit; staged file list reviewed — `excalidraw.log` (local MCP log) correctly excluded via `*.log` ignore rule; no secrets present in any committed file.

### Database migrations

- None (no schema yet, by design).

### Git

| Item | Value |
| --- | --- |
| Branch | `main` |
| Foundation commit | `7561767ca40243aeb45ba9e0fb5124ca0031f673` — `chore: initialize Arcadence repository foundation` |
| Handoff commit | recorded after this file (see `git log`) |
| Remote | `https://github.com/TalhaQureshi324/ARCADENCE-AI-Powered-Outreach-Sales-Engagement-Platform.git` |
| Push status | `main` + `develop` pushed (see chat report for final confirmation) |

### Manual setup actions

- Enable branch protection on `main` (and `develop`) in GitHub settings — see `docs/MANUAL_SETUP.md` #3.
- Confirm local toolchain (JDK 21, sbt, Node 22, Docker, Supabase CLI) before Phase 0 scaffolding — `docs/MANUAL_SETUP.md` #4.

### Next

- Product owner supplies the detailed **Phase 0 implementation plan**; agent executes it subphase-by-subphase (expected: build scaffolds, pinned toolchain, CI, skeleton API/worker/frontend, health checks, first deployment wiring).
