-- ---------------------------------------------------------------------------
-- Migration 20261102100000 — Workflow automation (Phase 12.4)
-- Deterministic automations: a trigger plus a validated graph of
-- conditions and actions. Execution is recorded step-by-step; dry runs
-- never mutate; loops are prevented at validation time.
-- ---------------------------------------------------------------------------

create table public.automations (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  name          text not null check (char_length(name) between 1 and 120),
  trigger_type  text not null check (trigger_type in (
                  'lead_imported','verification_completed','positive_reply',
                  'meeting_requested','crm_stage_changed','campaign_completed','sender_health_warning')),
  trigger_config jsonb not null default '{}'::jsonb,
  graph         jsonb not null default '{}'::jsonb,
  enabled       boolean not null default false,
  version       int not null default 1,
  created_by    uuid,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create unique index automations_ws_name_idx on public.automations (workspace_id, lower(name));

create table public.automation_runs (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  automation_id uuid not null references public.automations (id) on delete cascade,
  version       int not null,
  status        text not null default 'running' check (status in ('running','completed','failed','cancelled')),
  dry_run       boolean not null default false,
  context       jsonb not null default '{}'::jsonb,
  steps         jsonb not null default '[]'::jsonb,
  error         text,
  created_at    timestamptz not null default now(),
  finished_at   timestamptz
);
create index automation_runs_idx on public.automation_runs (automation_id, created_at desc);

create trigger automations_set_updated_at before update on public.automations
  for each row execute function public.set_updated_at();

alter table public.automations enable row level security;
alter table public.automation_runs enable row level security;
create policy automations_select_for_members on public.automations
  for select using (public.is_workspace_member(workspace_id));
create policy automation_runs_select_for_members on public.automation_runs
  for select using (public.is_workspace_member(workspace_id));
