# Arcadence — Database

> Status: **No schema exists yet.** First migrations land with Phase 0/1. This file records binding conventions so every future migration is consistent. Schema overview tables are added as phases ship.

**Last updated:** 2026-10-09

---

## 1. Platform & access

- Supabase PostgreSQL (hosted). Direct or session-pooled connections from the Scala services; final pooling mode (Supavisor session vs transaction) is decided at Phase 0 deployment wiring and recorded in `docs/DECISIONS.md`.
- Schema changes happen **only** through version-controlled SQL migrations. No console changes, no undocumented production-only changes (`AGENTS.md` §5).

## 2. Migration conventions

- Location: `supabase/migrations/`
- Naming: `<UTC-timestamp>_<snake_case_name>.sql` — e.g. `20261009120000_create_workspaces.sql` (Supabase CLI-compatible ordering).
- **Forward-only.** Destructive migrations (drop table/column, type narrowing, mass updates) require explicit product-owner approval and are called out in the implementation report.
- Every migration is documented in this file's schema overview and in `docs/HANDOFF.md`.
- Migrations are applied in a controlled step (Supabase CLI / CI), never ad-hoc from laptops against production.

## 3. Table conventions (binding)

| Concern | Convention |
| --- | --- |
| Primary key | `id uuid primary key default gen_random_uuid()` (uuidv7 evaluated when available in-toolchain) |
| Tenancy | Every tenant-owned table has `workspace_id uuid not null references public.workspace (id)` — **first-class column, indexed** |
| Timestamps | `created_at timestamptz not null default now()`, `updated_at timestamptz not null default now()` + trigger to maintain `updated_at` |
| Soft delete | `deleted_at timestamptz null` where audit/history matters; hard delete only where privacy requires |
| RLS | **Enabled on every tenant table**, policies keyed to workspace membership via JWT claims; service-role exceptions enumerated in `docs/SECURITY.md` |
| Indexes | FK columns always indexed; composite indexes led by `workspace_id` for tenant-scoped queries; indexes justified in migration comments |
| Constraints | Domain invariants live in the schema (CHECK/FK/UNIQUE) wherever possible, not only in Scala |
| Naming | `snake_case`; tables singular (`workspace`, `campaign`); boolean columns `is_/has_` prefixed |
| Enumerations | Postgres enums for stable closed sets; lookup tables for extensible sets |

## 4. Planned core schema areas

Added here as they are actually created — nothing below exists yet:

- ⬜ `workspace`, `workspace_member`, `invitation` — Phase 1
- ⬜ `email_connection` (OAuth/SMTP accounts, encrypted credentials), `suppression`, `consent` — Phase 2
- ⬜ `lead`, `lead_list`, `custom_field` — Phase 3
- ⬜ `campaign`, `sequence`, `sequence_step`, `prospect` (campaign membership) — Phase 4
- ⬜ `email_send` (durable send records + idempotency keys), `email_event` (delivery/open/click/bounce) — Phase 5
- ⬜ `conversation`, `message`, `sync_state` — Phase 6
- ⬜ `crm_account`, `contact`, `deal`, `pipeline`, `activity` — Phase 7
- ⬜ `audit_log`, job/outbox tables — Phase 0/1 platform foundations

## 5. Data quality rules

- Every FK enforced; no orphaned tenant rows.
- `ON DELETE` behavior chosen explicitly per relationship (restrict vs cascade) and documented inline.
- Case-insensitive uniqueness where semantically required (e.g. emails within a workspace) via expression indexes.

## 6. Verification checklist per migration

1. `up` applied to a fresh local database without errors; `down`/forward-fix strategy stated.
2. RLS enabled + policies present for any new tenant table.
3. Indexes match the query patterns of the subphase that needs them.
4. Cross-tenant isolation test added/updated and passing.
5. This file and `docs/HANDOFF.md` updated.
