#!/usr/bin/env bash
# Starts the Scala Worker process locally.
# Copy apps/worker/.env.example to apps/worker/.env.local to override configuration.
set -euo pipefail
cd "$(dirname "$0")/.."
exec sbt "worker/run"
