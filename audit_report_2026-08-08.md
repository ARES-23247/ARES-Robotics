# ARESLib-Kotlin High-Fidelity Codebase Audit

**Date:** August 8, 2026
**Auditor:** opencode Code Reviewer (12-Pillar Championship Protocol)
**Scope:** Full repository — `:core`, `:ftc-hardware`, `:frc-hardware`, `:simulator`, `:ftc-mocks`
**Baseline:** `HEAD = 68dccfe` ("fix: update ftc-hardware test assertions…")
**Supersedes:** `audit_report_areslib_kotlin.md` (June 16 — now stale)

---

## ⚙️ Build & Test Verification (Pre-Audit)

| Module | Tests | Failures | Errors | Status |
| :--- | :---: | :---: | :---: | :--- |
| `:core` | 352 | 0 | 0 | ✅ PASS |
| `:ftc-hardware` | 26 | 0 | 0 | ✅ PASS |
| `:frc-hardware` | 5 | 0 | 0 | ✅ PASS |
| `:simulator` | 2 | 0 | 0 | ✅ PASS |
| `:ftc-mocks` | — | — | — | ⬜ no tests |
| **Total** | **385** | **0** | **0** | ✅ **GREEN** |

Verified via `.\gradlew.bat test --rerun-tasks` (BUILD SUCCESSFUL). Full suite is green at audit baseline. Working tree clean except generated artifacts (`simulator/ares_run_summary.json`, `frc-hardware/backups/`).

> **Note on test discipline:** Three commits immediately preceding this audit (`6f6aac2`, `1d7f474`, `68dccfe`) were required to bring the suite green after the R4/R5 implementation changes. The earlier R4/R5 fix commits had landed without running the suite, leaving 33 core + 2 ftc-hardware failures (a duplicated condition-number guard in `LQRController` matrix ops broke every non-square multiply, and several EKF/PID/vision/hardware tests held stale assertions). A pre-commit or CI test gate is strongly recommended (see ARES2-F09).

---

## 📊 Summary Scorecard

| # | Pillar | Grade | Critical Item Summary |
| :--- | :--- | :---: | :--- |
| 1 | State Immutability & Redux Purity (R1) 🔒 | **A** | Immutable `val` state; `.copy()` transitions; documented Zero-GC in-place exception. |
| 2 | Zero-GC Allocation in Hot-Paths (R2) ⚡ | **B+** | Primitive `*Direct` paths + scratchpads; `Pose2d.translation` getter still allocates. |
| 3 | Time-Determinism & Clock Purity (R3) ⏰ | **A** | `RobotClock` injected throughout; mock-time support. |
| 4 | Math Stability & Boundary Guards (R4) 🎛️ | **A** | Closed-form wrap; singularity guards in `inverse()`; buggy norm-checks removed. |
| 5 | Hardware Timeout & Thread Purity (R5) 🔌 | **A-** | Daemon threads + `synchronized` caches for all I2C/sensors; leans on `synchronized` over atomics. |
| 6 | API Design & KDoc Documentation 📝 | **A** | LaTeX equations, physical units, coordinate conventions; bespoke KDoc pass. |
| 7 | Style & Conventions (Kotlin-First) 🎨 | **A** | Idiomatic properties, DSLs, `when` expressions; flat control flow. |
| 8 | Testing Coverage & Verification 🧪 | **B+** | 385 passing tests, well-mocked; stub planners untested; discipline gap (F09). |
| 9 | Code Portability & Decoupling 🚢 | **A** | `:core` is pure Kotlin; FTC/FRC SDKs isolated to hardware modules. |
| 10 | Memory Management & Object Pooling 🧹 | **A-** | Rich pools in EKF/Limelight; `ThetaStar` path pool fixed; VFH+ pools absent (stub). |
| 11 | Logging Efficiency 📊 | **A** | Zero-alloc `DiagnosticRingBuffer`; offline-first `.jsonl`. |
| 12 | System Robustness, Security & Failsafes 🛡️ | **B+** | Loop watchdogs + `safeHardware()`; web server endpoints are unauthenticated. |

**Overall: A- (strong, championship-grade, with a focused set of polish items).**

---

## 🔍 Sectioned Detail

### 1. State Immutability & Redux Purity (R1) 🔒 — A
- **✅ Strengths:** All `RobotState` sub-states (`DriveState`, `VisionState`, `PathState`, `TuningState`, `CostmapState`) use read-only `val` fields. Reducers (`rootReducer`, `DriveReducer`, etc.) are pure, return state via `.copy()`, and never throw on invalid actions. `Store.dispatch` is synchronous and documented as main-thread-only.
- **✅ Documented exception:** `DriveState.updateDiagnostics` mutates the `covarianceMatrix`/`lastKalmanGain` arrays in place to avoid a 9-element `DoubleArray` allocation at 50 Hz, with a clear inline justification (`RobotState.kt:84`). Compliant with the Zero-GC mutability carve-out.
- **⚠️ Findings:** `PoseEstimator.activeTags` is a `@JvmField var` on a singleton object (`PoseEstimator.kt:315`) — global mutable state outside the Redux tree. Acceptable as a config hold, but it is mutated by tests and could leak across tests/subsystems. See ARES2-F02.

### 2. Zero-GC Allocation in Hot-Paths (R2) ⚡ — B+
- **✅ Strengths:** `PoseEstimator.addOdometryObservationDirect` takes primitive `Double` deltas and uses `ThreadLocal` scratchpad matrices (`scratchQ`, `scratchCov`); `DriveReducer` calls this Direct variant, eliminating the old per-tick geometry allocations. `LQRController.calculate` operates entirely on pre-allocated matrix buffers. `HistoryBuffer` is a pre-allocated circular ring with `O(1)` insert.
- **✅ Fixed since prior audit:** ARES-F01 (DriveReducer 50 Hz allocations) — resolved via the Direct path. ARES-F02 (PoseEstimator allocations) — internalized into scratchpad-based `processOdometryDirect`.
- **⚠️ Findings:**
  - `Pose2d.translation` getter allocates a new `Translation2d(x, y)` on **every** access (`Geometry.kt:95`). Any hot-path caller of `pose.translation` heap-allocates. See ARES2-F01.
  - The non-Direct `addOdometryObservation(state, ts, Translation2d, Rotation2d, …)` wrapper still accepts (and thus callers allocate) geometry objects; it is a thin delegate to the Direct path. Internal cost is gone, but the public API invites allocation.

### 3. Time-Determinism & Clock Purity (R3) ⏰ — A
- **✅ Strengths:** `com.areslib.util.RobotClock.currentTimeMillis()` is the single time source across core math, estimators, and control loops; `useMockTime` enables deterministic replay. No raw `System.currentTimeMillis()`/`nanoTime()` in library hot paths. LQR/PID tests explicitly drive mock time.
- **⚠️ Findings:** None.

### 4. Math Stability & Boundary Guards (R4) 🎛️ — A
- **✅ Strengths:** Angular wrapping uses closed-form modulo (`MathUtils.wrapAngle`). `Matrix.inverse()` guards 1×1/2×2/3×3 against `|det| ≤ 1e-12` and NaN/Inf, falling back to identity with a logged warning; higher dims use partial-pivoted Gauss-Jordan with the same guard. `SlewRateLimiter` uses absolute `posLimit`/`-negLimit` bounds. EKF and PID inputs are checked for NaN/Inf before computation.
- **✅ Resolved regression:** Commit `6f6aac2` removed the erroneously duplicated condition-number (`normA * normInv > 1e6`) blocks from `add`, `subtract`, `multiplyScalar`, `multiply`, `transpose`, and `inverse`. Those blocks read `result.get(r, c)` with `c` up to the *left* operand's column count while `result` had the *right* operand's column count — an `ArrayIndexOutOfBoundsException` on every non-square product where the inner dim exceeded the result width (it had broken all 26 LQR tests). Correct removal; `inverse()` retains its proper singularity guard.
- **⚠️ Findings:** With the spurious guards gone, `add/subtract/multiply` no longer carry any conditioning check. That is the correct state (condition-number curation belongs at the solver level, not on every elementary op), but it means callers rely entirely on `inverse()`'s det guard for singularity safety. No action required.

### 5. Hardware Timeout & Thread Purity (R5) 🔌 — A-
- **✅ Strengths:** Every slow synchronous bus read runs on a background daemon thread with a `synchronized(lock)` cache and an interruptible `Thread.sleep` poll loop: `FtcColorSensor`, `FtcDistanceSensor`, `FtcRevColorSensorV3`, `FtcVL53L5CX`, `OctoquadIO`, `SrsHubIO`, `RevI2CSensorManager`, `RevBulkDataReader`, `RevMotorController`, `SwerveModuleIOFtc`, `LimelightProxy`. Property accessors return cached primitives under the lock, keeping the main loop non-blocking. `AresPhotonCore` uses `AtomicBoolean` for lifecycle flags.
- **⚠️ Findings:** Primitive sensor caches (e.g., `cachedRed`, `cachedDistance`, `latestVoltage`) are guarded with `synchronized` blocks rather than `@Volatile`/`AtomicLong`. For single-writer/reader primitive fields this adds minor monitor contention the protocol suggests avoiding via atomics. Low impact at FTC bus rates. See ARES2-F04.

### 6. API Design & KDoc Documentation 📝 — A
- **✅ Strengths:** High-fidelity KDoc with LaTeX math (DARE/Riccati in `LQRController`, EKF update in `VisionMahalanobisFilter`), explicit physical units (`m`, `rad`, `s`, `V`), and coordinate-sign conventions (CCW-positive heading, Limelight target-space axes documented in `RobotState.kt`). Student-facing DSLs (`aresRobot { }`, `ARESMecanumAuto`) are clean.
- **⚠️ Findings:** Residual auto-generated KDoc boilerplate survives in places (e.g., `Store.dispatchAll`/`subscribe` "Standard arguments (if applicable)"). Cosmetic.

### 7. Style & Conventions (Kotlin-First) 🎨 — A
- **✅ Strengths:** Kotlin properties, trailing lambdas, DSL builders, `when` expressions, and flat (non-nested-`if`) control flow throughout. The recent `refactor(control)` commit deliberately flattened nested conditionals.
- **⚠️ Findings:** A handful of compiler warnings for unused parameters (`pubUID` in `NT4Server.kt:284`, `alliance` in `PathPlannerAutoParser.kt:78`, `pose`/`dynamicObstacles` in `VFHPlanner.kt:33-34`) and a renamed-override mismatch in `DslBuilderTest.kt:67`. Cosmetic. See ARES2-F07.

### 8. Testing Coverage & Verification 🧪 — B+
- **✅ Strengths:** 88 test files / 385 tests across math, EKF, LQR, PID, pathing, safety, kinematics, reducers, sequencer, telemetry, and student onboarding. Tests are decoupled from Android/FTC SDKs via mocks (`ftc-mocks`, simulator physics), enabling full desktop execution. E2E tiers (`e2e/tier1`, `tier2`) exist.
- **✅ Resolved since prior audit:** ARES-F04 (disabled `PoseEstimatorVisionHardeningTest`) — re-enabled with an `activeTags` override in `setUp` and updated assertions; all three incidence/ambiguity cases now pass.
- **⚠️ Findings:**
  - `VFHPlanner` is an unimplemented stub (`VFHPlanner.kt:37-43`, returns `goalHeading` unchanged) and has no tests. Its KDoc claims Zero-GC histogram generation that does not yet exist. See ARES2-F06.
  - Test discipline gap: R4/R5 changes initially shipped with 35 failing tests; three follow-up commits repaired them. No automated gate prevented that. See ARES2-F09.

### 9. Code Portability & Decoupling 🚢 — A
- **✅ Strengths:** `:core` is a pure JVM Kotlin subproject with zero FTC/FRC SDK dependencies; all platform code lives behind IO interfaces (`MecanumHardwareIO`, `PinpointIO`, `FtcLimelightIO`, swerve module IO). The same core drives FTC, FRC, and the desktop simulator.
- **⚠️ Findings:** `PROJECT.md`'s "Code Layout" references `frc-app/src/main/kotlin/...` which does not exist (the module is `frc-hardware`). Doc-only. See ARES2-F08.

### 10. Memory Management & Object Pooling 🧹 — A-
- **✅ Strengths:** `PoseEstimator` uses a 16-slot `kalmanGainPool`, plus `translationPool`/`posePool`/`visionMeasurementPool`/`measurementListPool` (10-slot) in `FtcLimelightIO`. `ThetaStarPlanner.reconstructPath` now writes into a pre-allocated `state.pathPool` `DoubleArray` (ARES-F05 resolved) instead of allocating a `List`. EKF uses `ThreadLocal` scratchpads.
- **⚠️ Findings:** `VFHPlanner` pre-allocates `polarHistogram`/`binaryHistogram` arrays but the planner is a stub, so the README's "Valley pools in `VFHPlanner`" claim is currently inaccurate. No leak; documentation drift. See ARES2-F06.

### 11. Logging Efficiency 📊 — A
- **✅ Strengths:** `DiagnosticRingBuffer` writes into flat primitive arrays with zero `String` allocation. Telemetry is offline-first `.jsonl`; an embedded `RobotWebServer` (NanoHTTPD-style) exposes local pull endpoints for the ARES-Analytics driver station.
- **⚠️ Findings:** None.

### 12. System Robustness, Security & Failsafes 🛡️ — B+
- **✅ Strengths:** `FtcBaseRobot.update()` wraps the control cycle in `try/catch`; on any failure it commands `safeHardware()` (zero power to all drivetrain motors) and logs telemetry before re-throwing/propagating — preventing runaway. Motor current budgets, brownout guard, and beaching/odometry-freeze failsafes are present.
- **⚠️ Findings:** The embedded web server (`LogEndpointHandler`) exposes `/api/status`, `/api/logs`, `/api/logs/download`, `/api/logs/markSynced`, and `/api/limelight/stream` with **no authentication or authorization** and a wildcard CORS policy (`Access-Control-Allow-Origin: *` plus `Access-Control-Allow-Private-Network: true`, `LogEndpointHandler.kt:71-74`). Mutating routes (`markSynced`) and log downloads are unauthenticated. On a shared FTC field network this is low-to-moderate risk (another device could read/alter sync state). The prior audit's R12 "A" considered only the FTC-local watchdog surface, not this network attack surface. See ARES2-F03.

---

## 📋 Findings Table

| ID | Severity | Finding | Location |
| :--- | :--- | :--- | :--- |
| **ARES2-F01** | [LOW] | `Pose2d.translation` getter allocates a new `Translation2d` on every access; hot-path callers silently heap-allocate. | `core/.../math/geometry/Geometry.kt:95` |
| **ARES2-F02** | [LOW] | `PoseEstimator.activeTags` is global mutable singleton state (`@JvmField var`) outside the Redux tree; mutated by tests. | `core/.../math/estimation/PoseEstimator.kt:315` |
| **ARES2-F03** | [MEDIUM] | Web server `/api/*` endpoints are unauthenticated with wildcard CORS + Private-Network access; `markSynced`/`download` lack authorization. | `core/.../telemetry/web/LogEndpointHandler.kt:55-74` |
| **ARES2-F04** | [LOW] | Primitive sensor caches use `synchronized` monitors instead of `@Volatile`/`AtomicLong`; minor contention vs. protocol preference. | `ftc-hardware/.../hardware/FtcColorSensor.kt:114`, `FtcDistanceSensor.kt:55`, `RevI2CSensorManager.kt:168` |
| **ARES2-F05** | [LOW] | Public `addOdometryObservation(...)` wrapper still accepts geometry objects, inviting caller allocation despite the zero-GC Direct path existing. | `core/.../math/estimation/PoseEstimator.kt:332` |
| **ARES2-F06** | [LOW] | `VFHPlanner` is an unimplemented stub (returns `goalHeading`); KDoc/README describe histogram + Valley pools that do not exist. | `core/.../pathing/planner/VFHPlanner.kt:37-43` |
| **ARES2-F07** | [LOW] | Compiler warnings: unused params (`pubUID`, `alliance`, VFH `pose`/`dynamicObstacles`) and a renamed-override mismatch. | `NT4Server.kt:284`, `PathPlannerAutoParser.kt:78`, `VFHPlanner.kt:33-34`, `DslBuilderTest.kt:67` |
| **ARES2-F08** | [LOW] | `PROJECT.md` "Code Layout" references non-existent `frc-app/` module (actual: `frc-hardware`). | `PROJECT.md:29-30` |
| **ARES2-F09** | [MEDIUM] | Test discipline: R4/R5 impl commits shipped with 35 failing tests; required 3 follow-up commits to repair. No pre-commit/CI gate enforces a green suite. | (process) |

---

## 🗺️ Roadmap to Compliance

### 🔴 Must Fix (High Priority)
*None.* The library builds, the full 385-test suite is green at baseline, and all prior-audit findings (ARES-F01…F05) plus the LQR regression are resolved. No correctness, safety, or hard-blocker issues remain.

### 🟡 Should Fix (Medium Priority)
- **ARES2-F03 — Add minimal auth to the web server.** Bind to the DS link-local address or require a shared team token header on `/api/logs/markSynced` and `/api/logs/download`; drop the wildcard CORS to a constrained origin (or the DS host). Keeps the offline-first model while removing the shared-field-network exposure.
- **ARES2-F09 — Enforce a green-suite gate.** Add a pre-push hook or CI step running `.\gradlew.bat test` so implementation changes cannot land against a red suite again. This single change would have prevented the LQR regression and the 35 stale-test failures.

### 🟢 Backlog (Low Priority / Optimizations)
- **ARES2-F01 — Remove the hidden `Pose2d.translation` allocation.** Either store `translation` as a re-used field, or audit hot-path callers (estimators, drive controllers) to read `pose.x`/`pose.y` directly instead of `pose.translation`.
- **ARES2-F06 — Implement or delimit `VFHPlanner`.** Either land the VFH+ histogram + Valley pool (aligning code with KDoc/README) or mark the class `@Suppress("unused")`/experimental and trim the doc to "stub" so it is not mistaken for production avoidance.
- **ARES2-F02 — Move `activeTags` into Redux state** (e.g., a `VisionState.fieldLayout`) so field configuration flows through the store instead of a singleton.
- **ARES2-F05 — Deprecate the allocating `addOdometryObservation` wrapper** (or annotate `@ZeroGcHint` pointing to the Direct variant) to steer callers off the allocating path.
- **ARES2-F04 — Convert primitive sensor caches to `@Volatile`** (single long/double) or `AtomicLong` to drop monitor overhead on the read side.
- **ARES2-F07 / ARES2-F08 — Cleanups:** resolve unused-parameter warnings (prefixed `_` or removed) and correct the `frc-app` → `frc-hardware` path in `PROJECT.md`.

---

*Gracious Professionalism applied: this is a genuinely well-engineered, championship-grade library. The architecture (immutable Redux + decoupled IO + Zero-GC hot paths + deterministic clock) is consistently realized across ~47k lines and 385 tests. The items above are polish — none block competition.*
