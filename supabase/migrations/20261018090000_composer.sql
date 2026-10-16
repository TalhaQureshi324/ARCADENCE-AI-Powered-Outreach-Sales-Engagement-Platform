-- ---------------------------------------------------------------------------
-- Migration 20261018090000 — Composer & personalization (Phase 4.2)
-- Saved templates, reusable blocks, variant signatures, thread mode.
-- ---------------------------------------------------------------------------

create table public.email_template (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  name         text not null check (char_length(name) between 1 and 120),
  subject      text not null default '',
  preheader    text not null default '',
  body_html    text not null default '',
  body_text    text not null default '',
  created_by   uuid,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

create unique index email_template_workspace_name_idx on public.email_template (workspace_id, lower(name));

create trigger email_template_set_updated_at before update on public.email_template
  for each row execute function public.set_updated_at();

create table public.reusable_block (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  name         text not null check (char_length(name) between 1 and 120),
  body_html    text not null default '',
  body_text    text not null default '',
  created_by   uuid,
  created_at   timestamptz not null default now()
);

create unique index reusable_block_workspace_name_idx on public.reusable_block (workspace_id, lower(name));

-- Signature + thread mode: signature appends to every send of the variant;
-- thread_mode makes follow-up steps send as replies in the same provider
-- thread (wiring lands with the sending engine, Phase 5).
alter table public.step_variant add column signature text not null default '';
alter table public.campaign add column thread_mode boolean not null default false;

alter table public.email_template enable row level security;
alter table public.reusable_block enable row level security;

create policy email_template_select_for_members on public.email_template
  for select using (public.is_workspace_member(workspace_id));
create policy reusable_block_select_for_members on public.reusable_block
  for select using (public.is_workspace_member(workspace_id));
