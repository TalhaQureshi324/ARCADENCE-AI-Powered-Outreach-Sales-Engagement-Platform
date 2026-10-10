#!/usr/bin/env bash
# Starts the Scala Worker process locally.
# Loads apps/worker/.env.local automatically when present (values become env vars).
set -euo pipefail
cd "$(dirname "$0")/.."

env_file="apps/worker/.env.local"
if [ -f "$env_file" ]; then
  set -a
  . "$env_file"
  set +a
fi

exec sbt "worker/run"
