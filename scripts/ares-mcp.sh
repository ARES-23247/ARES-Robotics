#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ANALYTICS_DIR="${REPO_ROOT}/ARES-Analytics"
GRADLEW="${ANALYTICS_DIR}/gradlew"
LAUNCHER="${ANALYTICS_DIR}/mcp/build/install/mcp/bin/mcp"

GRADLE_ARGS=(":mcp:installDist" "--quiet" "--console=plain" "--no-daemon")

if [[ -n "${ARES_VERSION:-}" ]]; then
    GRADLE_ARGS+=("-ParesVersion=${ARES_VERSION}")
fi
if [[ -n "${ARES_REPOSITORY:-}" ]]; then
    GRADLE_ARGS+=("-ParesRepository=${ARES_REPOSITORY}")
elif [[ -f "${REPO_ROOT}/ARESLib-Kotlin/settings.gradle.kts" ]]; then
    CANDIDATE_REPO="${REPO_ROOT}/ARESLib-Kotlin/build/release-repository"
    if [[ -f "${CANDIDATE_REPO}/ares-candidate.properties" ]]; then
        CANDIDATE_VER="$(grep -E '^aresVersion=' "${CANDIDATE_REPO}/ares-candidate.properties" | head -n1 | cut -d= -f2- | tr -d '\r')"
        if [[ -n "${CANDIDATE_VER}" ]]; then
            GRADLE_ARGS+=("-ParesVersion=${CANDIDATE_VER}")
            GRADLE_ARGS+=("-ParesRepository=file://${CANDIDATE_REPO}")
        fi
    else
        GRADLE_ARGS+=("-ParesUseSiblingLib=true")
    fi
fi

(
    cd "${ANALYTICS_DIR}"
    "${GRADLEW}" "${GRADLE_ARGS[@]}"
) 1>&2

if [[ ! -x "${LAUNCHER}" ]]; then
    echo "[ares-mcp] Expected MCP launcher not found at ${LAUNCHER}" >&2
    exit 1
fi

EXTRA_ARGS=("$@")
HAS_TEMPLATE_DIR=0
for arg in "${EXTRA_ARGS[@]}"; do
    if [[ "${arg}" == "--template-dir" || "${arg}" == --template-dir=* ]]; then
        HAS_TEMPLATE_DIR=1
        break
    fi
done
DEFAULT_TEMPLATE_DIR="${ANALYTICS_DIR}/app/src/main/resources/project-templates"
if [[ "${HAS_TEMPLATE_DIR}" -eq 0 && -d "${DEFAULT_TEMPLATE_DIR}" ]]; then
    EXTRA_ARGS+=("--template-dir" "${DEFAULT_TEMPLATE_DIR}")
fi

exec "${LAUNCHER}" --mcp-stdio "${EXTRA_ARGS[@]}"
