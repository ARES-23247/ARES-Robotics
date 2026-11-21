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
  ARES-XRP-Starter
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

"$ROOT/scripts/verify-imported-histories.sh"

if command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN="python3"
elif command -v python >/dev/null 2>&1; then
  PYTHON_BIN="python"
else
  echo "Python 3 is required to run monorepo policy verification." >&2
  exit 1
fi

"$PYTHON_BIN" "$ROOT/scripts/verify_agent_guidance.py"
"$PYTHON_BIN" "$ROOT/scripts/verify_kotlin_toolchain.py"
"$PYTHON_BIN" "$ROOT/scripts/generate_codebase_ledger.py" --verify

if grep -R --include='*.gradle' --include='*.gradle.kts' -nE '\bmavenLocal[[:space:]]*\(' \
  "$ROOT/ARESLib-Kotlin" "$ROOT/ARES-FTC" "$ROOT/ARES-FRC" \
  "$ROOT/ARES-FTC-Starter" "$ROOT/ARES-FRC-Starter" "$ROOT/ARES-Analytics"; then
  echo 'Ambient mavenLocal() is forbidden.' >&2
  exit 1
fi

echo "ARES-Robotics source monorepo is ready."
echo "  Windows full matrix: ./build.ps1 -Task Test"
echo "  Per-platform commands: see AGENTS.md"
echo "  XRP standalone verify: cd ARES-XRP-Starter && ./ares verify"
