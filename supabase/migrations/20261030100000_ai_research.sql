-- ---------------------------------------------------------------------------
-- Migration 20261030100000 — AI research facts (Phase 11.2)
-- Approved company/lead knowledge the AI writer may ground on. Every fact
-- carries its source URL (or manual origin), retrieval timestamp and kind —
-- personalization claims must cite these or be flagged as unsupported.
-- ---------------------------------------------------------------------------

create table public.ai_research_items (
  id             uuid primary key default gen_random_uuid(),
  workspace_id   uuid not null references public.workspace (id) on delete cascade,
  contact_id     uuid references public.contact (id) on delete cascade,
  company_domain text,
  claim          text not null check (char_length(claim) between 1 and 2000),
  source_url     text,
  source_kind    text not null default 'manual' check (source_kind in ('manual','leadfinder','imported')),
  retrieved_at   timestamptz not null default now(),
  verified       boolean not null default false,
  created_by     uuid,
  created_at     timestamptz not null default now()
);
create index ai_research_ws_idx on public.ai_research_items (workspace_id, created_at desc);
create index ai_research_contact_idx on public.ai_research_items (contact_id);

alter table public.ai_research_items enable row level security;
create policy ai_research_select_for_members on public.ai_research_items
  for select using (public.is_workspace_member(workspace_id));
