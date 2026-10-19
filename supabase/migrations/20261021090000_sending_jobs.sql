-- ---------------------------------------------------------------------------
-- Migration 20261021090000 — Sending engine job infrastructure (Phase 5.1)
--
-- Responsibility split (ADR-0029):
--   * Scheduling & send execution: public.scheduled_actions — the ONE sending
--     queue, claimed by workers with FOR UPDATE SKIP LOCKED + leases.
--   * Event delivery: public.event_outbox — written in the same transaction
--     as state changes, dispatched at-least-once by the worker to future
--     webhook/stream sinks. No competing queue.
--   * public.job_queue (Phase 0) remains for platform-level jobs; it shares
--     the same claim pattern but never carries sends.
-- ---------------------------------------------------------------------------

create table public.scheduled_action (
  id              uuid primary key default gen_random_uuid(),
  workspace_id    uuid not null references public.workspace (id) on delete cascade,
  campaign_id     uuid references public.campaign (id) on delete cascade,
  enrollment_id   uuid references public.campaign_enrollment (id) on delete cascade,
  contact_id      uuid references public.contact (id) on delete cascade,
  email_account_id uuid references public.email_accounts (id) on delete set null,
  type            public.action_type not null,
  run_after       timestamptz not null default now(),
  status          public.action_status not null default 'pending',
  attempts        int not null default 0,
  max_attempts    int not null default 8,
  idempotency_key text not null,
  payload         jsonb not null default '{}'::jsonb,
  locked_at       timestamptz,
  locked_by       text,
  last_error      text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  constraint scheduled_action_attempts_range check (attempts >= 0 and max_attempts between 1 and 20)
);

create unique index scheduled_action_idempotency_idx
  on public.scheduled_action (idempotency_key);
create index scheduled_action_due_idx
  on public.scheduled_action (run_after) where status = 'pending';
create index scheduled_action_enrollment_idx on public.scheduled_action (enrollment_id);
create index scheduled_action_workspace_status_idx on public.scheduled_action (workspace_id, status);

create trigger scheduled_action_set_updated_at before update on public.scheduled_action
  for each row execute function public.set_updated_at();

create table public.job_attempt (
  id         uuid primary key default gen_random_uuid(),
  action_id  uuid not null references public.scheduled_action (id) on delete cascade,
  attempt    int not null,
  worker_id  text,
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  outcome    text check (outcome in ('done', 'retry', 'failed', 'lease_lost')),
  detail     jsonb not null default '{}'::jsonb
);

create index job_attempt_action_idx on public.job_attempt (action_id, attempt);

create table public.send_attempt (
  id               uuid primary key default gen_random_uuid(),
  workspace_id     uuid not null references public.workspace (id) on delete cascade,
  action_id        uuid references public.scheduled_action (id) on delete cascade,
  email_account_id uuid references public.email_accounts (id) on delete set null,
  provider         text,
  provider_message_id text,
  status           text not null check (status in
                     ('accepted', 'rejected', 'rate_limited', 'authentication_failed',
                      'permanently_failed', 'outcome_unknown')),
  provider_status_code text,
  detail           jsonb not null default '{}'::jsonb,
  created_at       timestamptz not null default now()
);

create index send_attempt_action_idx on public.send_attempt (action_id);
create index send_attempt_workspace_created_idx on public.send_attempt (workspace_id, created_at);

create table public.quota_reservation (
  id               uuid primary key default gen_random_uuid(),
  workspace_id     uuid not null references public.workspace (id) on delete cascade,
  email_account_id uuid not null references public.email_accounts (id) on delete cascade,
  day              date not null,
  reserved         int not null default 0 check (reserved >= 0),
  updated_at       timestamptz not null default now(),
  unique (email_account_id, day)
);

create table public.outbound_message (
  id                  uuid primary key default gen_random_uuid(),
  workspace_id        uuid not null references public.workspace (id) on delete cascade,
  action_id           uuid references public.scheduled_action (id) on delete set null,
  enrollment_id       uuid references public.campaign_enrollment (id) on delete set null,
  contact_id          uuid references public.contact (id) on delete set null,
  campaign_id         uuid references public.campaign (id) on delete set null,
  campaign_step_id    uuid references public.campaign_step (id) on delete set null,
  variant_key         text,
  email_account_id    uuid references public.email_accounts (id) on delete set null,
  subject             text not null default '',
  body_text           text not null default '',
  body_html           text not null default '',
  headers             jsonb not null default '{}'::jsonb,
  provider            text,
  provider_message_id text,
  provider_thread_id  text,
  internet_message_id text,
  status              public.message_status not null default 'queued',
  created_at          timestamptz not null default now()
);

create index outbound_message_workspace_idx on public.outbound_message (workspace_id, created_at);
create unique index outbound_message_action_idx on public.outbound_message (action_id)
  where action_id is not null;

create table public.event_outbox (
  id             uuid primary key default gen_random_uuid(),
  workspace_id   uuid references public.workspace (id) on delete cascade,
  aggregate_type text not null,
  aggregate_id   text not null,
  event_type     text not null,
  payload        jsonb not null default '{}'::jsonb,
  created_at     timestamptz not null default now(),
  delivered_at   timestamptz
);

create index event_outbox_pending_idx on public.event_outbox (created_at)
  where delivered_at is null;

alter table public.scheduled_action enable row level security;
alter table public.outbound_message enable row level security;
create policy scheduled_action_select_for_members on public.scheduled_action
  for select using (public.is_workspace_member(workspace_id));
create policy outbound_message_select_for_members on public.outbound_message
  for select using (public.is_workspace_member(workspace_id));
