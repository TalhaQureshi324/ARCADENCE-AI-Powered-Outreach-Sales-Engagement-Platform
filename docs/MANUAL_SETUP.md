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
| 5 | Create Supabase project (choose org + region close to your users) | Phase 0/1 | **done** | `arcadence-staging` (ap-northeast-1) created 2026-10-10. URL + publishable key staged in git-ignored `apps/*/.env.local`. ⚠️ DB password was shared via chat — rotate it (Dashboard → Project Settings → Database) when convenient |
| 6 | Google Cloud project + OAuth consent screen + Gmail API scopes (testing/publishing status) | Phase 2 | later | Google verification can take days — start early when Phase 2 nears |
| 7 | Microsoft Entra ID app registration (mail scopes) | Phase 2 | later | Tenant + consent configuration |
| 8 | Sending domain(s) with SPF/DKIM/DMARC DNS access | Phase 2/5 | later | Deliverability baseline per current bulk-sender requirements |
| 9 | Vercel account (or chosen Next.js host) + project link to the repo | Deployment phase | later | |
| 10 | VPS / container host + container registry choice | Deployment phase | later | |
| 11 | Stripe account (test mode first) | Billing phase | later | |
| 12 | AI provider account + API key policy (which provider(s), spend limits) | AI phase | later | Key is added via server-side env config, never in chat |

## Working agreement

- When a phase needs an item above, the implementation report will list it as the **precise blocking user action** (per `AGENTS.md` §3.8).
- Provide credentials only through environment variables/secret managers — never in chat, tickets, or commits.
