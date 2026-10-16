-- ---------------------------------------------------------------------------
-- Migration 20261015210000 — Segments + full-text search (Phase 3.4)
-- ---------------------------------------------------------------------------

create table public.segment (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  name         text not null check (char_length(name) between 1 and 120),
  filters      jsonb not null,
  created_by   uuid,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create unique index segment_workspace_name_idx on public.segment (workspace_id, lower(name));
create index segment_workspace_idx on public.segment (workspace_id);

create trigger segment_set_updated_at before update on public.segment
  for each row execute function public.set_updated_at();

-- trigram search for contact email/name at database scale
create extension if not exists pg_trgm;
create index contact_email_trgm_idx on public.contact using gin (email gin_trgm_ops);
create index contact_name_trgm_idx on public.contact
  using gin ((coalesce(first_name, '') || ' ' || coalesce(last_name, '')) gin_trgm_ops);

alter table public.segment enable row level security;
create policy segment_select_for_members on public.segment
  for select using (public.is_workspace_member(workspace_id));
