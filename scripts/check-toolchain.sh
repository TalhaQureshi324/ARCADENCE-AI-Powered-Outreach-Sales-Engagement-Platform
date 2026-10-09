#!/usr/bin/env bash
# Verifies the Arcadence toolchain is present and prints versions.
# Usage: scripts/check-toolchain.sh
set -uo pipefail

fail=0

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

if [ "$fail" -ne 0 ]; then
  echo
  echo "One or more required tools are missing. See docs/SETUP.md and docs/MANUAL_SETUP.md."
  exit 1
fi
echo
echo "Toolchain OK."
