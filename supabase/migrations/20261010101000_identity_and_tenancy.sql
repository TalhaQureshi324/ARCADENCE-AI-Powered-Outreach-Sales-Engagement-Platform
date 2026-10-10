-- Identity & tenancy: profile, organization, workspace, workspace_member, invitation.
-- RLS enabled with member/admin policies; writes flow through the Scala API
-- (service role) — clients only read what their membership allows.

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------
create table public.profile (
  id           uuid primary key,          -- = auth.users.id (FK added conditionally below)
  display_name text,
  avatar_url   text,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

create table public.organization (
  id                 uuid primary key default gen_random_uuid(),
  name               text not null check (length(name) between 1 and 200),
  stripe_customer_id text,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
-- Index serves billing lookups by Stripe customer; partial because most orgs have none.
create unique index organization_stripe_customer_id_key
  on public.organization (stripe_customer_id)
  where stripe_customer_id is not null;

create table public.workspace (
  id              uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organization (id) on delete cascade,
  name            text not null check (length(name) between 1 and 120),
  slug            text not null check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  timezone        text not null default 'UTC' check (timezone <> ''),
  daily_send_cap  int not null default 200 check (daily_send_cap between 1 and 10000),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz
);
alter table public.workspace
  add constraint workspace_organization_slug_key unique (organization_id, slug);

create table public.workspace_member (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  user_id      uuid not null,              -- = auth.users.id (FK added conditionally below)
  role         public.workspace_role not null default 'member',
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
alter table public.workspace_member
  add constraint workspace_member_workspace_user_key unique (workspace_id, user_id);
-- Serves "list workspaces for user".
create index workspace_member_user_id_idx on public.workspace_member (user_id);

create table public.invitation (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  email        text not null check (position('@' in email) > 1),
  role         public.workspace_role not null default 'member',
  token        uuid not null default gen_random_uuid(),
  invited_by   uuid,
  expires_at   timestamptz not null default now() + interval '7 days',
  accepted_at  timestamptz,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create unique index invitation_token_key on public.invitation (token);
-- One open invitation per workspace+email; accepted ones no longer count.
create unique index invitation_open_workspace_email_key
  on public.invitation (workspace_id, lower(email))
  where accepted_at is null;
-- Serves "find invitations addressed to me".
create index invitation_email_idx on public.invitation (lower(email));

-- ---------------------------------------------------------------------------
-- Conditional FKs to Supabase auth (skipped on plain PostgreSQL in CI)
-- ---------------------------------------------------------------------------
do $$
begin
  if exists (select 1 from information_schema.schemata where schema_name = 'auth') then
    alter table public.profile
      add constraint profile_id_fkey foreign key (id) references auth.users (id) on delete cascade;
    alter table public.workspace_member
      add constraint workspace_member_user_id_fkey foreign key (user_id) references auth.users (id) on delete cascade;
    alter table public.invitation
      add constraint invitation_invited_by_fkey foreign key (invited_by) references auth.users (id) on delete set null;
  end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- updated_at triggers
-- ---------------------------------------------------------------------------
create trigger profile_set_updated_at before update on public.profile
  for each row execute function public.set_updated_at();
create trigger organization_set_updated_at before update on public.organization
  for each row execute function public.set_updated_at();
create trigger workspace_set_updated_at before update on public.workspace
  for each row execute function public.set_updated_at();
create trigger workspace_member_set_updated_at before update on public.workspace_member
  for each row execute function public.set_updated_at();
create trigger invitation_set_updated_at before update on public.invitation
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------
alter table public.profile enable row level security;
create policy profile_select_own on public.profile
  for select using (id = public.current_user_id());
create policy profile_update_own on public.profile
  for update using (id = public.current_user_id())
  with check (id = public.current_user_id());
-- insert handled by the on-signup trigger below / service role

alter table public.organization enable row level security;
create policy organization_select_for_members on public.organization
  for select using (
    exists (
      select 1 from public.workspace w
      where w.organization_id = id
        and public.is_workspace_member(w.id)
    )
  );
-- writes service-role only (billing owner flows go through the API)

alter table public.workspace enable row level security;
create policy workspace_select_for_members on public.workspace
  for select using (public.is_workspace_member(id));
create policy workspace_update_for_admins on public.workspace
  for update using (public.has_workspace_role(id, ARRAY['owner','admin']::public.workspace_role[]))
  with check (public.has_workspace_role(id, ARRAY['owner','admin']::public.workspace_role[]));
-- insert/delete service-role only

alter table public.workspace_member enable row level security;
create policy workspace_member_select_for_members on public.workspace_member
  for select using (
    public.is_workspace_member(workspace_id)
    or user_id = public.current_user_id()
  );
-- writes service-role only (membership changes are audited in the API)

alter table public.invitation enable row level security;
create policy invitation_select_for_admins on public.invitation
  for select using (
    public.has_workspace_role(workspace_id, ARRAY['owner','admin']::public.workspace_role[])
  );
create policy invitation_select_own_open on public.invitation
  for select using (
    accepted_at is null
    and lower(public.current_user_email()) = lower(email)
  );
-- writes service-role only

-- ---------------------------------------------------------------------------
-- Profile provisioning on signup (trigger attached in Supabase environments only)
-- ---------------------------------------------------------------------------
-- The function is portable (plain PostgreSQL has no auth.users to fire it);
-- the trigger is attached only when the auth schema exists. No nested dollar
-- quotes: the migration runner's statement splitter does not support them.
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public
as $$
begin
  insert into public.profile (id, display_name)
  values (new.id, coalesce(new.raw_user_meta_data ->> 'name', split_part(new.email, '@', 1)))
  on conflict (id) do nothing;
  return new;
end;
$$;

do $$
begin
  if exists (select 1 from information_schema.schemata where schema_name = 'auth') then
    execute 'create trigger on_auth_user_created after insert on auth.users for each row execute function public.handle_new_user()';
  end if;
end;
$$;
