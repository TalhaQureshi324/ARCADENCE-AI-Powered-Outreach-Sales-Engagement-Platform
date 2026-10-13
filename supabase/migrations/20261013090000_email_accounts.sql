-- ---------------------------------------------------------------------------
-- Migration 20261013090000 — Email account architecture (Phase 2.1)
--
-- Tables: account_group, email_accounts, account_credentials, sender_domains,
-- account_health_events, provider_sync_state (docs/DATABASE.md §6.2).
--
-- Naming note: the Phase 0 provisional design called these email_connection /
-- email_credential; the Phase 2 plan (product owner) names them
-- email_accounts / account_credentials. See ADR-0019.
--
-- Account states replace the provisional connection_status enum values with
-- the Phase 2 plan's states: connected, reconnect_required, rate_limited,
-- suspended, error, disconnected.
-- ---------------------------------------------------------------------------

-- The provisional enum is unused by any table so far — replace its values.
drop type if exists public.connection_status;
create type public.connection_status as enum (
  'connected',
  'reconnect_required',
  'rate_limited',
  'suspended',
  'error',
  'disconnected'
);

-- Health event kinds written by the API/worker (never by clients).
create type public.account_health_event_type as enum (
  'connected',
  'disconnected',
  'reconnect_required',
  'token_refreshed',
  'rate_limited',
  'sync_error',
  'send_error',
  'paused',
  'resumed',
  'test_succeeded',
  'test_failed'
);

-- ---------------------------------------------------------------------------
-- account_group — operator-defined grouping of email accounts
-- ---------------------------------------------------------------------------
create table public.account_group (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  name         text not null check (char_length(name) between 1 and 80),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create unique index account_group_workspace_name_idx
  on public.account_group (workspace_id, lower(name));
create index account_group_workspace_idx on public.account_group (workspace_id);

-- ---------------------------------------------------------------------------
-- email_accounts — a connected sending/receiving identity (Gmail, Microsoft
-- 365 or SMTP/IMAP). Credentials never live here — see account_credentials.
-- ---------------------------------------------------------------------------
create table public.email_accounts (
  id               uuid primary key default gen_random_uuid(),
  workspace_id     uuid not null references public.workspace (id) on delete cascade,
  account_group_id uuid references public.account_group (id) on delete set null,
  provider         public.email_provider not null,
  email_address    text not null check (char_length(email_address) between 3 and 320),
  display_name     text check (char_length(display_name) <= 120),
  status           public.connection_status not null default 'disconnected',
  status_message   text,
  daily_send_cap   int check (daily_send_cap between 1 and 10000),
  signature        text,
  is_default       boolean not null default false,
  last_synced_at   timestamptz,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);
create unique index email_accounts_workspace_email_idx
  on public.email_accounts (workspace_id, lower(email_address));
create index email_accounts_workspace_status_idx on public.email_accounts (workspace_id, status);
create index email_accounts_group_idx on public.email_accounts (account_group_id);

create trigger email_accounts_set_updated_at before update on public.email_accounts
  for each row execute function public.set_updated_at();
create trigger account_group_set_updated_at before update on public.account_group
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- account_credentials — encrypted provider secrets, 1:1 with an account.
-- Plaintext secrets NEVER reach this table, the API surface, or logs:
-- the Scala service encrypts with AES-256-GCM (key versioned via
-- EMAIL_ENCRYPTION_KEY; see ADR-0020).
-- RLS is enabled with NO policies — PostgREST can neither read nor write;
-- only the trusted service connection (bypasses RLS) touches this table.
-- ---------------------------------------------------------------------------
create table public.account_credentials (
  id                    uuid primary key default gen_random_uuid(),
  email_account_id      uuid not null unique references public.email_accounts (id) on delete cascade,
  kind                  text not null check (kind in ('oauth2', 'password')),
  encrypted_blob        bytea not null,
  encryption_key_version int not null default 1,
  expires_at            timestamptz,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);

alter table public.account_credentials enable row level security;
-- Revoke PostgREST access when the Supabase roles exist (plain PostgreSQL
-- in CI has no anon/authenticated roles — keep the migration portable).
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on public.account_credentials from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on public.account_credentials from authenticated';
  end if;
end
$$;

create trigger account_credentials_set_updated_at before update on public.account_credentials
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- sender_domains — custom sending domains (SPF/DKIM/DMARC lifecycle arrives
-- with deliverability phases; this is the registry they hang off)
-- ---------------------------------------------------------------------------
create table public.sender_domains (
  id                  uuid primary key default gen_random_uuid(),
  workspace_id        uuid not null references public.workspace (id) on delete cascade,
  domain              text not null check (char_length(domain) between 3 and 253),
  verification_status text not null default 'unverified'
    check (verification_status in ('unverified', 'pending', 'verified', 'failed')),
  dns_records         jsonb,
  verified_at         timestamptz,
  last_checked_at     timestamptz,
  created_at          timestamptz not null default now()
);
create unique index sender_domains_workspace_domain_idx
  on public.sender_domains (workspace_id, lower(domain));
create index sender_domains_workspace_idx on public.sender_domains (workspace_id);

-- ---------------------------------------------------------------------------
-- account_health_events — append-only account lifecycle/health trail that
-- feeds the dashboard health indicators
-- ---------------------------------------------------------------------------
create table public.account_health_events (
  id               uuid primary key default gen_random_uuid(),
  workspace_id     uuid not null references public.workspace (id) on delete cascade,
  email_account_id uuid not null references public.email_accounts (id) on delete cascade,
  event_type       public.account_health_event_type not null,
  message          text,
  details          jsonb,
  occurred_at      timestamptz not null default now()
);
create index account_health_events_account_idx
  on public.account_health_events (email_account_id, occurred_at desc);

-- ---------------------------------------------------------------------------
-- provider_sync_state — durable per-account/per-direction sync cursors
-- ---------------------------------------------------------------------------
create table public.provider_sync_state (
  id               uuid primary key default gen_random_uuid(),
  email_account_id uuid not null references public.email_accounts (id) on delete cascade,
  direction        text not null default 'inbox' check (direction in ('inbox', 'sending')),
  sync_status      text not null default 'idle' check (sync_status in ('idle', 'syncing', 'error')),
  cursor           jsonb not null default '{}'::jsonb,
  last_error       text,
  last_synced_at   timestamptz,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  unique (email_account_id, direction)
);

create trigger provider_sync_state_set_updated_at before update on public.provider_sync_state
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- Row Level Security — tenant isolation mirrors the tenancy tables. Writes
-- flow exclusively through the audited Scala API (service connection); the
-- read policies below are defense in depth for PostgREST consumers.
-- ---------------------------------------------------------------------------
alter table public.account_group enable row level security;
create policy account_group_select_for_members on public.account_group
  for select using (public.is_workspace_member(workspace_id));

alter table public.email_accounts enable row level security;
create policy email_accounts_select_for_members on public.email_accounts
  for select using (public.is_workspace_member(workspace_id));

alter table public.sender_domains enable row level security;
create policy sender_domains_select_for_members on public.sender_domains
  for select using (public.is_workspace_member(workspace_id));

alter table public.account_health_events enable row level security;
create policy account_health_events_select_for_members on public.account_health_events
  for select using (public.is_workspace_member(workspace_id));

alter table public.provider_sync_state enable row level security;
create policy provider_sync_state_select_for_members on public.provider_sync_state
  for select using (
    public.is_workspace_member(
      (select e.workspace_id from public.email_accounts e where e.id = email_account_id)
    )
  );
