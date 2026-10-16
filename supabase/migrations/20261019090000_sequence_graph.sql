-- ---------------------------------------------------------------------------
-- Migration 20261019090000 — Sequence graph (Phase 4.3)
-- Branch/task/add-tag step types, client-stable step keys, explicit edges,
-- reply-triggered stop flag, business-day waits.
-- ---------------------------------------------------------------------------

alter type public.step_type add value if not exists 'branch';
alter type public.step_type add value if not exists 'task';
alter type public.step_type add value if not exists 'add_tag';

-- Stable identity across editor saves: the UI keeps these keys, the server
-- preserves the step UUID (and any enrollment references) when a key repeats.
alter table public.campaign_step add column client_key text;
create unique index campaign_step_client_key_idx on public.campaign_step (campaign_id, client_key)
  where client_key is not null;

-- Branch steps: a single whitelisted condition evaluated per contact.
alter table public.campaign_step add column condition jsonb;
-- add_tag steps:
alter table public.campaign_step add column tag_id uuid references public.tag (id) on delete set null;
-- task steps: manual task due N days after the step is entered.
alter table public.campaign_step add column task_due_days int check (task_due_days between 0 and 365);
-- wait steps can skip weekends (next business day) when scheduling.
alter table public.campaign_step add column business_days boolean not null default false;

alter table public.campaign add column stop_on_reply boolean not null default true;

-- Graph edges. branch_key is set on the two outgoing edges of a branch step
-- ('yes' / 'no'); linear connections leave it null.
create table public.campaign_edge (
  id           uuid primary key default gen_random_uuid(),
  campaign_id  uuid not null references public.campaign (id) on delete cascade,
  from_step_id uuid not null references public.campaign_step (id) on delete cascade,
  to_step_id   uuid not null references public.campaign_step (id) on delete cascade,
  branch_key   text,
  created_at   timestamptz not null default now(),
  unique (from_step_id, branch_key),
  check (from_step_id <> to_step_id)
);

create index campaign_edge_to_idx on public.campaign_edge (to_step_id);

alter table public.campaign_edge enable row level security;
create policy campaign_edge_select_for_members on public.campaign_edge
  for select using (
    exists (select 1 from public.campaign c where c.id = campaign_id and public.is_workspace_member(c.workspace_id))
  );
