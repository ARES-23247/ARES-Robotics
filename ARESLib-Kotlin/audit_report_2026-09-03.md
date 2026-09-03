# ARES Robotics High-Fidelity Monorepo Codebase Audit

**Date:** September 3, 2026  
**Auditor:** Antigravity Autonomous Code Reviewer (Deep Monorepo Protocol)  
**Scope:** Full ARES Robotics Monorepo — `ARESLib-Kotlin`, `ARES-FTC`, `ARES-FRC`, `ARES-Analytics`, `ARES-FTC-Starter`, `ARES-FRC-Starter`, Starter Mirrors & CI  
**Baseline:** `HEAD = 9306cfad` ("chore(release): prepare Studio 5.0.5 (#75)")  
**Supersedes:** `ARESLib-Kotlin/audit_report_2026-08-08.md` (August 8, 2026)

---

## ⚙️ Product Verification Matrix

All six isolated Gradle products and the starter export verification pipeline were verified programmatically:

| Product | Role | Primary Test / Verification Task | Result | Status |
| :--- | :--- | :--- | :---: | :---: |
| **`ARESLib-Kotlin`** | Foundation / Core / Compiler / Sim Foundation | `.\gradlew.bat test apiCheck` (385+ tests, API check) | 0 errors | ✅ **PASS** |
| **`ARES-FTC`** | FTC Season Product / Lightbot Robot | `.\gradlew.bat :simulator:test` | 0 errors | ✅ **PASS** |
| **`ARES-FRC`** | FRC Season Product (`Marvin XIX`) | `.\gradlew.bat test` (TimedRobot, Swerve, Shooter) | 0 errors | ✅ **PASS** |
| **`ARES-Analytics`** | ARES Robotics Studio Desktop & Gateway | `.\gradlew.bat studioReleaseVerification` (Coverage + Ratchet + Smoke) | 0 errors | ✅ **PASS** |
| **`ARES-FTC-Starter`** | Public FTC Starter Template | `.\gradlew.bat :simulator:test` | 0 errors | ✅ **PASS** |
| **`ARES-FRC-Starter`** | Public FRC Starter Template | `.\gradlew.bat test` | 0 errors | ✅ **PASS** |
| **Starter Mirrors** | Template Archive & Provenance Integrity | `pwsh -File .\scripts\export-starter-mirrors.ps1 -Check` | 0 diffs | ✅ **PASS** |

---

## 📊 Summary Scorecard

| # | Pillar / Capability | Grade | Summary & Verification |
| :--- | :--- | :---: | :--- |
| **1** | **State Immutability & Redux Purity (R1)** 🔒 | **A** | Read-only `val` data classes; `.copy()` transitions. Bounded Zero-GC diagnostic mutation in `DriveState.updateDiagnostics`. Clean Redux flow. |
| **2** | **Zero-GC Allocation in Hot-Paths (R2)** ⚡ | **A-** | Hot loops use `*Direct` primitives and thread-local scratchpads. `Pose2d.translation` getter still allocates `Translation2d(x, y)` on access (callers direct to `pose.x`/`pose.y`). |
| **3** | **Time-Determinism & Clock Purity (R3)** ⏰ | **A+** | 100% adherence to `RobotClock.currentTimeMillis()` and `RobotClock.nanoTime()` in `:core` and robot runtimes. Wall-clock usage isolated to desktop UI animations. |
| **4** | **Mathematical Stability & Boundary Guards (R4)** 🎛️ | **A** | Closed-form modulo wrapping; matrix singularity guards ($|\det| \le 10^{-12}$) in `Matrix3x3` and `Matrix.inverse()`; cached trigonometry in `HolonomicDriveController`; ADRC inverse plant precomputation. |
| **5** | **Hardware Timeout & Thread Purity (R5)** 🔌 | **A** | Dedicated daemon threads for slow I2C/Pinpoint/bus sensors; non-blocking 50Hz main loop; zero `GlobalScope.launch` in control paths. |
| **6** | **API Design & Physical Units (R6)** 📝 | **A** | Standard physical units ($m$, $rad$, $s$, $V$, $A$); CCW-positive standard; explicit Limelight target-space KDoc (yaw = `-rotation.y`). |
| **7** | **Style & Control Flow (Kotlin-First)** 🎨 | **A** | Flattened control flows; zero nested `if` statements in `ARES-FTC` and `ARES-FTC-Starter`; `6daa59a4` flattened FRC periodic and flywheel dispatch. |
| **8** | **Testing Coverage & Ratchets (R8)** 🧪 | **A** | 385+ passing tests; `studioReleaseVerification` enforces Kover coverage floors (App 38%, Shared 52%, Gateway 52%) and 500-line file size ratchets. |
| **9** | **Code Portability & Decoupling (R9)** 🚢 | **A** | `:core` is pure Kotlin; FTC SDK and WPILib isolated to hardware modules; FRC season namespace (`org.aresfirst.marvin`) cleanly separated from library (`com.areslib.frc`). |
| **10** | **Memory Management & Object Pooling** 🧹 | **A** | Object pools in EKF (`kalmanGainPool`), Limelight IO, and `ThetaStarPlanner` path pool; stale `VFHPlanner` stub removed from code. |
| **11** | **NT4 Protocol & Telemetry Rate Domains** 🌐 | **A** | Leading `/` stripped on topics; ground truth (`ARES/TruePose`) strictly segregated from EKF estimate (`ARES/EstimatedPose`); atomic `SimulatorPoseFrame` (10 doubles); 3 rate domains (50Hz control, 20Hz UI, 10Hz log). |
| **12** | **Watchdogs, Failsafes & Security Surface** 🛡️ | **A** | Control loops wrap in `try/catch` with `safeHardware()` fallback; `LogManagerServer:5002` hardened with token-bucket rate limiting (10 req/s), canonical path verification, and mandatory delete tokens ($\ge 16$ chars); gateway protected by OIDC. |

**Overall Score: A (Championship Production Ready)**

---

## 🔍 Deep-Dive Findings & Details

### 1. Zero-GC Hot Paths & Geometry Getters (Pillar 2)
- **Status:** Resolved in execution hot paths; minor API caveat remains.
- **Analysis:** Hot control loops (`HolonomicDriveController`, `PoseEstimator`, `LQRController`, `LinearADRC`) now use primitive `*Direct` entrypoints and member scratchpads. Callers correctly access `pose.x` and `pose.y` directly.
- **Residual Risk:** `Pose2d.translation` in `Geometry.kt:95` (`val translation: Translation2d get() = Translation2d(x, y)`) continues to allocate an instance per getter invocation. While hot paths bypass this property, student or third-party code calling `pose.translation` will incur GC churn.
- **Remediation:** Keep `@ZeroGcHint` documentation in KDoc, or store `translation` as an initialized `val` in `Pose2d`.

### 2. State Purity & Singleton Configurations (Pillars 1 & 4)
- **Status:** Compliant.
- **Analysis:** All Redux states are immutable. `PoseEstimator.activeTags` remains a `@JvmField var` on singleton `object PoseEstimator`. Tests override this in `@BeforeEach` and restore in `@AfterEach`. While acceptable as an ambient field configuration, migrating it into Redux state (`VisionState.activeTags`) would achieve 100% store-driven configuration.

### 3. Rate Domain Separation & Telemetry Flow (Pillar 11)
- **Status:** Verified and enforced.
- **Analysis:** Three independent rate domains are strictly maintained:
  1. **Control Rate (50 Hz):** Leased `ARES/Input/driveFrame` (`double[8]`) heartbeat. Free of DB writes, Compose state updates, or telemetry contention.
  2. **UI Rate (20 Hz):** `Nt4ClientService.uiTelemetryFlow` coalesces topic updates at 20 Hz, protecting Compose from recomposition storms.
  3. **Logging Rate (10 Hz):** `DriveFrameTelemetryRecorder` background stream records key motion metrics without stalling control.

### 4. Security & Network Surface (Pillar 12)
- **Status:** Hardened since prior audits (`ARES2-F03` resolved).
- **Analysis:**
  - `LogManagerServer:5002`: Mutating delete endpoint (`POST /api/delete`) is disabled by default and requires a token $\ge 16$ characters (`ares.log.deleteToken` or `ARES_LOG_DELETE_TOKEN`) via constant-time comparison (`MessageDigest.isEqual`). Read requests are rate-limited via client IP token buckets (10 tokens, 10 refill/s). Strict canonical path prefix verification prevents path traversal attacks.
  - Gateway (Port 8080): Ktor Netty on Cloud Run enforces Google OIDC authentication, per-subject rate limiting, and 1 MiB body limits.

### 5. Stale References & Architecture Documentation Drift
- **Status:** Remediated.
- **Analysis:**
  - `VFHPlanner` was previously removed from `ARESLib-Kotlin` in favor of `ThetaStarPlanner` and `Costmap`, but residual references persisted in `AGENTS.md:172` and `ARESLib-Kotlin/docs/architecture.md:40`.
  - **Action Taken:** Both files were updated to remove the obsolete `VFHPlanner` mentions.

### 6. Starter Export Mirror Script Compatibility
- **Status:** Remediated.
- **Analysis:** `scripts/export-starter-mirrors.ps1` previously invoked `[System.Security.Cryptography.SHA256]::HashData()`, which is unavailable in Windows PowerShell 5.1 (.NET Framework 4.8), causing script failure on standard Windows terminals unless running in PowerShell Core 7 (`pwsh`).
- **Action Taken:** Updated to `[System.Security.Cryptography.SHA256]::Create().ComputeHash()` with proper stream disposal, ensuring cross-version compatibility across Windows PowerShell 5.1 and PowerShell 7+.

---

## 📋 Comprehensive Audit Findings Table

| ID | Severity | Component | Description | Resolution / Status |
| :--- | :---: | :--- | :--- | :--- |
| **AUD-2026-01** | [LOW] | `ARESLib-Kotlin` (`Geometry.kt`) | `Pose2d.translation` getter allocates `Translation2d(x, y)` per call. | Core hot paths bypass getter and use `pose.x`/`pose.y`. Documented in KDoc. |
| **AUD-2026-02** | [RESOLVED] | `ARESLib-Kotlin` (`PoseEstimator.kt`) | `activeTags` singleton `@JvmField var` was non-volatile across vision/control threads. | **Fixed:** Added `@Volatile` for thread-safe memory visibility across threads. |
| **AUD-2026-03** | [RESOLVED] | `Workspace` (`AGENTS.md`, `docs/architecture.md`) | Stale documentation references to removed `VFHPlanner`. | **Fixed:** Cleaned references in `AGENTS.md` and `architecture.md`. |
| **AUD-2026-04** | [RESOLVED] | `Workspace` (`scripts/export-starter-mirrors.ps1`) | `SHA256.HashData` was incompatible with Windows PowerShell 5.1. | **Fixed:** Replaced with cross-compatible `SHA256.Create().ComputeHash()`. |
| **AUD-2026-05** | [RESOLVED] | `AI Guidance` (Skills) | Skills (`ares-code-auditor`, `ares-robotics-assistant`, `ares-subsystem-generator`) were brief and out of date with monorepo contracts. | **Fixed:** Fully rewritten and expanded to championship protocol standards. |
| **AUD-2026-06** | [RESOLVED] | `ARESLib-Kotlin` (`FrcVisionTracker.kt`) | Vision pose extraction manually unpacked 3D translation instead of utilizing `toPose2d()`. | **Fixed:** Replaced with idiomatic `measurement.targetPose.toPose2d()`. |

---

## 🤖 AI Guidance Synchronization & Analysis

### Skills Evaluated & Updated:

1. **`ares-code-auditor` (`SKILL.md`)**:
   - **Previous:** 24 lines, shallow 12-bullet list. Missing NT4 contracts, rate domains, cached reads, watchdog rules, and security surfaces. Misstated alliance inversion.
   - **Updated:** 140+ lines, comprehensive 12 Championship Code Pillars + 5 Deep System Invariants (NT4 topics, rate domain isolation, security surfaces, desktop lifecycle, release candidate workflows). Corrected alliance inversion (FTC blue vs FRC red).

2. **`ares-robotics-assistant` (`SKILL.md`)**:
   - **Previous:** 35 lines, generic rules.
   - **Updated:** Complete monorepo 6-product architecture, season layer pattern, Kotlin 2.4 context parameter rules (internal season only), Limelight target space axes, hardware read caching, and candidate release testing workflow.

3. **`ares-subsystem-generator` (`SKILL.md`)**:
   - **Previous:** Prescribed creating 5 manual Kotlin files in `TeamCode/src` with an Intake template.
   - **Updated:** Aligned with declarative `.ares` document workflow (`project-schema` $\to$ `project-compiler` $\to$ `TeamCode/build/generated/ares/`). Explains Lightbot reference robot (mecanum, 2 indicators, 1 Prism light) and provides clear separation between generated mechanical plumbing and custom season extensions.

4. **Workspace AI Guidance (`AGENTS.md` & `GEMINI.md`)**:
   - Validated against current codebase contracts. Obsolete planner references removed. All product and dependency rules remain authoritative.
