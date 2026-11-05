# Arcadence — Launch Checklist (Phase 13.5)

## Completed capabilities (Phases 0–12)

- [x] Auth, multi-tenant workspaces, RBAC, RLS (Phase 1)
- [x] Email accounts: Gmail/Microsoft OAuth, SMTP/IMAP with encrypted credentials (Phase 2)
- [x] Lead management: contacts, companies, imports, tags, segments, verification, suppression (Phase 3)
- [x] Campaigns: visual builder, scheduling, personalization, launch gates (Phase 4)
- [x] Sending engine: durable queue, real delivery, tracking, bounce handling, reliability (Phase 5)
- [x] Unified inbox: two-way sync, threading, classification, team workflows, real replies (Phase 6)
- [x] Analytics: event pipeline, dashboard, CSV export, anomalies (Phase 7)
- [x] Deliverability: DNS diagnostics, sender health, auto-pause, placement tests (Phase 8)
- [x] CRM: pipelines, deals, activities, auto-deal on replies; agency reporting (Phase 9)
- [x] Lead Finder + enrichment: provider framework, credits, licensing (Phase 10)
- [x] AI: gateway, writer, reply agent, sales agent, AI Studio (Phase 11)
- [x] Integrations: public API keys, webhooks, Slack/Calendar/HubSpot, visitor intel, workflows, multichannel consent (Phase 12)
- [x] Billing: plans, entitlements, idempotent Stripe webhooks, delinquency handling (Phase 13.1)
- [x] Domain inventory (manual provisioning, honest status) (Phase 13.2)
- [x] Data export + privacy requests + audit log tables (Phase 13.3)

## Launch checklist (owner actions before go-live)

- [ ] Provider OAuth approvals: Google OAuth verification, Microsoft publisher attestation
- [ ] Real test send/receive through Gmail + Microsoft + SMTP round-trip on production accounts
- [ ] Suppression and unsubscribe round-trip verified on production
- [ ] Tenant isolation spot-check between two production workspaces
- [ ] Stripe: live keys, live prices mapped, webhook endpoint registered, test payment + refund in live mode
- [ ] API key issued and exercised against production API
- [ ] Monitoring/alerts provisioned (uptime, error rate, bounce rate)
- [ ] Backup schedule verified; restore drill executed on staging
- [ ] Security: npm audit + sbt dependency scan clean; external penetration test scheduled
- [ ] Privacy/legal review: privacy policy, terms, DPA with providers, GLM data terms confirmed
- [ ] Performance: load test against production-size dataset (local ~8 jobs/s is not a production number)
- [ ] User onboarding flow walked end-to-end on production
- [ ] docs/ (SETUP, API, DEPLOYMENT, OPERATIONS, SECURITY) published to the docs site

## Known limitations (documented, not blockers if accepted)

- AI provider: Zhipu GLM via coding-plan endpoint; glm-5.3-flash requires account balance
- SMS/calling: interface only — no real provider integrated (consent plumbing ready)
- Managed domains: manual provisioning only — no registrar API integration
- Placement tests: require PLACEMENT_PROVIDER_KEY for real inbox placement data
- WorkerQueue timing spec: flakes under full-suite parallel load (green isolated)
- Realtime inbox updates: polling now; Supabase Realtime deferred to Phase 13 hardening
