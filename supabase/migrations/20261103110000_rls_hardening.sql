-- ---------------------------------------------------------------------------
-- Migration 20261103110000 — RLS hardening (Phase 14.1 audit remediation)
-- Schema-wide audit found 12 tables with RLS disabled while anon/authenticated
-- roles held full grants (Supabase default privileges). Any authenticated
-- user could read or modify another workspace's rows directly via PostgREST.
--
-- Fix: enable RLS on all of them with NO policies. PostgREST access becomes
-- deny-by-default (no policy = no rows visible). The Scala API and worker
-- connect as the database owner and bypass RLS, so application behavior is
-- unchanged. account_credentials and billing_events already use this
-- deny-by-default posture intentionally (encrypted blobs / event ledger).
-- ---------------------------------------------------------------------------

alter table public.campaign_daily_stats enable row level security;
alter table public.campaign_event       enable row level security;
alter table public.crm_task_assignment  enable row level security;
alter table public.event_outbox         enable row level security;
alter table public.inbound_events       enable row level security;
alter table public.job_attempt          enable row level security;
alter table public.job_queue            enable row level security;
alter table public.mailbox_sync_cursors enable row level security;
alter table public.provider_message_refs enable row level security;
alter table public.quota_reservation    enable row level security;
alter table public.send_attempt         enable row level security;
alter table public.sync_jobs            enable row level security;
