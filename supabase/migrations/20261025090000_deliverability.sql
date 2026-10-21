-- ---------------------------------------------------------------------------
-- Migration 20261025090000 — Deliverability Command Center (Phase 8)
-- ---------------------------------------------------------------------------

create table public.dns_check_results (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  domain       text not null,
  record_type  text not null check (record_type in ('spf','dkim','dmarc')),
  passed       boolean not null,
  findings     jsonb not null default '[]'::jsonb,
  checked_at   timestamptz not null default now()
);
create index dns_check_domain_idx on public.dns_check_results (workspace_id, domain, checked_at desc);

create table public.domain_health_history (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  domain       text not null,
  status       text not null check (status in ('pass','warn','fail')),
  reasons      jsonb not null default '[]'::jsonb,
  checked_at   timestamptz not null default now()
);
create index domain_health_domain_idx on public.domain_health_history (workspace_id, domain, checked_at desc);

create table public.account_health_snapshots (
  id               uuid primary key default gen_random_uuid(),
  workspace_id     uuid not null references public.workspace (id) on delete cascade,
  email_account_id uuid not null references public.email_accounts (id) on delete cascade,
  status           text not null check (status in ('healthy','warning','risk')),
  reasons          jsonb not null default '[]'::jsonb,
  window_days      int not null default 7,
  captured_at      timestamptz not null default now()
);
create index account_health_acct_idx on public.account_health_snapshots (email_account_id, captured_at desc);

create table public.placement_tests (
  id               uuid primary key default gen_random_uuid(),
  workspace_id     uuid not null references public.workspace (id) on delete cascade,
  email_account_id uuid not null references public.email_accounts (id) on delete cascade,
  requested_by     uuid,
  sample_size      int not null check (sample_size between 1 and 20),
  seed_addresses   jsonb not null default '[]'::jsonb,
  status           text not null default 'pending' check (status in ('pending','running','done','unavailable')),
  results          jsonb not null default '[]'::jsonb,
  status_reason    text,
  created_at       timestamptz not null default now(),
  completed_at     timestamptz
);
create index placement_tests_ws_idx on public.placement_tests (workspace_id, created_at desc);

alter table public.dns_check_results enable row level security;
alter table public.domain_health_history enable row level security;
alter table public.account_health_snapshots enable row level security;
alter table public.placement_tests enable row level security;

create policy dns_check_select_for_members on public.dns_check_results
  for select using (public.is_workspace_member(workspace_id));
create policy domain_health_select_for_members on public.domain_health_history
  for select using (public.is_workspace_member(workspace_id));
create policy account_health_select_for_members on public.account_health_snapshots
  for select using (public.is_workspace_member(workspace_id));
create policy placement_tests_select_for_members on public.placement_tests
  for select using (public.is_workspace_member(workspace_id));
