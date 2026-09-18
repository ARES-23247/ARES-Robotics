# Live heading tuning transaction: generated FTC consumer verification

Date: 2026-09-18. Base commit: `dc7bf80c138c385fec596997c5f4038db2bb4b1c` on branch `codex/live-tuning-consumer`.
Following: `docs/audits/NEXT_LIVE_TUNING_CONSUMER_PROMPT.md`.
All work executed locally with a single agent. No remote pushes, pull requests, releases, or broad audits.

## Scope and integration boundary

The preceding peer review (`docs/audits/GENERATED_SAFETY_BEHAVIOR_PEER_REVIEW.md`) verified canonical startup gains and feedback freshness timeouts across exported and reopened consumers, but left unproved the live heading-tuning transaction path from network tables through authorization, acknowledgement, neutral output hold, and actual controller response.

Existing test suites established isolated layers:
- `TuningLiveRequestAuditTest` / `Nt4TuningRequestWireAuditTest`: transport topic wire formats and schemas.
- `TuningManagerTest` / `TypedTuningRuntimeTest`: throttle timing, apply policies, and state rollbacks in isolation.
- `MecanumTuningTransactionAuditTest`: FTC mecanum calibration controller transitions.

The specific unproved boundary was end-to-end execution inside the actual generated and reopened FTC consumer runtime (`ARESStarterTeleOp` / `FtcMecanumRobot`), validating that `TuningManager` updates within `updateHardwareInputs()`, queries live arm/lease context, processes `LIVE_SAFE` parameter `ftc.drive.heading.kp`, enforces neutral hold during calibration mode, applies confirmed values to the Redux store, and drives the actual controller to produce expected physical effort verified by an independent P-only oracle.

## Verified cases

The test was added to `ConsumerSafetySimulationFixture.kt` (`consumerLiveTuningBehavior`) and integrated into `GenericStarterConsumerRoundtripIntegrationTest.kt`:

1. **Unarmed rejection (`SESSION_NOT_ARMED`)**:
   - Proposed value 3.5 with nonce 1.0 on `Tuning/Parameters/ftc.drive.heading.kp/Requested` while the OpMode was running normally without calibration arming (`robot.isCalibrationModeArmed == false`).
   - `TuningManager` processed the request and published `TuningAcknowledgement(1L, "SESSION_NOT_ARMED")`, `LastResult = "SESSION_NOT_ARMED"`, and `ProcessedNonce = 1.0`.
   - Runtime gain in `robot.store.state.tuning.drive.headingGains.kP` remained unmodified at the saved canonical gain (e.g. 1.8 / 1.4).
   - Persisted canonical profile `.ares/tuning/simulation.arestuning` remained byte-identical.

2. **Authorized transaction, neutral hold, and independent P-only oracle**:
   - Armed session via production protocol: `robot.enableCalibrationMode()`, published `SysId/EnableToken` ("token-live-1"), `SysId/EnableLease` (1.0), and `SysId/Command` ("STOP").
   - Verified `robot.isCalibrationModeArmed == true` and `robot.isCalibrationNeutralOutputHoldActive == true`.
   - Advanced lease to 2.0 and published requested value 3.2 with nonce 2.0.
   - Request applied: received `TuningAcknowledgement(2L, "APPLIED")`, `LastResult = "APPLIED"`, `ProcessedNonce = 2.0`, `Current = 3.2`, and runtime store gain updated to 3.2.
   - Confirmed all four drive motor powers (FL, FR, RL, RR) remained strictly zero (< 1e-9) during calibration hold.
   - Transitioned back to control via `robot.disableCalibrationMode()`.
   - Reset pose, locked heading at 0.0 rad, and updated simulated IMU with measured heading -0.10 rad (heading error e = +0.10 rad).
   - Independent P-only oracle: omega = Kp * e = 3.2 * 0.10 = 0.32 rad/s below saturation.
   - Verified `drive.angularVelocityRadiansPerSecond == 0.32` rad/s, with positive CCW effort on right wheels (FR = RR = 0.163 in (0.01, 1.0)) and opposing effort on left wheels (FL = RL = -FR).

3. **Replay idempotency and out-of-bounds rejection (`INVALID_VALUE`)**:
   - Replay test: published same nonce 2.0 with conflicting value 4.0. `TuningManager` ignored stale nonce; runtime gain remained 3.2, `LastResult` remained `"APPLIED"`, `ProcessedNonce` remained 2.0.
   - Invalid value test: re-armed with fresh token and lease sequence 3.0/4.0; published -5.0 (below minimum 0.0) with nonce 3.0.
   - Result: received `TuningAcknowledgement(3L, "INVALID_VALUE")`, `LastResult = "INVALID_VALUE"`, `ProcessedNonce = 3.0`.
   - Runtime store gain and `Current` topic remained 3.2. Canonical `.ares` files remained byte-identical.

4. **Stop/close neutralization**:
   - Stopped OpMode via `lifecycle.stop()`, verified `lifecycle.isStarted == false`, published state `DISABLED`, and all motor powers zero.
   - Published late request 5.0 with nonce 5.0, applied full joystick command `left_stick_y = -1.0f`, and ticked lifecycle.
   - Motors remained neutralized (0.0), confirming late actuation is completely neutralized.

## Diagnostics and fixes

- **JUnit 4 assertion ordering**: Corrected `assertEquals` parameter ordering on lines 230 and 340 of `ConsumerSafetySimulationFixture.kt` where message strings were positioned as the 3rd argument (`assertEquals(message, expected, actual)` in JUnit 4).
- **Nested test XML diagnostic capture**: Added test result XML extraction to the assertion error message in `GenericStarterConsumerRoundtripIntegrationTest.kt` to ensure subproject test failures are surfaced directly in top-level Gradle logs.

## Verification evidence

- **Toolchain**: Java 17, Android SDK.
- **Versions**: ARESLib 19.1.3, Studio 7.0.63, FTC Starter 19.1.4, BioBuzz 1.1.5. No library code was modified; published coordinates remain unmodified.
- **Command**: `.\gradlew.bat :app:consumerRoundtripTest --tests com.ares.analytics.service.project.GenericStarterConsumerRoundtripIntegrationTest`
- **Result**: `BUILD SUCCESSFUL in 2m 40s` (13 actionable tasks, 2 executed, 11 up-to-date).
- **Retained evidence**:
  - `ARES-Analytics/app/build/reports/tests/consumerRoundtripTest/index.html` (100% pass rate).
  - `ARES-Analytics/app/build/consumer-roundtrip-evidence/generic/operation-3/simulator/build/test-results/test/TEST-org.firstinspires.ftc.teamcode.GenericStarterReopenedConsumerSimulationTest.xml` (5 tests completed, 0 failures).
  - `ARES-Analytics/app/build/consumer-roundtrip-evidence/generic/operation-4/simulator/build/test-results/test/TEST-org.firstinspires.ftc.teamcode.GenericStarterReopenedConsumerSimulationTest.xml` (5 tests completed, 0 failures).
- **Monorepo policy**: `powershell.exe -ExecutionPolicy Bypass -File scripts/verify-monorepo-policy.ps1` passed with 0 violations.

## Scope limits

- Validation was performed via headless desktop integration testing on Windows using simulated IO and in-process NetworkTables transport seams.
- Does not claim physical REV Control Hub execution, real Wi-Fi network latency, or native Compose UI interaction.
