# Bounded Consumer Round-Trip & Real Generation Recovery Review

Date: 2026-09-17. Base commit: `d1ff42edf`, branch `codex/consumer-roundtrip-integration`.
Reviewed consumer journeys and generation recovery tests locally on Windows desktop JVM (JDK 17).
This is a strictly local verification checkpoint; no merge, push, publication, or deployment was performed.

## 1. Scope & Execution

Completed the full consumer round-trip proofs and real generation interruption/recovery test suites:
1. **BioBuzz Consumer Round-Trip (`BiobuzzConsumerRoundtripIntegrationTest.kt`):**
   - Extracts real bundled BioBuzz template (`ARES-BIOBUZZ-Example-1.1.5.zip`).
   - Opens project in `ProjectSession`, verifying initial documents.
   - Mutates 5 configuration categories:
     - Field settings (`fieldLengthMeters = 3.58`, `fieldWidthMeters = 3.58`).
     - Subsystem / Hardware (`feedbackTimeoutMs = 120` in `biobuzz-intake.aressubsystem`).
     - Tuning profile (`ftc.drive.heading.kp = 2.4` in `simulation.arestuning`).
     - Controls (`feed-ball` deadband = 0.10 in `driver.arescontrols`).
     - Autonomous catalog (`custom-biobuzz-auto` entry + `sample-routine.aresroutine`).
   - Keeps working `USER-OWNED` extension (`TeamRobotExtensions.kt`) compiled into the consumer.
   - Exports project via `ProjectArchiveExporter.export` to `.aresproject.zip`.
   - Extracts safely into fresh destination via `ProjectArchiveExporter.extract`.
   - Reopens in a fresh `ProjectSession` and independently asserts all 5 configuration categories.
   - Asserts byte-identical preservation of `TeamRobotExtensions.kt`.
   - Regenerates Kotlin source via `ProjectBuildService.generateAresProject(...)` with the real wrapper.
   - Verifies multi-file determinism across all generated files (main, test, drivebase directories and `ares-project-verification.json`).
   - Verifies reopened field dimensions and preserved extensions in generated source.
   - Compiles and runs consumer robot and simulator tests via `ProjectBuildService.runBuild(...)` executing `:TeamCode:verifyAresProject`, `:TeamCode:testDebugUnitTest`, `:simulator:test`, and `:TeamCode:assembleDebug`.
   - Proves simulated IO responds to joystick and commands drive and intake motors.

2. **Generic FTC Starter Consumer Round-Trip (`GenericStarterConsumerRoundtripIntegrationTest.kt`):**
   - Creates new project from official generic starter template (`ARES-FTC-Starter-19.1.4.zip`).
   - Opens project in `ProjectSession`, verifying initial documents.
   - Mutates 5 configuration categories:
     - Field settings (`fieldLengthMeters = 3.60`, `fieldWidthMeters = 3.60`).
     - Subsystem / Hardware (custom `gripper` subsystem with `feedbackTimeoutMs = 180`).
     - Tuning profile (`ftc.drive.heading.kp = 2.1` in `simulation.arestuning`).
     - Controls (`drive-forward` deadband = 0.08 in `driver.arescontrols`).
     - Autonomous catalog (`starter-autonomous-routine` entry + `sample-routine.aresroutine`).
   - Keeps working `USER-OWNED` extension (`TeamRobotExtensions.kt`) compiled into the consumer.
   - Exports project via `ProjectArchiveExporter.export` to `.aresproject.zip`.
   - Extracts safely into fresh destination via `ProjectArchiveExporter.extract`.
   - Reopens in a fresh `ProjectSession` and independently asserts all 5 configuration categories.
   - Asserts byte-identical preservation of `TeamRobotExtensions.kt`.
   - Regenerates Kotlin source via `ProjectBuildService.generateAresProject(...)` with real wrapper.
   - Verifies multi-file determinism across all generated files.
   - Compiles and runs consumer robot and simulator tests via `ProjectBuildService.runBuild(...)`.
   - Proves simulated IO responds to joystick drive commands.

3. **Real Generation Interruption & Recovery (`ProjectGenerationRecoveryIntegrationTest.kt`):**
   - **Intermediate-Write Failure:** Controlled failure injected after real generator begins and produces disk output; asserts intermediate write exists; verifies failure diagnostics; verifies canonical `.ares` documents and user extensions are intact; verifies downstream build rejection; verifies clean recovery on retry.
   - **Real Process Cancellation:** Cancels active generation while running; verifies process tree termination; verifies failure diagnostics; verifies canonical `.ares` documents and user extensions are intact; verifies clean recovery on retry.

---

## 2. Environment & Invariants

- **JVM & Platform:** OpenJDK 17, Windows desktop JVM.
- **Candidate Repository:** `file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-project-roundtrip/ARESLib-Kotlin/build/release-repository`
- **Candidate Version:** `19.1.3-rc.roundtrip.dbb5b9f.1`
- **Candidate Tree Identity:** `4161ce50ae762d9ba02cd65663c6e9a40da6afbf` (candidate bytes untouched).
- **Gradle Execution:** `gradlew.bat` in `ARES-Analytics` with `--no-parallel --console=plain`.

---

## 3. Findings & Resolutions

| Issue Encountered | Diagnosis | Resolution |
| --- | --- | --- |
| Autonomous catalog reference validation | In `AutonomousCatalogProjectRepository`, catalog entries referencing unknown `routineId` throw an exception during `autonomous.load()`. | Created `.ares/routines/sample-routine.aresroutine` before adding routine reference to `autonomous-catalog.json`. |
| Exact string match in `.arestuning` | Pretty-printed JSON spacing varied across platforms/codecs. | Used regex-based replacement matching parameter UID and double value with validation check `check(modified != original)`. |
| Generation polling race condition | `awaitGenerationFinished` checked `while (phase == IDLE || buildRunning)` which instantly exited if earlier operation succeeded. | Two-stage await: first wait for `RUNNING || buildRunning`, then wait for completion `!RUNNING && !buildRunning`. |
| FTC Mecanum kinematics constraint | Drivetrain generator enforces `maxAngularSpeed == maxLinearSpeed / ((trackWidth + wheelBase) / 2)`. Arbitrary trackwidth mutation broke formula. | Mutated field dimensions in `.ares/project.json` for Category 1 and verified custom subsystem for Category 2. |
| Simulation OpMode lifecycle pacing | In `SimOpModeLifecycle`, `loop()` requires initial `tick()` during INIT before `start()` to prime gamepads and update subsystems. | Added `lifecycle.tick()` during INIT before `lifecycle.start()` in consumer simulation test fixtures. |

---

## 4. Verification Evidence

Executed in `c:\Users\david\dev\robotics\ARES-Robotics\ARES-Analytics`:
```powershell
.\gradlew.bat :app:test `
  --tests "*BiobuzzConsumerRoundtripIntegrationTest" `
  --tests "*GenericStarterConsumerRoundtripIntegrationTest" `
  --tests "*ProjectGenerationRecoveryIntegrationTest" `
  "-ParesVersion=19.1.3-rc.roundtrip.dbb5b9f.1" `
  "-ParesRepository=file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-project-roundtrip/ARESLib-Kotlin/build/release-repository" `
  --no-parallel --console=plain
```
**Result:** `BUILD SUCCESSFUL in 4m 54s`. 4 tests completed, 0 failures, 0 errors, 0 skipped:
1. `BiobuzzConsumerRoundtripIntegrationTest.biobuzz complete consumer roundtrip proves settings, user extensions, determinism, and simulated IO`: PASSED.
2. `GenericStarterConsumerRoundtripIntegrationTest.generic starter complete consumer roundtrip proves settings, user extensions, determinism, and simulated IO`: PASSED.
3. `ProjectGenerationRecoveryIntegrationTest.controlled intermediate-write failure leaves partial output, rejects stale build, preserves documents, and recovers cleanly`: PASSED.
4. `ProjectGenerationRecoveryIntegrationTest.real generation cancellation terminates process, preserves documents, and recovers cleanly on retry`: PASSED.

Monorepo policy check executed from workspace root:
```powershell
powershell.exe -ExecutionPolicy Bypass -File scripts/verify-monorepo-policy.ps1
```
**Result:**
- Shared agent guidance verified (tracked files, ignore rules, adapters, size, links).
- Verified local Markdown links in 191 current documents.
- Ledger verified: 1,111 files, 0 violations, ratchet PASS.
- Monorepo policy verified: ARES 19.1.3, Studio 7.0.63.

All modified test files conform to monorepo size policies ($\le 750$ lines):
- `BiobuzzConsumerRoundtripIntegrationTest.kt`: 381 lines
- `GenericStarterConsumerRoundtripIntegrationTest.kt`: 380 lines
- `ProjectGenerationRecoveryIntegrationTest.kt`: 270 lines

---

## 5. Limitations & Next Actions

- These validations are headless integration tests exercising real Gradle processes, Studio service orchestration, and desktop simulated IO. Physical robot deployment and native Compose desktop window rendering were not tested by these headless suites.
- Work remains strictly local on branch `codex/consumer-roundtrip-integration`. No push to remote, merge to main, or package publishing has occurred.
