-- ---------------------------------------------------------------------------
-- Migration 20261017090000 — Campaign domain (Phase 4.1)
-- Campaigns, steps + variants, mailboxes, audiences, enrollments, versions.
-- Lifecycle: draft | active | paused | completed | cancelled | archived
-- (validated in Scala; see docs/ARCHITECTURE.md §7).
-- ---------------------------------------------------------------------------

alter type public.campaign_status add value if not exists 'cancelled' after 'completed';

create table public.campaign (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  name          text not null check (char_length(name) between 1 and 140),
  description   text not null default '',
  tags          text[] not null default '{}',
  status        public.campaign_status not null default 'draft',
  timezone      text not null default 'Etc/UTC',
  daily_cap     int not null default 50 check (daily_cap between 1 and 10000),
  open_tracking   boolean not null default true,
  click_tracking  boolean not null default true,
  created_by    uuid,
  activated_at  timestamptz,
  archived_at   timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

create unique index campaign_workspace_name_idx on public.campaign (workspace_id, lower(name));
create index campaign_workspace_status_idx on public.campaign (workspace_id, status);

create trigger campaign_set_updated_at before update on public.campaign
  for each row execute function public.set_updated_at();

-- Linear sequence steps (email/wait for 4.1; branching types arrive in 4.3).
create table public.campaign_step (
  id          uuid primary key default gen_random_uuid(),
  campaign_id uuid not null references public.campaign (id) on delete cascade,
  position    int not null check (position between 1 and 100),
  type        public.step_type not null,
  name        text not null default '',
  wait_days   int check (wait_days between 0 and 365),
  created_at  timestamptz not null default now(),
  constraint step_shape check (
    (type = 'email' and wait_days is null) or (type = 'wait' and wait_days is not null)
  )
);

create unique index campaign_step_position_idx on public.campaign_step (campaign_id, position);

-- A/Z variants on an email step (one variant minimum; 'A' is the control).
create table public.step_variant (
  id              uuid primary key default gen_random_uuid(),
  step_id         uuid not null references public.campaign_step (id) on delete cascade,
  variant_key     text not null check (variant_key in ('A', 'Z')),
  subject         text not null default '',
  preheader       text not null default '',
  body_html       text not null default '',
  body_text       text not null default '',
  created_at      timestamptz not null default now(),
  unique (step_id, variant_key)
);

-- Sending accounts assigned to the campaign (Phase 2 email_accounts).
create table public.campaign_mailbox (
  id               uuid primary key default gen_random_uuid(),
  campaign_id      uuid not null references public.campaign (id) on delete cascade,
  email_account_id uuid not null references public.email_accounts (id) on delete cascade,
  priority         int not null default 0,
  created_at       timestamptz not null default now(),
  unique (campaign_id, email_account_id)
);

create index campaign_mailbox_account_idx on public.campaign_mailbox (email_account_id);

-- Audiences: either a lead list or a saved segment, never both.
create table public.campaign_audience (
  id           uuid primary key default gen_random_uuid(),
  campaign_id  uuid not null references public.campaign (id) on delete cascade,
  lead_list_id uuid references public.lead_list (id) on delete cascade,
  segment_id   uuid references public.segment (id) on delete cascade,
  added_at     timestamptz not null default now(),
  constraint audience_exactly_one check (num_nonnulls(lead_list_id, segment_id) = 1),
  unique (campaign_id, lead_list_id),
  unique (campaign_id, segment_id)
);

-- Contacts progressing through the campaign. Rows are created by the API
-- once a campaign is active; structural campaign edits that would change
-- what an enrollment still has to execute are rejected while enrollments
-- are active (enforced in Scala).
create table public.campaign_enrollment (
  id             uuid primary key default gen_random_uuid(),
  workspace_id   uuid not null references public.workspace (id) on delete cascade,
  campaign_id    uuid not null references public.campaign (id) on delete cascade,
  contact_id     uuid not null references public.contact (id) on delete cascade,
  source_list_id uuid references public.lead_list (id) on delete set null,
  status         public.enrollment_status not null default 'active',
  current_step   int not null default 0,
  next_action_at timestamptz,
  started_at     timestamptz not null default now(),
  finished_at    timestamptz,
  created_at     timestamptz not null default now(),
  unique (campaign_id, contact_id)
);

create index enrollment_workspace_status_idx on public.campaign_enrollment (workspace_id, status);
create index enrollment_due_idx on public.campaign_enrollment (status, next_action_at)
  where status = 'active';

-- Snapshot of the step/variant definition on every structural save; gives
-- auditability once enrollments exist (what did THIS enrollment sign up for).
create table public.campaign_version (
  id          uuid primary key default gen_random_uuid(),
  campaign_id uuid not null references public.campaign (id) on delete cascade,
  version     int not null,
  definition  jsonb not null,
  created_by  uuid,
  created_at  timestamptz not null default now(),
  unique (campaign_id, version)
);

-- RLS: member-select on every tenant table; writes flow through the audited API.
alter table public.campaign enable row level security;
alter table public.campaign_step enable row level security;
alter table public.step_variant enable row level security;
alter table public.campaign_mailbox enable row level security;
alter table public.campaign_audience enable row level security;
alter table public.campaign_enrollment enable row level security;
alter table public.campaign_version enable row level security;

create policy campaign_select_for_members on public.campaign
  for select using (public.is_workspace_member(workspace_id));
create policy campaign_step_select_for_members on public.campaign_step
  for select using (
    exists (select 1 from public.campaign c where c.id = campaign_id and public.is_workspace_member(c.workspace_id))
  );
create policy step_variant_select_for_members on public.step_variant
  for select using (
    exists (
      select 1 from public.campaign_step s join public.campaign c on c.id = s.campaign_id
      where s.id = step_id and public.is_workspace_member(c.workspace_id)
    )
  );
create policy campaign_mailbox_select_for_members on public.campaign_mailbox
  for select using (
    exists (select 1 from public.campaign c where c.id = campaign_id and public.is_workspace_member(c.workspace_id))
  );
create policy campaign_audience_select_for_members on public.campaign_audience
  for select using (
    exists (select 1 from public.campaign c where c.id = campaign_id and public.is_workspace_member(c.workspace_id))
  );
create policy campaign_enrollment_select_for_members on public.campaign_enrollment
  for select using (public.is_workspace_member(workspace_id));
create policy campaign_version_select_for_members on public.campaign_version
  for select using (
    exists (select 1 from public.campaign c where c.id = campaign_id and public.is_workspace_member(c.workspace_id))
  );
