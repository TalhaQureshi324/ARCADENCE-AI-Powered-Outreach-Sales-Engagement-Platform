#!/usr/bin/env bash
# Verifies the Arcadence toolchain is present and prints versions.
# Usage: scripts/check-toolchain.sh
set -uo pipefail

fail=0

resolve_supabase() {
  if command -v supabase >/dev/null 2>&1; then
    echo "supabase"
  elif [ -x "$LOCALAPPDATA/Programs/supabase/supabase.exe" ]; then
    echo "$LOCALAPPDATA/Programs/supabase/supabase.exe"
  else
    echo ""
  fi
}

resolve_docker() {
  if command -v docker >/dev/null 2>&1; then
    echo "docker"
  elif [ -x "/c/Program Files/Docker/Docker/resources/bin/docker.exe" ]; then
    echo "/c/Program Files/Docker/Docker/resources/bin/docker.exe"
  else
    echo ""
  fi
}

check() {
  local name="$1" hint="$2"
  shift 2
  if command -v "$name" >/dev/null 2>&1; then
    local version
    version=$("$@" 2>&1 | head -1)
    echo "OK      $name: $version"
  else
    echo "MISSING $name — $hint"
    fail=1
  fi
}

echo "Arcadence toolchain check"
check node "install Node.js 22+ LTS (https://nodejs.org)" node --version
check npm "ships with Node.js" npm --version
check java "install Temurin JDK 21 LTS (winget install EclipseAdoptium.Temurin.21.JDK)" java -version
check sbt "install sbt (winget install sbt.sbt)" sbt --version

SUPABASE_BIN="$(resolve_supabase)"
if [ -n "$SUPABASE_BIN" ]; then
  echo "OK      supabase: $("$SUPABASE_BIN" -v 2>&1 | head -1)"
else
  echo "MISSING supabase — install from https://github.com/supabase/cli/releases (or scoop install supabase)"
  fail=1
fi

DOCKER_BIN="$(resolve_docker)"
if [ -n "$DOCKER_BIN" ]; then
  echo "OK      docker: $("$DOCKER_BIN" --version 2>&1 | head -1)"
  if "$DOCKER_BIN" info >/dev/null 2>&1; then
    echo "OK      docker engine: running"
  else
    echo "WARN    docker engine: not running — start Docker Desktop"
  fi
else
  echo "MISSING docker — install Docker Desktop (winget install Docker.DockerDesktop)"
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "One or more required tools are missing. See docs/SETUP.md and docs/MANUAL_SETUP.md."
  exit 1
fi
echo
echo "Toolchain OK."
