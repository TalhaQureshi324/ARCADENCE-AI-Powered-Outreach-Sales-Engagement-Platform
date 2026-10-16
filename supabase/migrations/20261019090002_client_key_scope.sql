-- The client_key uniqueness must be per campaign, not global (a global index
-- collides with the same editor keys across campaigns).
drop index if exists campaign_step_client_key_idx;
create unique index campaign_step_client_key_idx on public.campaign_step (campaign_id, client_key)
  where client_key is not null;
