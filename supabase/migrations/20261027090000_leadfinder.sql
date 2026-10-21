-- ---------------------------------------------------------------------------
-- Migration 20261027090000 — Lead Finder / enrichment / credits (Phase 10)
-- Licensed-data pipeline: providers are integrated only under agreement;
-- sandbox data is labeled and never presented as live leads.
-- ---------------------------------------------------------------------------

create table public.provider_configs (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  provider     text not null check (provider in ('sandbox','apollo','zoominfo','snov')),
  enabled      boolean not null default true,
  config       jsonb not null default '{}'::jsonb,   -- credentials live in env/secret store, never here
  created_at   timestamptz not null default now(),
  unique (workspace_id, provider)
);

create table public.search_requests (
  id              uuid primary key default gen_random_uuid(),
  workspace_id    uuid not null references public.workspace (id) on delete cascade,
  requested_by    uuid,
  provider        text not null,
  filters         jsonb not null default '{}'::jsonb,
  status          text not null default 'pending' check (status in ('pending','running','done','failed','cancelled')),
  estimated_credits int not null default 0,
  credits_charged int not null default 0,
  results_count   int not null default 0,
  error           text,
  created_at      timestamptz not null default now(),
  finished_at     timestamptz
);
create index search_requests_ws_idx on public.search_requests (workspace_id, created_at desc);

create table public.discovered_contacts (
  id               uuid primary key default gen_random_uuid(),
  workspace_id     uuid not null references public.workspace (id) on delete cascade,
  search_request_id uuid not null references public.search_requests (id) on delete cascade,
  provider         text not null,
  provider_contact_id text not null,
  email            text,
  email_verification text not null default 'unknown' check (email_verification in ('verified','risky','unverified','unknown')),
  first_name       text, last_name text, job_title text, company_name text,
  company_domain   text, industry text, region text, company_size text,
  confidence       int not null default 50 check (confidence between 0 and 100),
  permitted_use    text not null default 'licensed' check (permitted_use in ('licensed','sandbox_only')),
  retrieved_at     timestamptz not null default now(),
  added_to_list_id uuid references public.lead_list (id) on delete set null,
  created_at       timestamptz not null default now(),
  unique (provider, provider_contact_id)
);
create index discovered_ws_idx on public.discovered_contacts (workspace_id, created_at desc);

create table public.provenance_records (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  discovered_contact_id uuid references public.discovered_contacts (id) on delete cascade,
  contact_id    uuid references public.contact (id) on delete cascade,
  field         text not null,
  source        text not null,
  value         text,
  confidence    int not null default 50,
  retrieved_at  timestamptz not null default now()
);
create index provenance_contact_idx on public.provenance_records (contact_id, retrieved_at desc);

create table public.provider_usage (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  provider     text not null,
  operation    text not null,
  units        int not null default 1,
  cost_cents   int not null default 0,
  created_at   timestamptz not null default now()
);

create table public.credit_transactions (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  delta         int not null,
  kind          text not null check (kind in ('reserve','charge','refund','grant')),
  ref_id        uuid,
  idempotency_key text,
  reason        text not null default '',
  created_at    timestamptz not null default now()
);
create unique index credit_tx_idem_idx on public.credit_transactions (idempotency_key)
  where idempotency_key is not null;
create index credit_tx_ws_idx on public.credit_transactions (workspace_id, created_at desc);

alter table public.provider_configs enable row level security;
alter table public.search_requests enable row level security;
alter table public.discovered_contacts enable row level security;
alter table public.provenance_records enable row level security;
alter table public.provider_usage enable row level security;
alter table public.credit_transactions enable row level security;

create policy provider_configs_select_for_members on public.provider_configs for select using (public.is_workspace_member(workspace_id));
create policy search_requests_select_for_members on public.search_requests for select using (public.is_workspace_member(workspace_id));
create policy discovered_select_for_members on public.discovered_contacts for select using (public.is_workspace_member(workspace_id));
create policy provenance_select_for_members on public.provenance_records for select using (public.is_workspace_member(workspace_id));
create policy provider_usage_select_for_members on public.provider_usage for select using (public.is_workspace_member(workspace_id));
create policy credit_tx_select_for_members on public.credit_transactions for select using (public.is_workspace_member(workspace_id));
