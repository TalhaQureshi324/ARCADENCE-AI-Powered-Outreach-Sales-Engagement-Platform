-- ---------------------------------------------------------------------------
-- Migration 20261102100100 — Multichannel opt-in (Phase 12.5)
-- Channel consent is SEPARATE from email consent: a contact may be
-- reachable by email and simultaneously opted out of SMS/calls. The
-- channel adapter contract is code-level (core Integrations pattern);
-- no third-party provider is called without explicit configuration and
-- consent. CRM activities record phone/meeting interactions.
-- ---------------------------------------------------------------------------

alter table public.contact
  add column if not exists sms_consent text not null default 'unknown'
    check (sms_consent in ('unknown','opted_in','opted_out')),
  add column if not exists call_consent text not null default 'unknown'
    check (call_consent in ('unknown','opted_in','opted_out')),
  add column if not exists sms_consent_at timestamptz,
  add column if not exists call_consent_at timestamptz;

create table public.channel_interactions (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspace (id) on delete cascade,
  contact_id   uuid not null references public.contact (id) on delete cascade,
  channel      text not null check (channel in ('sms','call')),
  direction    text not null check (direction in ('outbound','inbound')),
  summary      text not null,
  outcome      text,
  occurred_at  timestamptz not null default now(),
  created_by   uuid,
  created_at   timestamptz not null default now()
);
create index channel_interactions_contact_idx on public.channel_interactions (contact_id, occurred_at desc);

alter table public.channel_interactions enable row level security;
create policy channel_interactions_select_for_members on public.channel_interactions
  for select using (public.is_workspace_member(workspace_id));
