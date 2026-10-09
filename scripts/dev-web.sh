#!/usr/bin/env bash
# Starts the Next.js frontend in development mode (default port 3000).
# Copy apps/web/.env.example to apps/web/.env.local to override configuration.
set -euo pipefail
cd "$(dirname "$0")/../apps/web"
exec npm run dev
