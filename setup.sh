#!/usr/bin/env bash
# Validates a complete ARES-Robotics source-monorepo checkout.
# Usage: ./setup.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REQUIRED=(
  ARESLib-Kotlin
  ARES-FTC
  ARES-FRC
  ARES-FTC-Starter
  ARES-FRC-Starter
  ARES-Analytics
  release/ares-versions.properties
  build-logic/ares-versioning.gradle
)

for path in "${REQUIRED[@]}"; do
  if [[ ! -e "$ROOT/$path" ]]; then
    echo "Incomplete ARES-Robotics checkout. Missing: $path" >&2
    exit 1
  fi
done

if grep -R --include='*.gradle' --include='*.gradle.kts' -nE '\bmavenLocal[[:space:]]*\(' \
  "$ROOT/ARESLib-Kotlin" "$ROOT/ARES-FTC" "$ROOT/ARES-FRC" \
  "$ROOT/ARES-FTC-Starter" "$ROOT/ARES-FRC-Starter" "$ROOT/ARES-Analytics"; then
  echo 'Ambient mavenLocal() is forbidden.' >&2
  exit 1
fi

echo "ARES-Robotics source monorepo is ready."
echo "  Windows full matrix: ./build.ps1 -Task Test"
echo "  Per-platform commands: see AGENTS.md"
