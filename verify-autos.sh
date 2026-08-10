#!/usr/bin/env bash
set -euo pipefail

workspace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
full=false
if [[ "${1:-}" == "--full" ]]; then
  full=true
elif [[ $# -gt 0 ]]; then
  echo "Usage: ./verify-autos.sh [--full]" >&2
  exit 2
fi

run_gradle() {
  local repository="$1"
  shift
  echo
  echo "==> ${repository} $*"
  (
    cd "${workspace_root}/${repository}"
    ./gradlew "$@"
  )
}

if [[ "$full" == true ]]; then
  run_gradle ARESLib-Kotlin test --console=plain
else
  run_gradle ARESLib-Kotlin :core:test \
    --tests com.areslib.auto.AutoAuthoringRuntimeAcceptanceTest \
    --console=plain
fi

run_gradle ARESLib-Kotlin publishToMavenLocal --console=plain

if [[ "$full" == true ]]; then
  run_gradle ARES-Analytics :app:test --console=plain
  run_gradle ARES-FTC :TeamCode:testDebugUnitTest --no-daemon --console=plain
  run_gradle ARES-FRC test -Pares.usePublishedLib=true --console=plain
else
  run_gradle ARES-Analytics :app:test \
    --tests com.ares.analytics.viewmodel.pathing.AutoAuthoringAcceptanceTest \
    --console=plain
  run_gradle ARES-FTC :TeamCode:testDebugUnitTest \
    --tests org.firstinspires.ftc.teamcode.AutoAssetContractTest \
    --no-daemon --console=plain
  run_gradle ARES-FRC test -Pares.usePublishedLib=true \
    --tests com.areslib.frc.robot.FrcNativeAutoContractTest \
    --tests com.areslib.frc.robot.FRCAutoAllianceMirroringContractTest \
    --console=plain
fi

echo
echo "ARES autonomous verification passed."
