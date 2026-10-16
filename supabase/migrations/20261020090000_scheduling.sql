-- ---------------------------------------------------------------------------
-- Migration 20261020090000 — Scheduling rules (Phase 4.4)
-- ---------------------------------------------------------------------------

alter table public.campaign
  add column send_window_start time not null default '09:00',
  add column send_window_end time not null default '17:00',
  add column business_days_only boolean not null default true;
