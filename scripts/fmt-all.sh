#!/usr/bin/env bash
# Formats all code: Scala via scalafmt, frontend via Prettier.
set -euo pipefail
cd "$(dirname "$0")/.."

sbt scalafmtAll
(cd apps/web && npm run format)

echo "Formatting complete."
