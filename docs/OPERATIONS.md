# Arcadence — Operations Runbook

Operational procedures for the sending engine (Phase 5.5). Update alongside the runbook items below as phases land.

## Sending engine (Phase 5)

### Components

- **API** (`apps/api`): enqueues `send_email` actions into `public.scheduled_action` (the single sending queue). Does not send.
- **Worker** (`apps/worker`): independent process. Every tick: recovers expired leases, claims due jobs with FOR UPDATE SKIP LOCKED, runs the send handler, records outcomes. Resumes after restart.
- **Event delivery**: `public.event_outbox` — written with the state change, dispatched at-least-once by the worker. Never carries sends.

### Metrics (observed in ReliabilityLoadSpec)

- End-to-end throughput at test scale: ~8 sends/second per worker set of 6 (dominated by per-send evidence writes: outbound_message + send_attempt + job_attempt + event rows, 6-9 transactions per send). Scale workers horizontally; keep pool size >= worker count (pool starvation shows as `Connection is not available, request timed out after 30008ms`).
- Queue lag: time from `run_after` due to claim; watch `select count(*), min(run_after) from scheduled_action where status='pending' and run_after <= now()`.
- Failure rates: `select status, count(*) from send_attempt where created_at > now() - interval '1 day' group by status`.

### Runbook

1. **Worker crash** — restart the process. Leases expire (default 300s) and jobs return to pending automatically. Nothing is lost; nothing duplicates (evidence-gated).
2. **Action held as `outcome_unknown`** (`status='failed'`, `last_error like 'outcome_unknown%'`) — the provider submission was ambiguous. Before ANY resend: reconcile against the provider (Gmail/Graph search by Message-ID from `outbound_message.headers`; SMTP: scan logs). If NOT delivered, set the row back to `status='pending', attempts=0, run_after=now()` and clear `last_error`. If delivered, set `status='done'`.
3. **Account suspended by circuit breaker** (`status='suspended'`, health event `paused` with auth-failure reason) — fix credentials, then reconnect via the Email Accounts page (sets status connected and clears the breaker by reconnection).
4. **Campaign stuck with pending actions** — verify campaign status is `active` (cancelled/paused campaigns skip at send time) and enrollment `next_action_at` is not in the future.
5. **Duplicate-send paranoia** — check `select action_id, count(*) from outbound_message group by action_id having count(*) > 1` (should always be 0; the unique partial index makes it structurally impossible).
6. **Pool exhaustion** — "Connection is not available" from Hikari: increase pool size or reduce worker count; each in-flight send holds one connection per transaction briefly.


## Scaling & performance (Phase 13.4)

Measured with ReliabilityLoadSpec (20-50 jobs, 6 workers, pooled transactor):

| Metric | Measured (local) | Production target |
| --- | --- | --- |
| Job throughput | ~8 jobs/s end-to-end incl. evidence writes | scale workers horizontally |
| Job claim integrity | 0 double-claims, 6 workers concurrent | invariant (SKIP LOCKED) |
| DB connections | Hikari 10 API + 6 worker | tune per Supabase plan |

Scaling levers in order: worker count -> Hikari pool -> queue batching.
Run the load harness against production-size data before publishing capacity
numbers - local results are not production capacity.

## Backup & disaster recovery

- Supabase managed backups (plan-dependent) plus owner-run nightly
  `pg_dump --schema=public` to object storage.
- Restore drill: load dump into staging, run the migrations CI check to
  verify schema, spot-check RLS policies.
- Worker recovery: stateless restart; expired leases re-queue automatically.
- Duplicate replay safety: webhook_deliveries.replay + idempotent billing_events.
- Rollback: redeploy previous image tag; DB migrations are append-only -
  roll back code, never schema.

## Monitoring & alerts (owner to provision)

- /health endpoints (API + worker) for uptime checks.
- Alert on: repeated sms_failed / ai_provider_error / billing webhook failures;
  bounce-rate (Phase 8 auto-pauses first); Hikari connection-wait timeouts.
