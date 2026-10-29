-- ---------------------------------------------------------------------------
-- Migration 20261029090000 — AI infrastructure (Phase 11.1)
-- Model gateway execution records, agent configs, prompt templates, usage
-- accounting, approvals and tool invocations. AI never bypasses business
-- rules: everything here is an audit surface over deterministic services.
-- Workspace budgets live on public.workspace (monthly token/spend caps);
-- usage rows carry estimated cost in micro-USD.
-- ---------------------------------------------------------------------------

alter table public.workspace
  add column if not exists ai_monthly_token_limit bigint not null default 200000,
  add column if not exists ai_monthly_spend_micros bigint not null default 50000000;

create table public.ai_agents (
  id                uuid primary key default gen_random_uuid(),
  workspace_id      uuid not null references public.workspace (id) on delete cascade,
  name              text not null check (char_length(name) between 1 and 120),
  kind              text not null check (kind in ('writer','researcher','reply_agent','sales_agent')),
  provider          text not null default 'openai-compatible',
  model             text not null default 'gpt-4o-mini',
  enabled           boolean not null default true,
  requires_approval boolean not null default true,
  max_credits       int not null default 50,
  config            jsonb not null default '{}'::jsonb,
  created_by        uuid,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);
create unique index ai_agents_ws_name_idx on public.ai_agents (workspace_id, lower(name));
create index ai_agents_ws_idx on public.ai_agents (workspace_id, created_at desc);

create table public.ai_templates (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  name         text not null check (char_length(name) between 1 and 120),
  kind         text not null default 'user' check (kind in ('system','user')),
  body         text not null,
  version      int not null default 1,
  created_by   uuid,
  created_at   timestamptz not null default now()
);
create unique index ai_templates_ws_name_ver_idx on public.ai_templates (workspace_id, lower(name), version);
create index ai_templates_ws_idx on public.ai_templates (workspace_id, name, version desc);

create table public.ai_runs (
  id             uuid primary key default gen_random_uuid(),
  workspace_id   uuid not null references public.workspace (id) on delete cascade,
  agent_id       uuid references public.ai_agents (id) on delete set null,
  kind           text not null check (kind in ('writer','research','reply','sales_agent','evaluation','completion')),
  status         text not null default 'pending' check (status in ('pending','running','awaiting_approval','completed','failed','cancelled')),
  provider       text not null,
  model          text not null,
  input_digest   text,
  input_preview  text,
  output_preview text,
  error          text,
  input_tokens   bigint not null default 0,
  output_tokens  bigint not null default 0,
  cost_micros    bigint not null default 0,
  requested_by   uuid,
  created_at     timestamptz not null default now(),
  finished_at    timestamptz
);
create index ai_runs_ws_idx on public.ai_runs (workspace_id, created_at desc);

create table public.ai_run_steps (
  id               uuid primary key default gen_random_uuid(),
  run_id           uuid not null references public.ai_runs (id) on delete cascade,
  workspace_id     uuid not null references public.workspace (id) on delete cascade,
  step_no          int not null,
  tool             text,
  status           text not null default 'ok' check (status in ('ok','failed','skipped')),
  request_preview  text,
  response_preview text,
  duration_ms      int,
  created_at       timestamptz not null default now()
);
create index ai_run_steps_run_idx on public.ai_run_steps (run_id, step_no);

create table public.ai_messages (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  run_id        uuid references public.ai_runs (id) on delete set null,
  contact_id    uuid references public.contact (id) on delete cascade,
  thread_id     uuid,
  draft_subject text,
  draft_body    text,
  final_subject text,
  final_body    text,
  status        text not null default 'draft' check (status in ('draft','approved','edited','rejected','sent','discarded')),
  ai_confidence int check (ai_confidence between 0 and 100),
  evidence      jsonb not null default '{}'::jsonb,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index ai_messages_ws_idx on public.ai_messages (workspace_id, created_at desc);

create table public.ai_usage (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  run_id        uuid references public.ai_runs (id) on delete set null,
  provider      text not null,
  model         text not null,
  input_tokens  bigint not null default 0,
  output_tokens bigint not null default 0,
  cost_micros   bigint not null default 0,
  created_at    timestamptz not null default now()
);
create index ai_usage_ws_idx on public.ai_usage (workspace_id, created_at desc);

create table public.ai_approvals (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  run_id       uuid references public.ai_runs (id) on delete cascade,
  action       text not null,
  payload      jsonb not null default '{}'::jsonb,
  status       text not null default 'pending' check (status in ('pending','approved','rejected','expired')),
  requested_by uuid,
  decided_by   uuid,
  decided_at   timestamptz,
  created_at   timestamptz not null default now()
);
create index ai_approvals_ws_idx on public.ai_approvals (workspace_id, status, created_at desc);

create table public.ai_tool_invocations (
  id             uuid primary key default gen_random_uuid(),
  workspace_id   uuid not null references public.workspace (id) on delete cascade,
  run_id         uuid references public.ai_runs (id) on delete cascade,
  tool           text not null,
  args           jsonb not null default '{}'::jsonb,
  result_summary jsonb not null default '{}'::jsonb,
  status         text not null default 'pending' check (status in ('pending','approved','running','completed','failed','rejected')),
  approved_by    uuid,
  error          text,
  created_at     timestamptz not null default now(),
  finished_at    timestamptz
);
create index ai_tool_invocations_ws_idx on public.ai_tool_invocations (workspace_id, created_at desc);

-- updated_at triggers
create trigger ai_agents_set_updated_at before update on public.ai_agents
  for each row execute function public.set_updated_at();
create trigger ai_messages_set_updated_at before update on public.ai_messages
  for each row execute function public.set_updated_at();

-- RLS: member-select everywhere (writes go through the Scala API, which
-- enforces RBAC independently).
alter table public.ai_agents enable row level security;
alter table public.ai_templates enable row level security;
alter table public.ai_runs enable row level security;
alter table public.ai_run_steps enable row level security;
alter table public.ai_messages enable row level security;
alter table public.ai_usage enable row level security;
alter table public.ai_approvals enable row level security;
alter table public.ai_tool_invocations enable row level security;

create policy ai_agents_select_for_members on public.ai_agents for select using (public.is_workspace_member(workspace_id));
create policy ai_templates_select_for_members on public.ai_templates for select using (public.is_workspace_member(workspace_id));
create policy ai_runs_select_for_members on public.ai_runs for select using (public.is_workspace_member(workspace_id));
create policy ai_run_steps_select_for_members on public.ai_run_steps for select using (public.is_workspace_member(workspace_id));
create policy ai_messages_select_for_members on public.ai_messages for select using (public.is_workspace_member(workspace_id));
create policy ai_usage_select_for_members on public.ai_usage for select using (public.is_workspace_member(workspace_id));
create policy ai_approvals_select_for_members on public.ai_approvals for select using (public.is_workspace_member(workspace_id));
create policy ai_tool_invocations_select_for_members on public.ai_tool_invocations for select using (public.is_workspace_member(workspace_id));
