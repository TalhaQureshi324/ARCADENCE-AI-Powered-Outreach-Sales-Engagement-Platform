-- ---------------------------------------------------------------------------
-- Migration 20261023090000 — Unified inbox: inbound sync (Phase 6.1)
-- ---------------------------------------------------------------------------

alter table public.outbound_message add column thread_id uuid;

create table public.email_threads (
  id               uuid primary key default gen_random_uuid(),
  workspace_id     uuid not null references public.workspace (id) on delete cascade,
  email_account_id uuid not null references public.email_accounts (id) on delete cascade,
  contact_id       uuid references public.contact (id) on delete set null,
  campaign_id      uuid references public.campaign (id) on delete set null,
  enrollment_id    uuid references public.campaign_enrollment (id) on delete set null,
  provider_thread_id text,
  subject          text not null default '',
  status           text not null default 'open' check (status in ('open','archived','done')),
  classification   text not null default 'unknown' check (classification in
                     ('unknown','interested','not_interested','question','meeting_requested',
                      'unsubscribe','out_of_office','automated','bounce')),
  unread           boolean not null default true,
  assigned_to      uuid,                       -- = auth.users.id (FK added conditionally below)
  last_message_at  timestamptz not null default now(),
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

create unique index email_threads_provider_thread_idx on public.email_threads (email_account_id, provider_thread_id)
  where provider_thread_id is not null;
create index email_threads_workspace_idx on public.email_threads (workspace_id, last_message_at desc);
create index email_threads_contact_idx on public.email_threads (contact_id);

create trigger email_threads_set_updated_at before update on public.email_threads
  for each row execute function public.set_updated_at();

create table public.email_messages (
  id                  uuid primary key default gen_random_uuid(),
  workspace_id        uuid not null references public.workspace (id) on delete cascade,
  thread_id           uuid not null references public.email_threads (id) on delete cascade,
  direction           text not null check (direction in ('inbound','outbound')),
  from_address        text not null default '',
  to_addresses        jsonb not null default '[]'::jsonb,
  subject             text not null default '',
  body_text           text not null default '',
  body_html           text not null default '',
  headers             jsonb not null default '{}'::jsonb,
  internet_message_id text,
  in_reply_to         text,
  provider_message_id text,
  provider_thread_id  text,
  classification      text,
  occurred_at         timestamptz not null default now(),
  created_at          timestamptz not null default now()
);

create index email_messages_thread_idx on public.email_messages (thread_id, occurred_at);
create index email_messages_internet_idx on public.email_messages (internet_message_id)
  where internet_message_id is not null;

create table public.provider_message_refs (
  id                  uuid primary key default gen_random_uuid(),
  workspace_id        uuid not null references public.workspace (id) on delete cascade,
  email_account_id    uuid not null references public.email_accounts (id) on delete cascade,
  provider_message_id text not null,
  message_id          uuid not null references public.email_messages (id) on delete cascade,
  created_at          timestamptz not null default now(),
  unique (email_account_id, provider_message_id)
);

create table public.mailbox_sync_cursors (
  email_account_id uuid primary key references public.email_accounts (id) on delete cascade,
  cursor           text,
  last_synced_at   timestamptz,
  last_error       text,
  status           text not null default 'idle' check (status in ('idle','syncing','error'))
);

create table public.sync_jobs (
  id               uuid primary key default gen_random_uuid(),
  workspace_id     uuid not null references public.workspace (id) on delete cascade,
  email_account_id uuid not null references public.email_accounts (id) on delete cascade,
  status           text not null default 'running' check (status in ('running','done','failed')),
  messages_synced  int not null default 0,
  error            text,
  started_at       timestamptz not null default now(),
  finished_at      timestamptz
);

create table public.inbound_events (
  id                  uuid primary key default gen_random_uuid(),
  workspace_id        uuid not null references public.workspace (id) on delete cascade,
  email_account_id    uuid not null references public.email_accounts (id) on delete cascade,
  provider_message_id text not null,
  event_type          text not null,
  processed_at        timestamptz,
  unique (email_account_id, provider_message_id, event_type)
);

alter table public.email_threads enable row level security;
alter table public.email_messages enable row level security;
create policy email_threads_select_for_members on public.email_threads
  for select using (public.is_workspace_member(workspace_id));
create policy email_messages_select_for_members on public.email_messages
  for select using (public.is_workspace_member(workspace_id));

-- Conditional FK to Supabase auth (skipped on plain PostgreSQL in CI)
do $$
begin
  if exists (select 1 from information_schema.schemata where schema_name = 'auth') then
    alter table public.email_threads
      add constraint email_threads_assigned_to_fkey foreign key (assigned_to)
      references auth.users (id) on delete set null;
  end if;
end
$$;
