-- ---------------------------------------------------------------------------
-- Migration 20261101090000 — Public API keys (Phase 12.1)
-- Workspace-scoped API keys: the full secret is shown ONCE at creation;
-- only a sha256 hash + display prefix are stored. Granular scopes gate
-- read/write on resource families; expiry and revocation are first-class.
-- ---------------------------------------------------------------------------

create table public.api_keys (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  name         text not null check (char_length(name) between 1 and 120),
  key_prefix   text not null check (char_length(key_prefix) between 8 and 24),
  key_hash     text not null unique,
  scopes       text[] not null default '{leads:read,campaigns:read}'::text[],
  last_used_at timestamptz,
  expires_at   timestamptz,
  revoked_at   timestamptz,
  created_by   uuid,
  created_at   timestamptz not null default now()
);
create index api_keys_ws_idx on public.api_keys (workspace_id, created_at desc);

alter table public.api_keys enable row level security;
create policy api_keys_select_for_members on public.api_keys
  for select using (public.is_workspace_member(workspace_id));

-- Webhook subscriptions (Phase 12.1): per-workspace endpoint registrations
-- for documented domain events; deliveries are attempted with signed
-- payloads, retries and dead-lettering.
create table public.webhook_subscriptions (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  url          text not null check (url ~ '^https?://'),
  events       text[] not null,
  secret       text not null,
  active       boolean not null default true,
  created_by   uuid,
  created_at   timestamptz not null default now()
);
create index webhook_subs_ws_idx on public.webhook_subscriptions (workspace_id);

create table public.webhook_deliveries (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  subscription_id uuid not null references public.webhook_subscriptions (id) on delete cascade,
  event        text not null,
  payload      jsonb not null default '{}'::jsonb,
  status       text not null default 'pending' check (status in ('pending','delivered','failed','dead')),
  attempts     int not null default 0,
  response_code int,
  last_error   text,
  next_attempt_at timestamptz,
  created_at   timestamptz not null default now(),
  delivered_at timestamptz
);
create index webhook_deliveries_status_idx on public.webhook_deliveries (status, next_attempt_at);
create index webhook_deliveries_ws_idx on public.webhook_deliveries (workspace_id, created_at desc);

alter table public.webhook_subscriptions enable row level security;
alter table public.webhook_deliveries enable row level security;
create policy webhook_subs_select_for_members on public.webhook_subscriptions
  for select using (public.is_workspace_member(workspace_id));
create policy webhook_deliveries_select_for_members on public.webhook_deliveries
  for select using (public.is_workspace_member(workspace_id));
