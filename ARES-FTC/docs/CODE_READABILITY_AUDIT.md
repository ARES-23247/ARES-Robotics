# CODE_READABILITY_AUDIT (dated 2026-08-10)

> **Status (2026-08-16):** the "suspected defects" listed below have been resolved and are
> retained for history only — do not re-fix them. Items 1-3, 5-7, and 9 were verified fixed;
> the remaining notes informed later refactors. Consult current source before acting on
> anything in this file.

Date: 2026-08-10

## Scope and result

This audit reviewed every first-party Kotlin/Java file under:

- `TeamCode/src/main`
- `TeamCode/src/test`
- `simulator/src/main`
- `simulator/src/test` (directory absent; zero files)

The inventory contained **34 files / 3,273 pre-audit lines**:

| Category | Reviewed | Changed | Unchanged |
|---|---:|---:|---:|
| TeamCode production source | 24 | 24 | 0 |
| TeamCode test/tool source | 9 | 4 | 5 |
| Simulator source | 1 | 1 | 0 |
| Total | **34** | **29** | **5** |

The review explicitly excluded:

- `FtcRobotController/**`, which is upstream FTC SDK code;
- `build/**`, `.gradle/**`, generated class/output directories, and downloaded dependencies;
- Gradle scripts, JSON/assets, logs, binaries, and Markdown because they are not Kotlin/Java source;
- generated *outputs* from `SubsystemGenerator` (none were present). The generator source and the templates it emits were reviewed.

No Gradle task was run during this pass because other agents were editing integrated repositories. Root verification owns the sequential build/test run.

## Standards applied

- KDoc explains contracts, units, coordinate frames, lifecycle ownership, failure behavior, and non-obvious invariants—not local variable names.
- Hardware getters are documented as cache-only; `refresh()` remains the sole sensor-read path.
- Redux controllers dispatch intent/observations and never imply direct state mutation or hardware writes.
- Safety comments state the actual subsystem → `HardwareRegistry` → platform shutdown ordering.
- Heading is documented as CCW-positive radians; blue field-centric control mirrors both translation axes and not rotation.
- Periodic control paths retain allocation-neutral primitives and do not add direct hardware reads.
- Guard clauses and small expressions replace nested branches only where behavior remains equivalent.
- Placeholder `Documentation for ...` blocks and mojibake were removed from the full scope.

## Changed files

### Configuration, DSL, and state

| File | Rationale |
|---|---|
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/config/HardwareConstants.kt` | Documented canonical `fl/fr/rl/rr` names and flywheel physical assumptions. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/dsl/AresAutoDSL.kt` | Documented shared-vs-season autonomous ownership, alliance-before-pose invariant, and process-local handoff state; simplified expression bodies. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/dsl/AresTeleOpDSL.kt` | Clarified the generic lifecycle guarantee and concrete facade binding; removed boilerplate. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/dsl/SubsystemStates.kt` | Replaced field placeholders with immutable Redux intent/observation semantics and units; documented allocation-free fallback. |

### Hardware boundaries

| File | Rationale |
|---|---|
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/hardware/FtcFlywheelIO.kt` | Documented cache validity, encoder units, recoverable reads, velocity-to-open-loop fallback, voltage compensation, and registry safety. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/hardware/FtcIntakeIO.kt` | Documented absent pivot, once-per-frame cached reads, current validity, voltage fallback, and crash safety; simplified voltage validation. |

### OpModes and facade

| File | Rationale |
|---|---|
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/ARESAuto.kt` | Identified asset and alliance role. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/ARESMecanumDiagnostic.kt` | Added restrained-hardware safety contract; extracted equivalent motor alias lookup; removed variable-name comments. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/ARESMecanumTeleOp.kt` | Corrected outdated hardware description and documented pose restoration, alliance transform, and optional indicators. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/ARESRemoteDriveOpMode.kt` | Documented the atomic command watchdog, canonical NT4 keys, and zero-output failure behavior. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/AresRobot.kt` | Replaced inaccurate “drive-only” claim with composition/lifecycle/safety contract; documented optional mechanisms and facade APIs; simplified stall guard. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/ARESTuningTeleOp.kt` | Clarified local NT4 tuning scope and red-alliance initialization. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/IntakeShootTeleOp.kt` | Clarified Redux command flow and telemetry-only unwired feed trigger; removed numbered/noise comments. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/NullOpMode.kt` | Clarified its hardware-isolation purpose and no-output guarantee. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/TestAuto.kt` | Removed unused imports and documented red/blue mirroring roles. |

### Controllers and subsystems

| File | Rationale |
|---|---|
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/robot/AresDriveController.kt` | Documented axes, shaping, EMA, frames, and two-axis blue transform; formatted deadband branch without changing math. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/robot/AresSuperstructureController.kt` | Documented Redux-only responsibility, interlock, stop target, and deterministic debounce; sampled `RobotClock` once per action and named the interval. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/robot/AresTelemetryHelper.kt` | Documented rate/cap behavior and Redux lighting commands; consolidated equivalent battery display branch. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/IndicatorLightSubsystem.kt` | Clarified cached Redux stage, optional no-op behavior, rainbow sentinel, and deterministic clock. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/PrismSubsystem.kt` | Distinguished brightness power shedding from effect pulse width and documented scale clamping. |
| `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Subsystems.kt` | Documented current dwell, power behavior, RPM dispatch cadence, and emergency zero; converted stall nesting to an equivalent guard and named thresholds. |

### Simulator and tests/tools

| File | Rationale |
|---|---|
| `simulator/src/main/kotlin/org/firstinspires/ftc/teamcode/CalibrationVerificationApp.kt` | Documented independent NT4 E2E role, lifecycle sequence, fresh calibration-token handshake, bounded waits, and serialized routines. |
| `TeamCode/src/test/java/org/firstinspires/ftc/teamcode/ARESTests.kt` | Replaced placeholder narration with the allocation-free fallback contract. |
| `TeamCode/src/test/kotlin/org/firstinspires/ftc/teamcode/AresRobotTest.kt` | Removed generated local-variable KDoc, documented test scope, and placed the unchecked-cast suppression at its source. |
| `TeamCode/src/test/kotlin/org/firstinspires/ftc/teamcode/AresTeleOpBaseTest.kt` | Removed extensive placeholder KDoc and explained SDK reflection/desktop lifecycle purpose. |
| `TeamCode/src/test/kotlin/org/firstinspires/ftc/teamcode/tools/SubsystemGenerator.kt` | Corrected seven-file count and generated templates: meaningful units/contracts, once-per-refresh caching, finite fail-safe outputs, device registration, and reducer/lifecycle follow-up instructions. |

## Reviewed unchanged

These five files already had focused names/comments and no readability-only edit justified churn.
They may still appear modified in the shared worktree from the preceding behavioral-test pass:

1. `TeamCode/src/test/java/org/firstinspires/ftc/teamcode/AutoToTeleOpTransitionTest.kt`
2. `TeamCode/src/test/kotlin/org/firstinspires/ftc/teamcode/AresDriveControllerTest.kt`
3. `TeamCode/src/test/kotlin/org/firstinspires/ftc/teamcode/AresSuperstructureControllerTest.kt`
4. `TeamCode/src/test/kotlin/org/firstinspires/ftc/teamcode/hardware/FtcHardwareTest.kt`
5. `TeamCode/src/test/kotlin/org/firstinspires/ftc/teamcode/SeasonLifecycleSafetyTest.kt`

## Suspected behavior defects not changed here

These require behavior decisions/tests and were deliberately kept outside the readability pass:

1. `FtcIntakeIO.setRollerVoltage` does not reject a non-finite requested voltage. A `NaN` command can survive division/clamping and reach the motor API; it should fail closed like flywheel voltage commands.
2. `FlywheelSubsystem.readSensors` does not consult `FlywheelIO.velocityValid`. It can dispatch zero RPM for an invalid sample, safely preventing “ready” state but conflating sensor loss with a stopped wheel in telemetry/state.
3. Remote drive accepts non-finite `vx/vy/omega` and treats malformed `reset` fields as zero. Inputs should be validated atomically before motion or pose reset rather than partially defaulting.
4. `AresDriveController.driveWithGamepad` maps left-stick X to ARES field +X (forward) and negated left-stick Y to field +Y (left). This appears transposed relative to conventional FTC stick semantics and should be confirmed on hardware before changing it.
5. `AresDriveController.processAxis` does not explicitly reject non-finite or out-of-range normalized inputs. Lower layers may clamp magnitude, but a `NaN` can propagate through shaping.
6. Flywheel target RPM from live tuning is finite-checked by hardware IO but not bounded to the configured physical maximum before closed-loop velocity assignment.
7. `NullOpMode` continuously updates telemetry without `idle()`/sleep and may unnecessarily busy-spin the OpMode thread.
8. `CalibrationVerificationApp` counts non-empty polling observations rather than unique/fresh calibration frames, so one repeated array can satisfy its data-count assertion. It also terminates with `System.exit` rather than closing NT publishers/subscribers/instance, which limits reuse as an embedded test.
9. `AresTelemetryHelper` displays a non-finite battery observation rather than classifying it as invalid/low.

## Verification recommendation

After integrated edits settle, run from the ARES-FTC repository root:

```powershell
.\gradlew.bat :TeamCode:testDebugUnitTest :TeamCode:assembleDebug --no-daemon
.\gradlew.bat :simulator:compileKotlin --no-daemon
```
