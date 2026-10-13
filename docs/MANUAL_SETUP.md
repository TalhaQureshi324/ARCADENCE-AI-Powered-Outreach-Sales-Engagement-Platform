# Arcadence — Manual Setup (Product Owner Actions)

> Tasks that require **your** external accounts, approvals, or clicks — things agents cannot do for you. Each item names the phase that needs it so you can prioritize. Agents never ask for credentials in chat or commit them anywhere.

**Last updated:** 2026-10-09

---

## Status legend

`done` · `action needed` · `later` (needed by a future phase)

## Queue

| # | Task | Needed by | Status | Notes |
| --- | --- | --- | --- | --- |
| 1 | GitHub repository `TalhaQureshi324/ARCADENCE-AI-Powered-Outreach-Sales-Engagement-Platform` created | Phase 0 | **done** | Remote reachable and empty; foundation pushed 2026-10-09 |
| 2 | Verify Git push credentials work from this machine (push succeeded = done; if a push ever fails with auth errors, run `gh auth login` or update Windows Credential Manager) | Phase 0 | **verify on next push** | First push used HTTPS remote |
| 3 | Enable branch protection on GitHub: `main` requires PRs; protect `develop` likewise | Phase 0 | **action needed** | Settings → Branches → Add rule. Cannot be done by the agent without admin API access |
| 4 | Install local toolchain: JDK 21 (Temurin), sbt, Node 22 LTS, Docker Desktop, Supabase CLI | Phase 0 | **partially done** | JDK 21.0.12.1 + sbt installed via winget by the agent (2026-10-09); Node 24 already present. ⬜ Docker Desktop + Supabase CLI still needed before subphase 0.3. Open a new terminal after winget installs for updated PATH |
| 5 | Create Supabase project (choose org + region close to your users) | Phase 0/1 | **done** | `arcadence-staging` (ap-northeast-1) created 2026-10-10. URL + publishable key staged in git-ignored `apps/*/.env.local`. ⚠️ DB password was shared via chat — rotate it (Dashboard → Project Settings → Database) when convenient. Note: direct DB host is IPv6-only; use the session pooler (already configured in `.env.local`) |
| 5b | Run `supabase login` in your own terminal (one-time browser auth) so migrations can be pushed to staging | Phase 0 | **action needed** | Agent shells are non-interactive — `supabase login` needs your browser. After you log in, say "go" and the agent will run `supabase link` + `db push` + staging RLS verification |
| 5c | Google OAuth provider (optional): create a Google Cloud OAuth client and add it under Supabase Dashboard → Authentication → Providers → Google | Phase 1 | later | App sign-in works without it (the "Continue with Google" button appears only when `NEXT_PUBLIC_GOOGLE_OAUTH_ENABLED=true` is set). Note: this is application identity only — Gmail mailbox access (Phase 2) uses a separate, explicitly-scoped consent |
| 6a | Generate and back up `EMAIL_ENCRYPTION_KEY` for every environment the API runs in (`openssl rand -base64 32`) | Phase 2 | **done locally** | A local key was generated into git-ignored `apps/api/.env.local` (2026-10-13). For staging/production deployments the owner generates and stores the key in the platform secret manager; **losing it makes stored credentials undecryptable** |
| 6 | Google Cloud project + OAuth consent screen + Gmail API scopes (testing/publishing status) | Phase 2 | **needed for 2.2** | Google verification can take days — start now. Required: OAuth client (web) with redirect URI `http://localhost:8080/api/v1/email-connections/oauth/google/callback` (dev; the Scala API completes the exchange and redirects the browser back to the app) or the production equivalent; scopes `gmail.compose`, `gmail.readonly`, `userinfo.profile`; client ID/secret go to `apps/api/.env.local` as `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` when subphase 2.2 lands. Unverified testing apps run in "External + testing" mode with explicitly added test users — that is not production approval |
| 7 | Microsoft Entra ID app registration (delegated Mail.Read / Mail.Send / User.Read / offline_access) | Phase 2 | **needed for 2.3** | Tenant + consent configuration; redirect `http://localhost:3000/api/auth/microsoft/callback` (dev). Tenant admins may block third-party apps — record tenant restrictions when the app is registered |
| 8 | Sending domain(s) with SPF/DKIM/DMARC DNS access | Phase 2/5 | later | Deliverability baseline per current bulk-sender requirements |
| 9 | Vercel account (or chosen Next.js host) + project link to the repo | Deployment phase | later | |
| 10 | VPS / container host + container registry choice | Deployment phase | later | |
| 11 | Stripe account (test mode first) | Billing phase | later | |
| 12 | AI provider account + API key policy (which provider(s), spend limits) | AI phase | later | Key is added via server-side env config, never in chat |

## Working agreement

- When a phase needs an item above, the implementation report will list it as the **precise blocking user action** (per `AGENTS.md` §3.8).
- Provide credentials only through environment variables/secret managers — never in chat, tickets, or commits.
