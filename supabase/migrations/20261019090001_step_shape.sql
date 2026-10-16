-- Second phase of 20261019090000: the widened step_shape check must run in a
-- separate transaction from ALTER TYPE ... ADD VALUE (PG 55P04).
alter table public.campaign_step drop constraint step_shape;
alter table public.campaign_step add constraint step_shape check (
  (type in ('email', 'branch', 'task', 'add_tag') and wait_days is null)
  or (type = 'wait' and wait_days is not null)
);
