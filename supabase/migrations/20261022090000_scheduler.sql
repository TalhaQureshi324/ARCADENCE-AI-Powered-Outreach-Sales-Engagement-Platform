-- Phase 5.2 — scheduler: enrollments track their next step explicitly (graphs
-- made position unreliable), plus a marker for the last mailbox used so
-- follow-ups keep the same sender within a conversation.
alter table public.campaign_enrollment add column next_step_id uuid
  references public.campaign_step (id) on delete set null;
alter table public.campaign_enrollment add column last_account_id uuid
  references public.email_accounts (id) on delete set null;

create index if not exists enrollment_due_idx2 on public.campaign_enrollment (next_action_at)
  where status = 'active';
