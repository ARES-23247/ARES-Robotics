# Gemini Operator Path Audit: Studio & Lightbot Live Tuning

Worker submission; independent corrections and actual validation are recorded in
[the coordinator checkpoint](AGY_TUNING_CHECKPOINT.md). Submission claims are not acceptance.

**Date:** 2026-09-23
**Base Commit:** `9426ee3c86f331910b2ce1656e33c774506a44e1`
**Boundary:** Studio operator tuning UI and Lightbot FTC OpMode live calibration path.

## 1. Checked Source Paths

- `ARES-FTC/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/AresRobot.kt` (owned edit)
- `ARES-FTC/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/ARESTuningTeleOp.kt` (owned review)
- `ARES-Analytics/app/src/main/kotlin/com/ares/analytics/ui/screens/TuningScreen.kt` (owned review)
- `ARES-Analytics/app/src/main/kotlin/com/ares/analytics/ui/components/tuning/GainTuningPanel.kt` (owned review)
- `ARES-Analytics/app/src/main/kotlin/com/ares/analytics/ui/components/tuning/TuningSysIdComponents.kt` (owned review)
- `ARES-FTC/TeamCode/src/test/kotlin/org/firstinspires/ftc/teamcode/opmodes/AresTuningOperatorAuditTest.kt` (owned new test)
- `ARES-FTC/TeamCode/src/test/kotlin/org/firstinspires/ftc/teamcode/FtcSeasonLifecycleAuditTest.kt` (read-only fixture pattern)
- `ARESLib-Kotlin/core/src/main/kotlin/com/areslib/tuning/TypedTuningRuntime.kt` (read-only contract)
- `ARESLib-Kotlin/core/src/main/kotlin/com/areslib/tuning/TuningManager.kt` (read-only contract)
- `ARESLib-Kotlin/ftc-hardware/src/main/kotlin/com/areslib/ftc/calibration/FtcMecanumCalibrationController.kt` (read-only contract)

## 2. Findings and Decisions

1. **FTC Disabled-Only Calibration Context Wiring (Fixed in `AresRobot.kt`):**
   - *Finding:* In `AresRobot.kt`, `TuningApplyContext` omitted `outputsNeutralAndInhibited = base.isCalibrationNeutralOutputHoldActive`. Because active FTC OpModes have no WPILib-style disabled state (`robotDisabled` is permanently `false`), `DISABLED_ONLY` parameters failed closed with `ROBOT_MUST_BE_DISABLED` even during stationary neutral holds under an active `STOP` lease.
   - *Decision:* Passed `outputsNeutralAndInhibited = base.isCalibrationNeutralOutputHoldActive` to `TuningApplyContext` in `contextProvider`, matching `ARES-FTC-Starter` and `TypedTuningRuntime.kt`.
2. **Studio Operator UI & OpMode Inspection:**
   - *Mode Selection & Gain Tuning:* "Guided experiment" vs "Advanced profiles & calibration" correctly isolates profiles without writing canonical files. Heading gain (`drive.headingKp`, `LIVE_SAFE`) routes requests and atomic acknowledgements cleanly.
   - *Arming & Gating:* `ARESTuningTeleOp` enables calibration on `onStart`. Arming requires a fresh non-retained token + `STOP` command. Gamepad drive is gated while `isCalibrationModeArmed` is true and restores on disarm.
   - *UI Banner Styling:* String-matching banner color was evaluated and deferred as brittle; UI verified as no-defect at source scope.

## 3. Regression Coverage

- Added `AresTuningOperatorAuditTest.kt` following `FtcSeasonLifecycleAuditTest.kt`.
- Mocks `FtcMecanumRobot` and `TuningManager` construction to capture the real `contextProvider` lambda (`arguments()[2]`).
- Constructs `AresRobot(mock HardwareMap)` normally and exercises the captured lambda against mocked base states:
  - Unarmed: `sessionArmed = false`, `outputsNeutralAndInhibited = false`.
  - Armed active motion: `sessionArmed = true`, `outputsNeutralAndInhibited = false`.
  - Armed stationary hold: `sessionArmed = true`, `outputsNeutralAndInhibited = true`.
- Asserts calibration parameter UIDs and cleans up `RobotFieldManager`, `PoseEstimator`, `NamedCommands`, and `robot.close()`. Fails directly if the `AresRobot.kt` wiring is reverted.

## 4. Limits & Tests Not Run

- **Tests Not Run:** No test commands, Gradle tasks, or compilation were executed (strict file-tools-only constraint). Test execution is deferred to the coordinator.
- **Evidence Boundary:** Verifies constructor callback wiring under mock IO, not physical motor neutralization or wire telemetry.
- **GUI & Hardware Limits:** No Compose desktop window, display server, physical robot, or live socket was observed. Source-level verification does not constitute GUI acceptance or hardware validation.
