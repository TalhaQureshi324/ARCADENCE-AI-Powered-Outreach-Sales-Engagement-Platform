-- Phase 6.4 — collaboration on threads
create table public.conversation_notes (
  id         uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  thread_id  uuid not null references public.email_threads (id) on delete cascade,
  author_id  uuid,
  body       text not null,
  created_at timestamptz not null default now()
);
create index conversation_notes_thread_idx on public.conversation_notes (thread_id, created_at);

alter table public.conversation_notes enable row level security;
create policy conversation_notes_select_for_members on public.conversation_notes
  for select using (public.is_workspace_member(workspace_id));
