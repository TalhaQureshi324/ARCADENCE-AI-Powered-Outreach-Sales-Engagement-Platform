#!/usr/bin/env bash
# Starts the Scala API process locally (default port 8080).
# Copy apps/api/.env.example to apps/api/.env.local to override configuration.
set -euo pipefail
cd "$(dirname "$0")/.."
exec sbt "api/run"
