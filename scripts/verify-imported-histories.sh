#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

imports=(
  'ARESLib-Kotlin|f11d9cb38232e209d412944477ceb887149e3851|13599358be7fb87d44e9804e798c7f27c6e84b21'
  'ARES-FTC|03e2bdf25086e1ca1ee5613b075e81b51e3dadc4|0cb74896a60fd7a3b46bb70ca04440aaac91fc6a'
  'ARES-FRC|ed3b5575046d08612464b6d91ecd3468a454d924|98e2ab05e3a864e9738e9eb56965997412f8b581'
  'ARES-FTC-Starter|4b27c2ee501afffb5155e200183d1a2ea3794ae6|8db9f3651cea58cc0af038919f9b2b1f48fb67e3'
  'ARES-FRC-Starter|17411eceaeac6e88ab4ed5b08f5f4c6f1c298005|eab3a8db8a19f7f9153a6eaa12192f78da673c8a'
  'ARES-Analytics|242fa8ab176bff1e4a3951b44d76fd3ef6425f25|09e00086c3d0ec29bcd2c11f6805c35d42e7267d'
)

for record in "${imports[@]}"; do
  IFS='|' read -r path import_commit source_commit <<< "$record"
  git cat-file -e "${import_commit}^{commit}"
  git cat-file -e "${source_commit}^{commit}"
  git merge-base --is-ancestor "$source_commit" HEAD
  imported_tree="$(git rev-parse "${import_commit}:${path}")"
  source_tree="$(git rev-parse "${source_commit}^{tree}")"
  if [[ "$imported_tree" != "$source_tree" ]]; then
    echo "$path import tree differs from its recorded source commit." >&2
    exit 1
  fi
  echo "verified $path history and import tree"
done
