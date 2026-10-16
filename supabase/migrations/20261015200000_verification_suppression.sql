-- ---------------------------------------------------------------------------
-- Migration 20261015200000 — Verification & suppression (Phase 3.3)
--
-- suppression: workspace-scoped do-not-email registry (per DATABASE.md §6.3).
-- Reuses the suppression_reason / suppression_source enums from Phase 0.
-- Contact verification lives on contact (verification_status/_checked_at/
-- _provider) — populated by the verification provider abstraction.
-- ---------------------------------------------------------------------------

create table public.suppression (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  email        text not null check (char_length(email) between 3 and 320),
  contact_id   uuid references public.contact (id) on delete set null,
  reason       public.suppression_reason not null,
  source       public.suppression_source not null default 'manual',
  created_by   uuid,
  created_at   timestamptz not null default now(),
  unique (workspace_id, email)
);
create index suppression_workspace_reason_idx on public.suppression (workspace_id, reason);
create index suppression_contact_idx on public.suppression (contact_id);

alter table public.suppression enable row level security;
create policy suppression_select_for_members on public.suppression
  for select using (public.is_workspace_member(workspace_id));
