#!/usr/bin/env bash
# Runs every test suite: Scala units via sbt, frontend units via Vitest.
# Usage: scripts/test-all.sh
set -euo pipefail
cd "$(dirname "$0")/.."

echo "== Scala tests =="
sbt test

echo
echo "== Frontend tests =="
(cd apps/web && npm test)

echo
echo "All test suites passed."
