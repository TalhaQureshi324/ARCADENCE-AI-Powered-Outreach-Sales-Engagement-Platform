# Arcadence Web (Next.js) self-host container — Phase 15.6 (Hostinger readiness).
# Used ONLY if/when the frontend moves from Vercel to the VPS (see
# docs/HOSTINGER_MIGRATION.md). Vercel remains the primary frontend host until
# the owner explicitly switches.
#
# NOTE: NEXT_PUBLIC_* variables are inlined into the JS bundle at BUILD time —
# they are passed as build args and must be provided on every rebuild
# (docs/ENVIRONMENT.md classifies them PUBLIC).

# --- deps ----------------------------------------------------------------------
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

# --- build ---------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ARG NEXT_PUBLIC_SUPABASE_URL=""
ARG NEXT_PUBLIC_SUPABASE_ANON_KEY=""
ARG NEXT_PUBLIC_API_URL=""
ARG NEXT_PUBLIC_GOOGLE_OAUTH_ENABLED=""
ENV NEXT_PUBLIC_SUPABASE_URL=$NEXT_PUBLIC_SUPABASE_URL \
    NEXT_PUBLIC_SUPABASE_ANON_KEY=$NEXT_PUBLIC_SUPABASE_ANON_KEY \
    NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL \
    NEXT_PUBLIC_GOOGLE_OAUTH_ENABLED=$NEXT_PUBLIC_GOOGLE_OAUTH_ENABLED
RUN npm run build

# --- run -----------------------------------------------------------------------
FROM node:22-alpine AS run
WORKDIR /app
ENV NODE_ENV=production
RUN addgroup -S arcadence && adduser -S -G arcadence arcadence
COPY --from=build --chown=arcadence:arcadence /app/.next ./.next
COPY --from=build --chown=arcadence:arcadence /app/node_modules ./node_modules
COPY --from=build --chown=arcadence:arcadence /app/package.json ./
USER arcadence
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:3000/login || exit 1
CMD ["npm", "run", "start"]
