# Arcadence — Production Deployment (Phase 13.5)

## Environments

| Environment | Frontend | API + Worker | Database |
| --- | --- | --- | --- |
| Development | localhost:3000 | localhost:8080 + worker | local Supabase (port 5433) |
| Staging | Vercel preview | VPS container (Docker) | Supabase staging project |
| Production | Vercel | VPS container (Docker Compose) | Supabase production project |

## API + Worker (Docker)

1. Build: `sbt api/Docker/publishLocal` and `sbt worker/Docker/publishLocal`
   (or build CI images from the GitHub workflow).
2. Required env (secret manager, never committed): `DATABASE_URL`,
   `SUPABASE_JWT_ISSUER`, `SUPABASE_JWT_AUDIENCE`, `SUPABASE_JWKS_URL`,
   `EMAIL_ENCRYPTION_KEY`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`,
   `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL`, Gmail/Microsoft OAuth client ids
   and secrets.
3. Health: `GET /health` must return 200 before marking the container live.
4. Rollback: redeploy previous image tag. DB migrations are append-only —
   roll back code, not schema.

## Frontend (Vercel)

- Env: `NEXT_PUBLIC_SUPABASE_URL`, `NEXT_PUBLIC_SUPABASE_ANON_KEY`,
  `NEXT_PUBLIC_API_URL`.
- Deploy on merge to main; preview deploys on PRs.

## Database

- Apply migrations with `supabase db push` (verified in CI against plain
  PostgreSQL). Never edit production schema by hand.
- Backups per `docs/OPERATIONS.md`.

## Canary / staged rollout

Deploy the API to one instance first (single VPS: stop old, start new),
smoke-test `/health` + one authenticated endpoint, then scale to full fleet.
Frontend: Vercel immediate rollback to previous deployment.

## Stripe go-live

1. Replace test keys with live keys (`STRIPE_SECRET_KEY`,
   `STRIPE_WEBHOOK_SECRET`).
2. Create live prices and map them in `BillingRoutes` priceMap.
3. Register the webhook endpoint `POST /api/v1/stripe/webhook` in Stripe
   dashboard for subscription lifecycle events.
