# Metric definitions (Phase 7.1)

All metrics derive from append-only `campaign_event` rows collected by
database triggers from the engine's own tables. Aggregates live in
`campaign_daily_stats` (full-day rebuild via `refresh_campaign_daily_stats`),
reconciled against raw events (see AnalyticsPipelineSpec).

| Metric | Definition | Denominator |
| --- | --- | --- |
| Unique leads contacted | distinct contact_id with a send_scheduled event | per campaign/day |
| Emails submitted | count(send_scheduled) — actions the worker picked up | — |
| Provider-accepted messages | count(accepted) send_attempt events | / emails submitted = acceptance rate |
| Hard bounces | send_attempt permanently_failed with SMTP 5xx/permanent evidence | / provider-accepted |
| Soft bounces | rate_limited/deferred with 4xx/temporary evidence | / provider-accepted |
| Reply rate | count(reply inbound messages) | / provider-accepted (delivery ≠ reply denominator: acceptance is our best delivery proxy) |
| Positive reply rate | reply_classified=interested or meeting_requested | / replies |
| Unsubscribe rate | count(unsubscribe events) | / provider-accepted |
| Meetings booked | count(meeting_booked) (deterministic meeting_requested classification; confirmation tracking arrives with CRM) | / provider-accepted |
| Pipeline value / won revenue | count+value of deal_transition events | — (dormant until the CRM module emits them) |
| Per-mailbox / per-variant performance | same events grouped by email_account_id / variant_key | same denominators |

## Distinguishers

- **Messages ≠ recipients**: emails submitted counts scheduled messages; unique leads contacted counts distinct recipients.
- **Acceptance ≠ delivery**: provider acceptance is the relay's receipt, not inbox placement. Bounce/reply events are the delivery signal.
- **Opens/clicks** are optional, privacy-sensitive and unreliable — never treated as ground truth and not used in any numerator.

## Reconciliation

`refresh_campaign_daily_stats` is a pure function of `campaign_event`.
Reconciliation = re-run the refresh for the period and compare aggregate
totals to raw event counts (AnalyticsPipelineSpec does exactly this).
