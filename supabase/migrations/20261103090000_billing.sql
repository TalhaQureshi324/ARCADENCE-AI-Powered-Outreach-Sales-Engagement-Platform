-- ---------------------------------------------------------------------------
-- Migration 20261103090000 — Billing and entitlements (Phase 13.1)
-- Stripe identifiers are stored alongside internal entitlements; webhook
-- events are recorded durably so duplicate/delayed/out-of-order delivery is
-- idempotent. Payment failure downgrades entitlements but NEVER deletes
-- customer data.
-- ---------------------------------------------------------------------------

create table public.billing_customers (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  stripe_customer_id text not null unique,
  created_at    timestamptz not null default now(),
  unique (workspace_id)
);

create table public.subscriptions (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  stripe_customer_id text not null,
  stripe_subscription_id text not null unique,
  plan          text not null check (plan in ('free','starter','growth','agency')),
  status        text not null check (status in ('active','trialing','past_due','canceled','incomplete')),
  current_period_end timestamptz,
  cancel_at_period_end boolean not null default false,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index subscriptions_ws_idx on public.subscriptions (workspace_id);

create table public.billing_events (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid references public.workspace (id) on delete cascade,
  stripe_event_id text not null unique,
  event_type   text not null,
  payload      jsonb not null default '{}'::jsonb,
  processed_at timestamptz not null default now()
);

create table public.entitlements (
  workspace_id  uuid primary key references public.workspace (id) on delete cascade,
  plan          text not null default 'free' check (plan in ('free','starter','growth','agency')),
  max_mailboxes int  not null default 1,
  max_users     int  not null default 2,
  max_active_leads bigint not null default 1000,
  monthly_send_cap bigint not null default 1000,
  monthly_ai_tokens bigint not null default 200000,
  monthly_enrichment_credits int not null default 50,
  agency_workspaces_allowed boolean not null default false,
  api_requests_per_minute int not null default 60,
  updated_at    timestamptz not null default now()
);

alter table public.billing_customers enable row level security;
alter table public.subscriptions enable row level security;
alter table public.billing_events enable row level security;
alter table public.entitlements enable row level security;
create policy billing_customers_select_for_members on public.billing_customers
  for select using (public.is_workspace_member(workspace_id));
create policy subscriptions_select_for_members on public.subscriptions
  for select using (public.is_workspace_member(workspace_id));
create policy billing_events_select_for_members on public.billing_events
  for select using (public.is_workspace_member(workspace_id));
create policy entitlements_select_for_members on public.entitlements
  for select using (public.is_workspace_member(workspace_id));
