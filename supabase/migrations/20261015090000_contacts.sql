-- ---------------------------------------------------------------------------
-- Migration 20261015090000 — Contacts & companies (Phase 3.1)
--
-- Tables: company, contact, lead_list, list_membership, tag, contact_tag,
-- custom_field, source_provenance (docs/DATABASE.md §6.3).
--
-- Emails are stored normalized (lowercased) for deduplication; the display
-- value is retained in email_display. Contacts exist independently of any
-- campaign enrollment (campaign links arrive in Phase 4).
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- company
-- ---------------------------------------------------------------------------
create table public.company (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  name         text not null check (char_length(name) between 1 and 200),
  domain       text check (char_length(domain) between 3 and 253),
  website      text,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create unique index company_workspace_name_idx on public.company (workspace_id, lower(name));
create unique index company_workspace_domain_idx on public.company (workspace_id, lower(domain)) where domain is not null;
create index company_workspace_idx on public.company (workspace_id);

-- ---------------------------------------------------------------------------
-- contact
-- ---------------------------------------------------------------------------
create table public.contact (
  id                      uuid primary key default gen_random_uuid(),
  workspace_id            uuid not null references public.workspace (id) on delete cascade,
  company_id              uuid references public.company (id) on delete set null,
  email                   text not null check (char_length(email) between 3 and 320),
  email_display           text,
  first_name              text check (char_length(first_name) <= 120),
  last_name               text check (char_length(last_name) <= 120),
  job_title               text check (char_length(job_title) <= 200),
  website                 text,
  phone                   text check (char_length(phone) <= 40),
  region                  text check (char_length(region) <= 80),
  timezone                text check (char_length(timezone) <= 80),
  notes                   text,
  custom_fields           jsonb not null default '{}'::jsonb,
  verification_status     public.contact_verification not null default 'unknown',
  verification_checked_at timestamptz,
  verification_provider   text,
  state                   public.contact_state not null default 'active',
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now()
);
create unique index contact_workspace_email_idx on public.contact (workspace_id, email);
create index contact_workspace_state_idx on public.contact (workspace_id, state);
create index contact_workspace_company_idx on public.contact (workspace_id, company_id);
create index contact_workspace_created_idx on public.contact (workspace_id, created_at desc, id);
create index contact_custom_fields_idx on public.contact using gin (custom_fields);

create trigger contact_set_updated_at before update on public.contact
  for each row execute function public.set_updated_at();
create trigger company_set_updated_at before update on public.company
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- lead_list + list_membership
-- ---------------------------------------------------------------------------
create table public.lead_list (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  name         text not null check (char_length(name) between 1 and 120),
  description  text,
  -- actor id (no FK: workspace_member has no unique user_id)
  created_by   uuid,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create unique index lead_list_workspace_name_idx on public.lead_list (workspace_id, lower(name));

create table public.list_membership (
  id         uuid primary key default gen_random_uuid(),
  list_id    uuid not null references public.lead_list (id) on delete cascade,
  contact_id uuid not null references public.contact (id) on delete cascade,
  added_by   uuid,
  added_at   timestamptz not null default now(),
  unique (list_id, contact_id)
);
create index list_membership_contact_idx on public.list_membership (contact_id);

create trigger lead_list_set_updated_at before update on public.lead_list
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- tag + contact_tag
-- ---------------------------------------------------------------------------
create table public.tag (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  name         text not null check (char_length(name) between 1 and 60),
  color        text check (char_length(color) <= 20),
  created_at   timestamptz not null default now(),
  unique (workspace_id, name)
);
create index tag_workspace_idx on public.tag (workspace_id);

create table public.contact_tag (
  contact_id uuid not null references public.contact (id) on delete cascade,
  tag_id     uuid not null references public.tag (id) on delete cascade,
  added_at   timestamptz not null default now(),
  primary key (contact_id, tag_id)
);
create index contact_tag_tag_idx on public.contact_tag (tag_id);

-- ---------------------------------------------------------------------------
-- custom_field (user-defined variable definitions; values live in
-- contact.custom_fields keyed by field name)
-- ---------------------------------------------------------------------------
create table public.custom_field (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  name         text not null check (char_length(name) between 1 and 60),
  field_type   public.field_type not null default 'text',
  description  text,
  created_at   timestamptz not null default now(),
  unique (workspace_id, name)
);
create index custom_field_workspace_idx on public.custom_field (workspace_id);

-- ---------------------------------------------------------------------------
-- source_provenance — append-only trail of where a contact came from
-- (manual creation, imports (Phase 3.2 attaches import_job_id), API)
-- ---------------------------------------------------------------------------
create table public.source_provenance (
  id            uuid primary key default gen_random_uuid(),
  workspace_id  uuid not null references public.workspace (id) on delete cascade,
  contact_id    uuid not null references public.contact (id) on delete cascade,
  source        text not null check (source in ('manual', 'import', 'api', 'verification')),
  import_job_id uuid,
  details       jsonb not null default '{}'::jsonb,
  created_at    timestamptz not null default now()
);
create index source_provenance_contact_idx on public.source_provenance (contact_id, created_at desc);
create index source_provenance_import_idx on public.source_provenance (import_job_id);

-- ---------------------------------------------------------------------------
-- Row Level Security — same shape as the tenancy tables: members read,
-- writes flow through the audited Scala API (service connection).
-- ---------------------------------------------------------------------------
alter table public.company enable row level security;
create policy company_select_for_members on public.company
  for select using (public.is_workspace_member(workspace_id));

alter table public.contact enable row level security;
create policy contact_select_for_members on public.contact
  for select using (public.is_workspace_member(workspace_id));

alter table public.lead_list enable row level security;
create policy lead_list_select_for_members on public.lead_list
  for select using (public.is_workspace_member(workspace_id));

alter table public.list_membership enable row level security;
create policy list_membership_select_for_members on public.list_membership
  for select using (
    public.is_workspace_member(
      (select l.workspace_id from public.lead_list l where l.id = list_id)
    )
  );

alter table public.tag enable row level security;
create policy tag_select_for_members on public.tag
  for select using (public.is_workspace_member(workspace_id));

alter table public.contact_tag enable row level security;
create policy contact_tag_select_for_members on public.contact_tag
  for select using (
    public.is_workspace_member(
      (select c.workspace_id from public.contact c where c.id = contact_id)
    )
  );

alter table public.custom_field enable row level security;
create policy custom_field_select_for_members on public.custom_field
  for select using (public.is_workspace_member(workspace_id));

alter table public.source_provenance enable row level security;
create policy source_provenance_select_for_members on public.source_provenance
  for select using (public.is_workspace_member(workspace_id));
