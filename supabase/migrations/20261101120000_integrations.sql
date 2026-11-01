-- ---------------------------------------------------------------------------
-- Migration 20261101120000 — External integrations (Phase 12.2)
-- Per-workspace connector registrations (Slack, calendars, CRM pushes,
-- generic outgoing webhooks). Secrets live in the config jsonb column —
-- same git-ignored/env discipline applies to tokens obtained via OAuth
-- through the Phase 2 provider registry.
-- ---------------------------------------------------------------------------

create table public.integrations (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  kind         text not null check (kind in ('slack','google_calendar','outlook_calendar','hubspot','webhook_out')),
  status       text not null default 'connected' check (status in ('connected','disconnected','error')),
  config       jsonb not null default '{}'::jsonb,
  last_error   text,
  last_synced_at timestamptz,
  created_by   uuid,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  unique (workspace_id, kind)
);
create index integrations_ws_idx on public.integrations (workspace_id, kind);

create trigger integrations_set_updated_at before update on public.integrations
  for each row execute function public.set_updated_at();

alter table public.integrations enable row level security;
create policy integrations_select_for_members on public.integrations
  for select using (public.is_workspace_member(workspace_id));
