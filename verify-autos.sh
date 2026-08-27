#!/usr/bin/env bash
set -euo pipefail

workspace_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ares_base="$(sed -n 's/^aresVersion=//p' "${workspace_root}/release/ares-versions.properties")"
commit="$(git -C "${workspace_root}" rev-parse --short=12 HEAD)"
candidate_version="${ares_base}-rc.${commit}"
validation_repository="file://${workspace_root}/ARESLib-Kotlin/build/release-repository"
consumer_args=("-ParesVersion=${candidate_version}" "-ParesRepository=${validation_repository}")
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
    ./gradlew "$@" --no-parallel --no-daemon
  )
}

if [[ "$full" == true ]]; then
  run_gradle ARESLib-Kotlin test --console=plain
else
  run_gradle ARESLib-Kotlin :core:test \
    --tests com.areslib.routine.RoutineDocumentTest \
    --tests com.areslib.routine.RoutineManagerTest \
    --tests com.areslib.routine.AutonomousCatalogTest \
    --tests com.areslib.controls.ControlSchemeValidationTest \
    --tests com.areslib.controls.ControllerProfileDocumentTest \
    --tests com.areslib.input.DigitalBindingTest \
    --tests com.areslib.input.AnalogBindingTest \
    --tests com.areslib.input.ButtonSuppressionTest \
    --tests com.areslib.codegen.AresKotlinProjectGeneratorTest \
    --tests com.areslib.codegen.AresProjectCodegenCliTest \
    --console=plain
  run_gradle ARESLib-Kotlin :ftc-hardware:test \
    --tests com.areslib.ftc.input.FtcInputFrameAdapterTest \
    --console=plain
  run_gradle ARESLib-Kotlin :frc-runtime:test \
    --tests com.areslib.frc.input.FrcInputFrameAdapterTest \
    --console=plain
fi

run_gradle ARESLib-Kotlin publishReleaseValidation \
  "-ParesVersion=${candidate_version}" \
  --console=plain

if [[ "$full" == true ]]; then
  run_gradle ARES-Analytics :app:test "${consumer_args[@]}" --console=plain
  run_gradle ARES-FTC :TeamCode:verifyAresProject "${consumer_args[@]}" --console=plain
  run_gradle ARES-FTC :TeamCode:testDebugUnitTest "${consumer_args[@]}" --console=plain
  run_gradle ARES-FRC verifyAresProject "${consumer_args[@]}" --console=plain
  run_gradle ARES-FRC test "${consumer_args[@]}" --console=plain
else
  run_gradle ARES-Analytics :app:test \
    "${consumer_args[@]}" \
    --tests com.ares.analytics.viewmodel.project.ProjectDocumentRepositoriesTest \
    --tests com.ares.analytics.viewmodel.routine.RoutineEditorModelTest \
    --tests com.ares.analytics.viewmodel.controls.ControlsEditorViewModelTest \
    --console=plain
  run_gradle ARES-FTC :TeamCode:verifyAresProject "${consumer_args[@]}" --console=plain
  run_gradle ARES-FTC :TeamCode:testDebugUnitTest \
    "${consumer_args[@]}" \
    --tests org.firstinspires.ftc.teamcode.FtcFieldAssetContractTest \
    --tests org.firstinspires.ftc.teamcode.FtcAutoLifecycleTest \
    --tests org.firstinspires.ftc.teamcode.FtcGeneratedRuntimeTest \
    --tests org.firstinspires.ftc.teamcode.FtcAutonomousSelectorTest \
    --console=plain
  run_gradle ARES-FRC verifyAresProject "${consumer_args[@]}" --console=plain
  run_gradle ARES-FRC test "${consumer_args[@]}" \
    --tests com.areslib.frc.robot.FrcNativeAutoContractTest \
    --tests com.areslib.frc.robot.FRCAutoAllianceMirroringContractTest \
    --tests com.areslib.frc.generatedruntime.FrcGeneratedRoutineRuntimeTest \
    --console=plain
fi

echo
echo "ARES routines, controls, code generation, and autonomous verification passed."
