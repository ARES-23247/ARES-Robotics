#!/usr/bin/env bash
# Clones the four ARES subprojects as siblings of this script.
# Idempotent: existing directories are skipped (local work is never overwritten).
#
# Usage:  ./setup.sh

set -euo pipefail

ORG="ARES-23247"
REPOS=(ARESLib-Kotlin ARES-FTC ARES-FRC ARES-Analytics)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for repo in "${REPOS[@]}"; do
  dest="$ROOT/$repo"
  if [ -d "$dest" ]; then
    echo "skip   $repo (exists: $dest)"
    continue
  fi
  url="https://github.com/$ORG/$repo.git"
  echo "clone  $repo <- $url"
  git clone "$url" "$dest"
done

echo ""
echo "Workspace ready. Next steps:"
echo "  1. Build foundation first:  cd ARESLib-Kotlin && ./gradlew publishToMavenLocal"
echo "  2. Per-project build/test/run commands: see AGENTS.md"
