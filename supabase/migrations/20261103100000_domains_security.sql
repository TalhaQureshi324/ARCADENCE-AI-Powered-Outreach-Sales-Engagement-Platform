-- ---------------------------------------------------------------------------
-- Migration 20261103100000 — Managed domains + data export/deletion (13.2/13.3)
-- Domain inventory tracks registrar/DNS provider and setup status. Automatic
-- provisioning is NOT claimed: domains are registered manually (or via a
-- future contractual integration) and their status is tracked honestly.
-- ---------------------------------------------------------------------------

create table public.managed_domains (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  domain        text not null,
  registrar     text,
  dns_provider  text,
  status        text not null default 'manual_setup'
    check (status in ('manual_setup','pending_provisioning','active','failed')),
  dns_records   jsonb not null default '{}'::jsonb,
  renewal_date  date,
  setup_notes   text,
  last_error    text,
  created_by    uuid,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  unique (workspace_id, domain)
);
create index managed_domains_ws_idx on public.managed_domains (workspace_id, status);

create trigger managed_domains_set_updated_at before update on public.managed_domains
  for each row execute function public.set_updated_at();

alter table public.managed_domains enable row level security;
create policy managed_domains_select_for_members on public.managed_domains
  for select using (public.is_workspace_member(workspace_id));

-- Data export / deletion request tracking (13.3 privacy workflows)
create table public.privacy_requests (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  request_type text not null check (request_type in ('export','deletion')),
  subject_email text not null,
  status       text not null default 'pending' check (status in ('pending','completed','failed')),
  completed_at timestamptz,
  requested_by uuid,
  created_at   timestamptz not null default now()
);
alter table public.privacy_requests enable row level security;
create policy privacy_requests_select_for_members on public.privacy_requests
  for select using (public.is_workspace_member(workspace_id));

-- Security audit log (13.3): sensitive security events, retained by ops.
create table public.security_audit_log (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid,
  event_type   text not null,
  actor        text,
  details      jsonb not null default '{}'::jsonb,
  created_at   timestamptz not null default now()
);
create index security_audit_idx on public.security_audit_log (created_at desc);
alter table public.security_audit_log enable row level security;
create policy security_audit_select_for_members on public.security_audit_log
  for select using (workspace_id is not null and public.is_workspace_member(workspace_id));
