-- ---------------------------------------------------------------------------
-- Migration 20261015100000 — Import pipeline (Phase 3.2)
--
-- import_job: durable, restartable CSV import jobs (file stored server-side).
-- import_error: per-row errors, powering the downloadable error report.
-- Idempotent retries are keyed on (import_job_id, row_number) provenance.
-- ---------------------------------------------------------------------------

create table public.import_job (
  id                 uuid primary key default gen_random_uuid(),
  workspace_id       uuid not null references public.workspace (id) on delete cascade,
  created_by         uuid,
  file_path          text not null,
  file_name          text not null,
  file_size          bigint not null,
  status             text not null default 'created'
    check (status in ('created', 'queued', 'running', 'completed', 'failed', 'cancelled')),
  duplicate_strategy text not null default 'skip'
    check (duplicate_strategy in ('skip', 'update', 'reject')),
  column_mapping     jsonb,
  defaults           jsonb not null default '{}'::jsonb,
  target_list_id     uuid references public.lead_list (id) on delete set null,
  total_rows         int not null default 0,
  processed_rows     int not null default 0,
  inserted_rows      int not null default 0,
  updated_rows       int not null default 0,
  skipped_rows       int not null default 0,
  invalid_rows       int not null default 0,
  duplicate_rows     int not null default 0,
  error_message      text,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);
create index import_job_workspace_idx on public.import_job (workspace_id, created_at desc);

create table public.import_error (
  id         uuid primary key default gen_random_uuid(),
  job_id     uuid not null references public.import_job (id) on delete cascade,
  row_number int not null,
  reason     text not null,
  created_at timestamptz not null default now()
);
create index import_error_job_idx on public.import_error (job_id, row_number);

create trigger import_job_set_updated_at before update on public.import_job
  for each row execute function public.set_updated_at();

-- provenance rows now reference the originating job (unique = idempotent retries)
alter table public.source_provenance
  add column if not exists row_number int;
-- plain unique index (NULLs never conflict, so manual rows are unaffected);
-- a partial index cannot be targeted by ON CONFLICT (import_job_id, row_number)
create unique index if not exists source_provenance_job_row_idx
  on public.source_provenance (import_job_id, row_number);

alter table public.import_job enable row level security;
create policy import_job_select_for_members on public.import_job
  for select using (public.is_workspace_member(workspace_id));

alter table public.import_error enable row level security;
create policy import_error_select_for_members on public.import_error
  for select using (
    public.is_workspace_member(
      (select j.workspace_id from public.import_job j where j.id = job_id)
    )
  );
