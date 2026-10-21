create type public.deal_status as enum ('open','won','lost');

create table public.pipeline (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  name         text not null check (char_length(name) between 1 and 120),
  created_at   timestamptz not null default now()
);
create unique index pipeline_workspace_name_idx on public.pipeline (workspace_id, lower(name));

create table public.pipeline_stage (
  id          uuid primary key default gen_random_uuid(),
  pipeline_id uuid not null references public.pipeline (id) on delete cascade,
  name        text not null,
  position    int not null
);
create unique index pipeline_stage_name_idx on public.pipeline_stage (pipeline_id, lower(name));

create table public.deal (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  pipeline_id   uuid not null references public.pipeline (id) on delete cascade,
  stage_id      uuid not null references public.pipeline_stage (id) on delete cascade,
  title         text not null check (char_length(title) between 1 and 200),
  value_cents   bigint not null default 0 check (value_cents >= 0),
  currency      text not null default 'USD' check (char_length(currency) = 3),
  status        public.deal_status not null default 'open',
  owner_id      uuid,
  source        text not null default 'manual',
  source_enrollment_id uuid references public.campaign_enrollment (id) on delete set null,
  expected_close_date date,
  finished_at     timestamptz,
  custom_fields jsonb not null default '{}'::jsonb,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create unique index deal_enrollment_open_idx on public.deal (source_enrollment_id)
  where source_enrollment_id is not null and status = 'open';
create index deal_ws_idx on public.deal (workspace_id, status);
create trigger deal_set_updated_at before update on public.deal
  for each row execute function public.set_updated_at();

create table public.deal_contact (
  deal_id    uuid not null references public.deal (id) on delete cascade,
  contact_id uuid not null references public.contact (id) on delete cascade,
  primary key (deal_id, contact_id)
);

create table public.deal_company (
  deal_id     uuid not null references public.deal (id) on delete cascade,
  company_id  uuid not null references public.company (id) on delete cascade,
  primary key (deal_id, company_id)
);

create table public.crm_activity (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  deal_id      uuid references public.deal (id) on delete cascade,
  contact_id   uuid references public.contact (id) on delete cascade,
  type         text not null check (type in ('note','call','meeting','stage_change','reply','status_change','task')),
  body         text not null default '',
  actor_id     uuid,
  created_at   timestamptz not null default now()
);
create index crm_activity_deal_idx on public.crm_activity (deal_id, created_at desc);

create table public.crm_task (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  deal_id      uuid references public.deal (id) on delete cascade,
  title        text not null,
  due_at       timestamptz,
  completed_at timestamptz,
  created_at   timestamptz not null default now()
);
create table public.crm_task_assignment (
  task_id uuid not null references public.crm_task (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  primary key (task_id, user_id)
);
create table public.crm_reminder (
  id       uuid primary key default gen_random_uuid(),
  task_id  uuid not null references public.crm_task (id) on delete cascade,
  remind_at timestamptz not null
);

alter table public.organization add column parent_organization_id uuid references public.organization (id) on delete set null;

alter table public.pipeline enable row level security;
alter table public.pipeline_stage enable row level security;
alter table public.deal enable row level security;
alter table public.deal_contact enable row level security;
alter table public.deal_company enable row level security;
alter table public.crm_activity enable row level security;
alter table public.crm_task enable row level security;
alter table public.crm_reminder enable row level security;
create policy pipeline_select_for_members on public.pipeline for select using (public.is_workspace_member(workspace_id));
create policy pipeline_stage_select_for_members on public.pipeline_stage for select using (
  exists (select 1 from public.pipeline p where p.id = pipeline_id and public.is_workspace_member(p.workspace_id)));
create policy deal_select_for_members on public.deal for select using (public.is_workspace_member(workspace_id));
create policy deal_contact_select_for_members on public.deal_contact for select using (
  exists (select 1 from public.deal d join public.workspace w on w.id = d.workspace_id where d.id = deal_id and public.is_workspace_member(w.id)));
create policy deal_company_select_for_members on public.deal_company for select using (
  exists (select 1 from public.deal d join public.workspace w on w.id = d.workspace_id where d.id = deal_id and public.is_workspace_member(w.id)));
create policy crm_activity_select_for_members on public.crm_activity for select using (public.is_workspace_member(workspace_id));
create policy crm_task_select_for_members on public.crm_task for select using (public.is_workspace_member(workspace_id));
create policy crm_reminder_select_for_members on public.crm_reminder for select using (
  exists (select 1 from public.crm_task t where t.id = task_id and public.is_workspace_member(t.workspace_id)));
