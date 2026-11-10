# Vercel Deployment — Next.js Frontend (Phase 15.4)

**Scope:** the private-testing deployment of `apps/web` on Vercel, per the architecture decision in `docs/STAGING_DEPLOYMENT.md` (Option A: Vercel frontend + Docker-hosted Scala API/worker). **The deployment itself is an owner action** (Vercel account); this document is the exact runbook. The in-repo configuration (`apps/web/vercel.json`) makes the project settings explicit so dashboard choices are minimal.

**Status: prepared and locally verified — awaiting owner Vercel project creation (MANUAL_SETUP #9). Deployment URL: recorded in the table at the bottom once live.**

---

## 1. What is already prepared in-repo

- `apps/web/vercel.json` — framework `nextjs`, install `npm ci`, build `npm run build` (matches the committed `package-lock.json`; no workspace tooling exists at the repo root, so Vercel's **Root Directory = `apps/web`** is the only structural setting needed).
- `apps/web/.env.example` — the complete public env surface (4 `NEXT_PUBLIC_*` variables; the audit verified **zero server-only secrets and zero service-role references** in the frontend).
- `next.config.ts` — security headers on every response (`nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy`); `cacheComponents` deliberately disabled (documented Phase 1.4 constraint).
- Auth flows verified end-to-end on the **production build** (same artifact Vercel serves): signup → email confirmation → onboarding → dashboard, session refresh, logout, password recovery, expired-link handling — Playwright `auth.spec` 6/6; workspace isolation lifecycle (`multi-org.spec`) 1/1 against the real Scala API.
- App layout opts out of static prerender via `connection()`; all authenticated data is client-fetched with bearer tokens — no server-side caching of authed content.

## 2. Owner steps (Vercel dashboard, ~10 minutes)

1. **Import the repo:** Vercel → Add New → Project → import `TalhaQureshi324/ARCADENCE-AI-Powered-Outreach-Sales-Engagement-Platform`.
2. **Root Directory:** set to `apps/web` (Vercel will offer this after detecting the monorepo layout). Leave every other build setting at default — `vercel.json` pins framework/install/build.
3. **Environment variables** (Project → Settings → Environment Variables; all four are PUBLIC-classified per `docs/ENVIRONMENT.md` — never add a Supabase service-role key):

   | Variable | Value |
   | --- | --- |
   | `NEXT_PUBLIC_SUPABASE_URL` | staging project URL, `https://kryxwfjaafcedtqruges.supabase.co` |
   | `NEXT_PUBLIC_SUPABASE_ANON_KEY` | staging publishable key (Supabase → Settings → API) |
   | `NEXT_PUBLIC_API_URL` | the staging API origin, e.g. `https://api.<your-domain>` (from `STAGING_DEPLOYMENT.md` §2; the VPS must be live first, or temporarily the local tunnel for smoke checks) |
   | `NEXT_PUBLIC_GOOGLE_OAUTH_ENABLED` | `true` only if Google sign-in is configured in Supabase Auth (MANUAL_SETUP 5c); otherwise omit |

   Apply to **Production, Preview and Development** environments.
4. **Deployment Protection** (Settings → Deployment Protection): enable **Vercel Authentication** (or Password) on Production — this is what makes the deployment *private testing*. Note the **Protection Bypass for Automation** secret it generates; the 15.5 browser tests will need it (send as the `x-vercel-protection-bypass` header, documented below).
5. **Deploy.** Every push to any branch gets a preview URL; `main`/`develop` map per default Git behavior.
6. **Supabase Auth callback wiring** (Supabase staging dashboard → Authentication → URL Configuration):
   - Site URL: the Vercel production origin (e.g. `https://arcadence-web.vercel.app` or the custom domain).
   - Redirect URLs: add the same origin (covers `/auth/confirm` PKCE exchange, `/reset-password`, invites).
7. **API-side wiring** (on the VPS `.env`): set `APP_ORIGIN` to the same Vercel origin — it drives workspace invite links and OAuth callback redirects (Phase 15.2 change).

## 3. Verified behaviors & constraints

- **Sessions:** `@supabase/ssr` cookie-based sessions refresh at the edge in `middleware.ts`; cookies are HTTPS-only on Vercel automatically. Nothing to configure.
- **CORS:** the Scala API currently reflects any origin (bearer auth, no cookies — 15.1 audit §4). Works unchanged against the Vercel origin; origin allowlisting remains a pre-public-launch hardening item.
- **Cache safety:** authenticated pages are dynamic (`connection()` opt-out); client fetches send no-cache semantics by default. No `Cache-Control` overrides needed.
- **No fake backend:** there is no JavaScript reimplementation of Scala functionality and none may be added — the frontend requires `NEXT_PUBLIC_API_URL` to point at the real Scala API.
- **Preview deployments** work normally (unlike Vercel Daemons, Functions/Next.js previews are fully supported).

## 4. 15.5 test access through Deployment Protection

When Vercel Authentication is enabled, automated browser tests bypass it with the project's **Protection Bypass secret**:

```
curl -H "x-vercel-protection-bypass: <secret>" https://<deployment-url>/login
```

(Playwright: `extraHTTPHeaders: { "x-vercel-protection-bypass": process.env.VERCEL_BYPASS }`.) The secret lives only in the owner's Vercel dashboard / local env — never committed.

## 5. Post-deploy smoke checklist (run once the VPS API is live)

- [ ] `curl -I https://<vercel-url>/login` → 200 + security headers present
- [ ] Signup → confirmation email → onboarding → dashboard (Supabase staging)
- [ ] Browser console: no errors on `/`, `/login`, `/app`
- [ ] Workspace creation succeeds (frontend → Scala API round-trip with bearer JWT)
- [ ] Logout returns to `/login`; protected routes redirect with `?next=`
- [ ] Mobile viewport (390×844): login/signup render without horizontal overflow (verified locally on the production build)

## 6. Deployment URL record

| Item | Value |
| --- | --- |
| Project name | _(owner fills in after import)_ |
| Production URL | _(record after first deploy)_ |
| Protection mode | Vercel Authentication / Password |
| Bypass secret location | owner's secret manager (never committed) |
| Deployed commit | _(record)_ |
| Date | _(record)_ |
