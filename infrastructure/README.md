# infrastructure/

Deployment and operations artifacts: Docker images, Compose files, TLS proxy, staging configuration.

## Contents

| File | Purpose |
| --- | --- |
| `docker-compose.yml` | Local run of API + worker against the local Supabase stack (`host.docker.internal`) |
| `docker-compose.staging.yml` | **Staging topology (Phase 15.3)**: Caddy TLS proxy + api + worker; healthchecks, memory limits, log rotation, graceful-stop periods; worker sending-safety defaults (`SENDING_ENABLED=false`) |
| `Caddyfile` | TLS termination + HSTS for the public API hostname |
| `staging.env.example` | Staging environment template — copy to `.env`, fill in, never commit |
| `docker-compose.hostinger.yml` | **Production topology (Phase 15.6)**: Caddy (app + api hosts) + web + api + worker; sending paused by default |
| `Caddyfile.hostinger` | TLS for the two production origins |
| `hostinger.env.example` | Production env template — fresh secrets, never copied from staging |
| `apps/web/Dockerfile` | Self-host Next.js container (built + smoke-tested 15.6; used when the frontend migrates off Vercel) |
| `docs/HOSTINGER_MIGRATION.md` | **The migration plan** — DNS/TLS/OAuth/redirects/secrets/worker-handoff/rollback/downtime + backup/restore |

## Building the images

Both services use sbt-native-packager (`JavaAppPackaging` + `DockerPlugin`, base `eclipse-temurin:21-jre-noble`, **non-root uid 1001**):

```bash
# Tag with the git SHA for reproducible deploys and rollback (recommended)
GIT_SHA=$(git rev-parse --short HEAD)
sbt -batch "api/Docker/publishLocal" "worker/Docker/publishLocal"
docker tag arcadence/arcadence-api:0.1.0-SNAPSHOT arcadence/arcadence-api:$GIT_SHA
docker tag arcadence/arcadence-worker:0.1.0-SNAPSHOT arcadence/arcadence-worker:$GIT_SHA
```

Distribution options: push to a private registry (GHCR/Docker Hub) and `docker pull` on the host, or `docker save | ssh host 'docker load'` (no registry needed for a single VPS).

## Staging deployment (Phase 15.3+)

1. Provision the VPS (owner action — see `docs/MANUAL_SETUP.md` #10).
2. Copy `docker-compose.staging.yml`, `Caddyfile`, `staging.env.example` to the host; `cp staging.env.example .env` and fill in.
3. Load/pull images tagged with the git SHA; set `API_IMAGE_TAG=$GIT_SHA`.
4. `docker compose -f docker-compose.staging.yml up -d`.
5. Smoke tests (spec §5/§11): `curl https://$API_HOST/health` returns 200, `curl .../ready` returns 200 (DB round-trip), worker logs show the startup line with `sending DISABLED` and a heartbeat every `WORKER_TICK_INTERVAL_MS`.

**Deployment happens only with the product owner's explicit authorization.**

## Queue-health queries (staging monitoring)

Run against the staging DB (read-only):

```sql
-- queue lag: pending + claimed depth by type
select type, status, count(*) from public.scheduled_action group by 1, 2;

-- job failures in the last hour (dead-letter watch)
select count(*) from public.scheduled_action
where status = 'failed' and updated_at > now() - interval '1 hour';

-- oldest pending action age (lag signal)
select now() - min(run_after) as lag from public.scheduled_action
where status = 'pending' and run_after <= now();
```
