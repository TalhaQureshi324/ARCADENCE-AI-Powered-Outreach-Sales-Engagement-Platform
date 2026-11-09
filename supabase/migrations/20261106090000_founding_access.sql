-- ---------------------------------------------------------------------------
-- Migration 20261106090000 — Founding 10,000 program (Phase 14.2B.1)
-- Free-first launch: no Stripe charges at claim or expiry. Allocation uses a
-- locked program row + capacity counter so concurrent claims can never
-- exceed 10,000. One membership per user across all workspaces.
-- ---------------------------------------------------------------------------

create table public.founding_program (
  id            uuid primary key default gen_random_uuid(),
  slug          text not null unique default 'founding-10000',
  name          text not null default 'Arcadence Founding 10,000',
  description   text not null default 'The first 10,000 verified users receive 12 months of complimentary Founding Access. No credit card. No automatic charges.',
  capacity      int not null default 10000 check (capacity > 0),
  claimed_count int not null default 0,
  status        text not null default 'active' check (status in ('draft','active','paused','filled','closed')),
  terms_version text not null default 'v1',
  created_at    timestamptz not null default now()
);

create table public.founding_members (
  id            uuid primary key default gen_random_uuid(),
  program_id    uuid not null references public.founding_program (id) on delete cascade,
  user_id       uuid not null unique,
  workspace_id  uuid references public.workspace (id) on delete set null,
  claim_position int not null unique,
  status        text not null default 'active' check (status in ('active','expired','revoked')),
  activated_at  timestamptz not null default now(),
  expires_at    timestamptz not null,
  terms_version text not null default 'v1',
  created_at    timestamptz not null default now()
);
create index founding_members_program_idx on public.founding_members (program_id, claim_position);

create table public.user_announcement_prefs (
  user_id       uuid primary key,
  offer_dismissed_at timestamptz,
  snoozed_until timestamptz,
  email_updates_opt_in boolean not null default false,
  updated_at    timestamptz not null default now()
);

alter table public.founding_program enable row level security;
alter table public.founding_members enable row level security;
alter table public.user_announcement_prefs enable row level security;

-- Public program status is readable by anyone (accurate, real counter only)
create policy founding_program_select_public on public.founding_program
  for select using (true);
-- Members can see only their own membership row
create policy founding_members_select_own on public.founding_members
  for select using (user_id = public.current_user_id());
-- Announcement prefs are strictly per-user
create policy announcement_prefs_select_own on public.user_announcement_prefs
  for select using (user_id = public.current_user_id());
