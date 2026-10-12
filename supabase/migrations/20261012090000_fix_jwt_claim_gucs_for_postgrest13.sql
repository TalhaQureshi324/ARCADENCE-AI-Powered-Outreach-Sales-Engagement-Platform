-- Fix tenancy helpers for PostgREST 13+.
-- PostgREST v13 removed the per-claim `request.jwt.claim.*` GUCs in favor of
-- `request.jwt.sub` and the full `request.jwt.claims` JSON. The originals in
-- 20261010100000 relied on the old GUCs, which made member-scoped RLS policies
-- (profile_select_own, is_workspace_member, …) resolve to NULL and silently
-- match zero rows. Coalesce every known source, old and new.

create or replace function public.current_user_id() returns uuid
language sql stable
as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.sub', true), ''),
    nullif(current_setting('request.jwt.sub', true), ''),
    nullif(coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb ->> 'sub', '')
  )::uuid
$$;

create or replace function public.current_user_email() returns text
language sql stable
as $$
  select coalesce(
    nullif(current_setting('request.jwt.claim.email', true), ''),
    nullif(coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb ->> 'email', '')
  )
$$;
