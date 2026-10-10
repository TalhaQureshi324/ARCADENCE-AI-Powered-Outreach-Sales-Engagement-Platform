-- Platform foundations: durable job queue, audit log, outbound webhook registry.
-- job_queue is service-role only (claimed by workers with SKIP LOCKED, ADR-0005).

create table public.job_queue (
  id              uuid primary key default gen_random_uuid(),
  queue           text not null default 'default',
  payload         jsonb not null default '{}'::jsonb,
  run_after       timestamptz not null default now(),
  status          public.action_status not null default 'pending',
  attempts        int not null default 0,
  max_attempts    int not null default 8,
  idempotency_key text,
  locked_at       timestamptz,
  locked_by       text,
  last_error      text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  constraint job_queue_attempts_range check (attempts >= 0 and max_attempts >= 1)
);
-- Dedupe for replay-safe enqueueing (provider notifications, timers).
create unique index job_queue_idempotency_key_idx
  on public.job_queue (idempotency_key)
  where idempotency_key is not null;
-- Hot path: due-job scan.
create index job_queue_due_idx
  on public.job_queue (run_after)
  where status = 'pending';
create index job_queue_queue_status_idx on public.job_queue (queue, status);
create trigger job_queue_set_updated_at before update on public.job_queue
  for each row execute function public.set_updated_at();
-- No RLS: jobs are written by API/workers via service role, never by browsers.

create table public.audit_log (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid references public.workspace (id) on delete cascade, -- null = platform-level
  actor        uuid,                                                    -- auth.users.id; null = system
  action       text not null check (action <> ''),
  target_type  text,
  target_id    text,
  metadata     jsonb not null default '{}'::jsonb,
  ip           inet,
  created_at   timestamptz not null default now()
);
create index audit_log_workspace_created_idx on public.audit_log (workspace_id, created_at desc);
create index audit_log_actor_created_idx on public.audit_log (actor, created_at);
-- Append-only: no updated_at, no updates/deletes via policies.
alter table public.audit_log enable row level security;
create policy audit_log_select_for_admins on public.audit_log
  for select using (
    public.has_workspace_role(workspace_id, ARRAY['owner','admin']::public.workspace_role[])
  );
-- insert service-role only

create table public.webhook_endpoint (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  url          text not null check (url ~ '^https://'),
  secret_enc   bytea not null,             -- envelope-encrypted by the server
  events       text[] not null default '{}',
  is_active    boolean not null default true,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create index webhook_endpoint_workspace_idx on public.webhook_endpoint (workspace_id);
create trigger webhook_endpoint_set_updated_at before update on public.webhook_endpoint
  for each row execute function public.set_updated_at();
alter table public.webhook_endpoint enable row level security;
create policy webhook_endpoint_select_for_admins on public.webhook_endpoint
  for select using (
    public.has_workspace_role(workspace_id, ARRAY['owner','admin']::public.workspace_role[])
  );
-- writes service-role only

create table public.webhook_delivery (
  id                  uuid primary key default gen_random_uuid(),
  workspace_id        uuid not null references public.workspace (id) on delete cascade,
  webhook_endpoint_id uuid not null references public.webhook_endpoint (id) on delete cascade,
  event_type          text not null,
  payload             jsonb not null,
  status              public.action_status not null default 'pending',
  response_status     int,
  attempts            int not null default 0,
  next_retry_at       timestamptz,
  last_error          text,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now()
);
-- Hot path: due deliveries.
create index webhook_delivery_due_idx
  on public.webhook_delivery (next_retry_at)
  where status = 'pending';
create index webhook_delivery_endpoint_idx on public.webhook_delivery (webhook_endpoint_id, created_at desc);
create trigger webhook_delivery_set_updated_at before update on public.webhook_delivery
  for each row execute function public.set_updated_at();
alter table public.webhook_delivery enable row level security;
create policy webhook_delivery_select_for_admins on public.webhook_delivery
  for select using (
    public.has_workspace_role(workspace_id, ARRAY['owner','admin']::public.workspace_role[])
  );
-- writes service-role only
