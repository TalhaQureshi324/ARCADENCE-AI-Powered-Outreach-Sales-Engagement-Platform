-- ---------------------------------------------------------------------------
-- Migration 20261024090000 — Analytics event pipeline (Phase 7.1)
--
-- campaign_event is the append-only domain event source of truth for all
-- campaign analytics. Events are collected by database triggers from the
-- tables the engine already mutates (single writer, no dual paths).
-- campaign_daily_stats is the derived aggregate: full-day rebuild from raw
-- events, idempotent, re-runnable for any day (reconciliation = compare).
-- ---------------------------------------------------------------------------

create table public.campaign_event (
  id               uuid primary key default gen_random_uuid(),
  workspace_id     uuid not null,
  campaign_id      uuid,
  enrollment_id    uuid,
  contact_id       uuid,
  email_account_id uuid,
  step_id          uuid,
  variant_key      text,
  event_type       text not null check (event_type in
    ('campaign_activated','lead_enrolled','send_scheduled','accepted',
     'provider_rejected','bounce_hard','bounce_soft','reply','reply_classified',
     'meeting_booked','unsubscribe','deal_transition')),
  payload          jsonb not null default '{}'::jsonb,
  occurred_at      timestamptz not null default now()
);

create index campaign_event_campaign_idx on public.campaign_event (campaign_id, occurred_at);
create index campaign_event_workspace_idx on public.campaign_event (workspace_id, occurred_at);

-- campaign activation
create or replace function public.trg_campaign_event_activation() returns trigger as $$
begin
  if old.status::text <> 'active' and new.status::text = 'active' then
    insert into public.campaign_event (workspace_id, campaign_id, event_type)
    values (new.workspace_id, new.id, 'campaign_activated');
  end if;
  return new;
end $$ language plpgsql;

create trigger campaign_event_activation
  after update on public.campaign
  for each row execute function public.trg_campaign_event_activation();

-- lead enrollment
create or replace function public.trg_campaign_event_enrolled() returns trigger as $$
begin
  insert into public.campaign_event (workspace_id, campaign_id, enrollment_id, contact_id, event_type)
  values (new.workspace_id, new.campaign_id, new.id, new.contact_id, 'lead_enrolled');
  return new;
end $$ language plpgsql;

create trigger campaign_event_enrolled
  after insert on public.campaign_enrollment
  for each row execute function public.trg_campaign_event_enrolled();

-- send scheduling
create or replace function public.trg_campaign_event_scheduled() returns trigger as $$
begin
  if new.type::text = 'send_email' then
    insert into public.campaign_event
      (workspace_id, campaign_id, enrollment_id, contact_id, email_account_id, event_type)
    values (new.workspace_id, new.campaign_id, new.enrollment_id, new.contact_id,
            new.email_account_id, 'send_scheduled');
  end if;
  return new;
end $$ language plpgsql;

create trigger campaign_event_scheduled
  after insert on public.scheduled_action
  for each row execute function public.trg_campaign_event_scheduled();

-- submission outcomes (accepted / rejected / bounces from SMTP evidence)
create or replace function public.trg_campaign_event_outcome() returns trigger as $$
declare
  ev text;
  detail text := coalesce(new.detail::text, '');
begin
  if new.status = 'accepted' then ev := 'accepted';
  elsif new.status = 'permanently_failed' and detail ~* '(5[0-9]{2}|permanent|bounce|undeliverable)' then ev := 'bounce_hard';
  elsif new.status = 'permanently_failed' then ev := 'provider_rejected';
  elsif new.status = 'rate_limited' and detail ~* '(4[0-9]{2}|temporary|deferred|try again)' then ev := 'bounce_soft';
  else ev := 'provider_rejected';
  end if;
  insert into public.campaign_event
    (workspace_id, campaign_id, enrollment_id, contact_id, email_account_id, event_type, payload)
  select sa.workspace_id, sa.campaign_id, sa.enrollment_id, sa.contact_id, sa.email_account_id,
         ev, jsonb_build_object('detail', detail, 'provider', new.provider)
  from public.scheduled_action sa where sa.id = new.action_id;
  return new;
end $$ language plpgsql;

create trigger campaign_event_outcome
  after insert on public.send_attempt
  for each row execute function public.trg_campaign_event_outcome();

-- inbound reply + classification + meeting/unsubscribe
create or replace function public.trg_campaign_event_reply() returns trigger as $$
declare
  cls text;
  cm uuid;
begin
  if new.direction <> 'inbound' then return new; end if;
  select classification, campaign_id into cls, cm from public.email_threads where id = new.thread_id;
  insert into public.campaign_event (workspace_id, campaign_id, event_type, payload)
  values (new.workspace_id, cm, 'reply', jsonb_build_object('thread_id', new.thread_id));
  if cls is not null and cls <> 'unknown' then
    insert into public.campaign_event (workspace_id, campaign_id, event_type, payload)
    values (new.workspace_id, cm, 'reply_classified', jsonb_build_object('classification', cls));
    if cls = 'meeting_requested' then
      insert into public.campaign_event (workspace_id, campaign_id, event_type)
      values (new.workspace_id, cm, 'meeting_booked');
    end if;
    if cls = 'unsubscribe' then
      insert into public.campaign_event (workspace_id, campaign_id, event_type)
      values (new.workspace_id, cm, 'unsubscribe');
    end if;
  end if;
  return new;
end $$ language plpgsql;

create trigger campaign_event_reply
  after insert on public.email_messages
  for each row execute function public.trg_campaign_event_reply();

-- derived daily aggregate (full-day rebuild; idempotent per day)
create table public.campaign_daily_stats (
  workspace_id     uuid not null,
  campaign_id      uuid not null,
  day              date not null,
  send_scheduled   int not null default 0,
  accepted         int not null default 0,
  provider_rejected int not null default 0,
  bounce_hard      int not null default 0,
  bounce_soft      int not null default 0,
  replies          int not null default 0,
  reply_classified int not null default 0,
  meeting_booked   int not null default 0,
  unsubscribe      int not null default 0,
  unique_leads_contacted int not null default 0,
  primary key (campaign_id, day)
);

create or replace function public.refresh_campaign_daily_stats(p_from date, p_to date) returns void as $$
begin
  delete from public.campaign_daily_stats
  where day between p_from and p_to;

  insert into public.campaign_daily_stats
    (workspace_id, campaign_id, day, send_scheduled, accepted, provider_rejected,
     bounce_hard, bounce_soft, replies, reply_classified, meeting_booked, unsubscribe, unique_leads_contacted)
  select workspace_id, campaign_id, occurred_at::date,
    count(*) filter (where event_type='send_scheduled'),
    count(*) filter (where event_type='accepted'),
    count(*) filter (where event_type='provider_rejected'),
    count(*) filter (where event_type='bounce_hard'),
    count(*) filter (where event_type='bounce_soft'),
    count(*) filter (where event_type='reply'),
    count(*) filter (where event_type='reply_classified'),
    count(*) filter (where event_type='meeting_booked'),
    count(*) filter (where event_type='unsubscribe'),
    count(distinct contact_id) filter (where event_type='send_scheduled')
  from public.campaign_event
  where campaign_id is not null and occurred_at::date between p_from and p_to
  group by workspace_id, campaign_id, occurred_at::date;
end $$ language plpgsql;
