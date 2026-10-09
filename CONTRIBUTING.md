# Contributing to Arcadence

Short version of [AGENTS.md](AGENTS.md) — read that file for the full contract.

## Workflow

1. **One subphase per change.** One prompt → one completed subphase → tests → push → implementation report.
2. Branch from `develop` using `feature/phase-NN-slug` (e.g. `feature/phase-01-workspace-rbac`).
3. Land changes via PR into `develop`. `develop` is promoted to `main` per completed phase, tagged (e.g. `phase-01`).

## Commits

Conventional commits, imperative mood:

```
feat(email): implement Gmail OAuth connection
fix(inbox): deduplicate concurrent IMAP syncs
docs(database): record Phase 5 schema
chore(ci): add Playwright job
```

## Definition of done

- [ ] Only the current subphase implemented — no drive-by refactors
- [ ] Formatting, build, and type checks pass
- [ ] Unit tests pass; integration tests where applicable; Playwright whenever UI behavior changed
- [ ] Schema changes via versioned migration + `docs/DATABASE.md` updated
- [ ] API changes → OpenAPI regenerated + `docs/API.md` updated
- [ ] `docs/PROJECT_STATUS.md` and `docs/HANDOFF.md` updated
- [ ] No secrets committed; no mock data posing as live data
- [ ] Cross-tenant isolation considered (new resources need isolation tests)
- [ ] Loading/empty/error/permission states present in UI work

## Security

Never commit credentials. Report vulnerabilities privately to the maintainer — do not open public issues for them.
