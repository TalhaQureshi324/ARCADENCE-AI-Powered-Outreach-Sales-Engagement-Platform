-- Foundations: enum types, shared triggers, tenancy helper functions.
-- Portable: runs on Supabase (auth/storage schemas present) and plain PostgreSQL (CI).

-- ---------------------------------------------------------------------------
-- Enum types (docs/DATABASE.md §7)
-- ---------------------------------------------------------------------------
create type public.workspace_role as enum ('owner', 'admin', 'member', 'viewer');
create type public.email_provider as enum ('gmail', 'microsoft', 'smtp_imap');
create type public.connection_status as enum ('connected', 'auth_error', 'syncing', 'paused', 'disconnected');
create type public.contact_verification as enum ('unknown', 'valid', 'risky', 'invalid');
create type public.contact_state as enum ('active', 'unsubscribed', 'complained', 'bounced');
create type public.field_type as enum ('text', 'number', 'date', 'boolean', 'url');
create type public.suppression_reason as enum ('bounce', 'complaint', 'unsubscribe', 'manual', 'invalid');
create type public.suppression_source as enum ('automatic', 'import', 'manual');
create type public.campaign_status as enum ('draft', 'active', 'paused', 'completed', 'archived');
create type public.step_type as enum ('email', 'wait');
create type public.enrollment_status as enum ('pending', 'active', 'paused', 'completed', 'replied', 'unsubscribed', 'bounced', 'failed', 'skipped');
create type public.action_type as enum ('send_email', 'generic_job');
create type public.action_status as enum ('pending', 'claimed', 'done', 'failed', 'cancelled');
create type public.message_direction as enum ('outbound', 'inbound');
create type public.message_status as enum ('queued', 'sending', 'sent', 'delivered', 'deferred', 'bounced', 'failed', 'unknown', 'received');
create type public.conversation_status as enum ('open', 'closed', 'archived');
create type public.email_event_type as enum ('queued', 'sent', 'delivered', 'deferred', 'bounced_hard', 'bounced_soft', 'complained', 'opened', 'clicked', 'replied', 'unsubscribed', 'send_failed', 'received');
create type public.activity_type as enum ('note', 'call', 'meeting', 'task', 'email_linked');
create type public.ai_kind as enum ('campaign_copy', 'reply_draft', 'lead_summary', 'other');
create type public.ai_status as enum ('succeeded', 'failed');
create type public.subscription_status as enum ('trialing', 'active', 'past_due', 'canceled');

-- ---------------------------------------------------------------------------
-- updated_at maintenance
-- ---------------------------------------------------------------------------
create or replace function public.set_updated_at() returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

-- ---------------------------------------------------------------------------
-- Tenancy helpers (docs/DATABASE.md §9).
-- Identity comes from Postgres GUCs set by PostgREST/Supabase Auth
-- (`request.jwt.claim.sub` / `request.jwt.claim.email`), so these are portable
-- and return NULL when no JWT context exists (service jobs, plain psql).
-- ---------------------------------------------------------------------------

-- PostgreSQL 14+ validates `language sql` bodies (including referenced
-- relations) at creation time; these helpers reference tables that the next
-- migration creates, so body checking is disabled for bootstrap only.
set check_function_bodies = off;

create or replace function public.current_user_id() returns uuid
language sql stable
as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;

create or replace function public.current_user_email() returns text
language sql stable
as $$
  select nullif(current_setting('request.jwt.claim.email', true), '')
$$;

-- security definer so policies can evaluate membership without recursing
-- through workspace_member's own RLS; functions only return booleans/ids
-- derived from the caller's own JWT.
create or replace function public.is_workspace_member(ws uuid) returns boolean
language sql stable security definer set search_path = public
as $$
  select exists (
    select 1 from public.workspace_member m
    where m.workspace_id = ws
      and m.user_id = public.current_user_id()
  )
$$;

create or replace function public.has_workspace_role(ws uuid, required workspace_role[]) returns boolean
language sql stable security definer set search_path = public
as $$
  select exists (
    select 1 from public.workspace_member m
    where m.workspace_id = ws
      and m.user_id = public.current_user_id()
      and m.role = any(required)
  )
$$;
