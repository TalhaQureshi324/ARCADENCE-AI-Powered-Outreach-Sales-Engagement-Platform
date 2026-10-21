-- ---------------------------------------------------------------------------
-- Migration 20261027120000 — Lead Finder starter credits (Phase 10)
-- New workspaces receive a one-time 50-credit grant so Lead Finder is usable
-- before billing exists (Phase 11 replaces this with purchased credits).
-- The ledger stays append-only and auditable like every other entry.
-- ---------------------------------------------------------------------------

create function public.grant_starter_credits() returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.credit_transactions (workspace_id, delta, kind, reason)
  values (new.id, 50, 'grant', 'starter grant');
  return new;
end;
$$;

drop trigger if exists workspace_starter_credits on public.workspace;
create trigger workspace_starter_credits
  after insert on public.workspace
  for each row execute function public.grant_starter_credits();
