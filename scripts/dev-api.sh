#!/usr/bin/env bash
# Starts the Scala API process locally (default port 8080).
# Loads apps/api/.env.local automatically when present (values become env vars).
set -euo pipefail
cd "$(dirname "$0")/.."

env_file="apps/api/.env.local"
if [ -f "$env_file" ]; then
  set -a
  . "$env_file"
  set +a
fi

exec sbt "api/run"
