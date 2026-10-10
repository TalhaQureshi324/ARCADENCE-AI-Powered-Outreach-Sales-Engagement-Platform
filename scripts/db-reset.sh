#!/usr/bin/env bash
# Resets the local Supabase database: drops and recreates it, then applies all
# migrations from supabase/migrations/ in order (ADR-0018 workflow).
# Requires: Supabase CLI + Docker engine running.
set -euo pipefail
cd "$(dirname "$0")/.."

resolve_supabase() {
  if command -v supabase >/dev/null 2>&1; then
    echo "supabase"
  elif [ -x "$LOCALAPPDATA/Programs/supabase/supabase.exe" ]; then
    echo "$LOCALAPPDATA/Programs/supabase/supabase.exe"
  else
    echo "ERROR: Supabase CLI not found. See docs/SETUP.md." >&2
    exit 1
  fi
}

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker engine is not running. Start Docker Desktop first." >&2
  exit 1
fi

"$(resolve_supabase)" db reset
echo "Local database reset complete."
