-- ---------------------------------------------------------------------------
-- Migration 20261101120000b — Website visitor intelligence (Phase 12.3)
-- Tenants register sites, get a site key (the only credential the public
-- tracking script needs), and events are collected anonymously. Aggregates
-- power the dashboard; no individual-person identification is claimed.
-- ---------------------------------------------------------------------------

create table public.site_keys (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  domain       text not null,
  site_key     text not null unique,
  active       boolean not null default true,
  retention_days int not null default 90 check (retention_days between 7 and 365),
  created_by   uuid,
  created_at   timestamptz not null default now()
);

create table public.visitor_events (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  site_key_id  uuid not null references public.site_keys (id) on delete cascade,
  event_type   text not null check (event_type in ('pageview','conversion')),
  path         text not null default '/',
  referrer     text,
  utm_source   text,
  utm_medium   text,
  utm_campaign text,
  session_id   text,
  occurred_at  timestamptz not null default now()
);
create index visitor_events_ws_idx on public.visitor_events (workspace_id, occurred_at desc);
create index visitor_events_site_idx on public.visitor_events (site_key_id, occurred_at desc);

alter table public.site_keys enable row level security;
alter table public.visitor_events enable row level security;
create policy site_keys_select_for_members on public.site_keys
  for select using (public.is_workspace_member(workspace_id));
create policy visitor_events_select_for_members on public.visitor_events
  for select using (public.is_workspace_member(workspace_id));
