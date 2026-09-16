# Deep, Evidence-Backed Audit of the ARES Monorepo

## Status and Authority
- **Status:** **Active Campaign** (Initiated 2026-09-16)
- **Goal Type:** Tracked Repository Goal
- **Baseline Commit:** `9487e288b0029833dac0a75dc63d95d8247811cb` (Protected `origin/main` Release: Studio 7.0.60, ARESLib 19.1.1)
- **Isolated Branch:** `antigravity/deep-audit`
- **Fixed Repository Scope:** Exactly **2,964 tracked files** (as resolved by `git ls-files -z` at baseline)
- **Authority:** Explicit authorization for internal subagents, isolated feature worktrees/branches, and local repository modifications without external push/publish.

---

## 1. Objective and Core Invariants

### 1.1 Overarching Objective
Review every in-scope authored file in the fixed repository baseline (`9487e288b`), using coordinated internal subagents.
Identify, reproduce, and fix confirmed correctness, safety, lifecycle, concurrency, and measured performance problems.
Maintain an accurate file-level ledger in `docs/audits/file-reviews.json` using `scripts/audit_inventory.py` and validate
the combined changes through rigorous, meaningful integration checkpoints.

### 1.2 Non-Negotiable Invariants
1. **Zero Unsubstantiated Claims:** File counts, clean builds, and timestamps are accounting, not proof of code review. Every file review must document examined behavior, scope, and validation evidence.
2. **Strict Monorepo Isolation & Boundary Respect:**
   - Shared behaviors belong in `ARESLib-Kotlin`.
   - Season robot behaviors belong in `ARES-FTC` and `ARES-FRC`.
   - Desktop and cloud synchronization workflows belong in `ARES-Analytics`.
   - MicroPython controller logic belongs in `ARES-XRP-Starter`.
   - Never weaken leases, weaken security checks, or substitute simulation truth for Redux estimators.
3. **Write Conflict Prevention:**
   - The coordinator owns central files: `docs/audits/file-reviews.json`, `release/*`, `scripts/*`, shared CI, and git commits.
   - Worker subagents act as domain-partitioned reviewers and researchers writing isolated report fragments.
   - Builds are serialized; simultaneous Gradle builds against the same target outputs are forbidden.
4. **Preservation of Previous Audit Work:**
   - Historical readiness reports (`ROBOT_READINESS_REPORT.md`, `DESKTOP_READINESS_REPORT.md`, `BIOBUZZ_READINESS_REPORT.md`) and archive indices (`HISTORICAL_AUDITS_INDEX.md`) remain authoritative records of past physical and scenario validations.
   - Proven fixes from prior passes must be retained.
5. **Physical Hardware Limitation:**
   - Physical robot hardware is currently offline/unavailable. Software simulation, mathematical derivations, analytical trajectory models, and unit/integration test evidence must be distinguished from live hardware tests.

---

## 2. Baseline Inventory and Categorization

As of baseline `9487e288b`, the monorepo contains **2,964 tracked files** classified into 6 functional categories:

| Category | File Count | Description | Primary Verification Method |
|---|---|---|---|
| **Source Code** | 1,276 | Kotlin, Java, MicroPython production logic | Line-by-line behavioral audit, caller/callee analysis, unit/integration testing |
| **Tests & Mocks** | 1,032 | Unit tests, mock frameworks, test fixtures | Test quality review, assertion strength audit, mutation/regression verification |
| **Configuration & Resources** | 271 | JSON/YAML schemas, `.ares` descriptors, templates | Schema validation, codec roundtrips, semantic consistency checks |
| **Documentation** | 245 | Markdown guides, specs, historical reports | Content review, link verification (`verify-doc-links.ps1`), policy alignment |
| **Build & Tooling** | 86 | Gradle scripts, Python tooling, CI workflows | Monorepo policy verification (`verify-monorepo-policy.ps1`), script execution |
| **Assets & Binaries** | 54 | Icons, field images, starter template zips | Integrity checks, SHA-256 verification, consumption verification |

### Initial Review Status at Baseline:
- **Reviewed & Fingerprint-Matched:** 2,703 files
- **Stale (Modified in PR #103 / Release 7.0.60):** 134 files
- **Pending (New files unrecorded in ledger):** 127 files
- **Orphaned Ledger Entries:** 63 records (deleted or consolidated files to be pruned)

---

## 3. Subagent Coordination and Ownership Map

```
                    +--------------------------------+
                    |    Lead Coordinator (Agent)    |
                    |  - Ownership & Subagent Dispatch |
                    |  - Defect Fixes & Integration  |
                    |  - Central Ledger & Manifests  |
                    |  - Serialized Build Validation  |
                    +---------------+----------------+
                                    |
            +-----------------------+-----------------------+
            |                       |                       |
+-----------v-----------+ +---------v-----------+ +---------v-----------+
| Worker 1: Core/Lib   | | Worker 2: Analytics  | | Worker 3: Starters  |
| - ARESLib-Kotlin      | | - Gateway & Services | | - ARES-FTC/FRC/XRP  |
| - Math, Geometry, EKF | | - ViewModels & UI    | | - Templates/Schemas |
| - Concurrency & Redux | | - Telemetry & NT4    | | - Test Suites       |
+-----------------------+ +---------------------+ +---------------------+
```

### Worker Operating Rules:
- **Concurrency Limit:** At most 3 concurrent worker subagents.
- **Access Mode:** Read-only inspection of source, callers, callees, and tests.
- **Deliverables:** Structured markdown findings report fragments with exact file paths, line numbers, defect classification, reproduction steps, and proposed fixes.
- **Prohibitions:** Workers do not write to shared files, do not edit files outside their assigned partition, and do not execute parallel Gradle builds.

---

## 4. Substantive Audit Pillars

Every authored file is inspected against the 12 Championship Code Pillars:
1. **State Immutability & Redux Purity:** Pure reducers, zero I/O in reducers, no global mutable singletons.
2. **Zero-GC Allocation in Hot Paths:** 50Hz-100Hz loops (update, calculate, odometry, steering) must not allocate heap memory. Watch for hidden getters constructing objects.
3. **Time-Determinism & Clock Purity:** Use RobotClock; System.currentTimeMillis is prohibited in runtime control logic.
4. **Mathematical Stability & Boundary Guards:** Closed-form angle wrapping [-pi, pi], matrix singularity guards (|det| <= 1e-12, NaN/Inf checks), division-by-zero guards.
5. **Concurrency & Thread Purity:** Dedicated daemon threads for bus reads (I2C/Pinpoint); 50Hz main loop never blocks on bus reads or sleeps; no unmanaged coroutines.
6. **Physical Units & Coordinate Conventions:** SI units (m, rad, m/s, rad/s), CCW-positive heading (0 = +X, +pi/2 = +Y).
7. **Vision Space Conventions:** Limelight target space vs Field space separation; planar robot yaw from target space is -rotation.y.
8. **Vision Fusion & Outlier Rejection:** Mahalanobis distance gating (chi^2 > 18.0), ambiguity thresholds, covariance sanitization.
9. **Alliance Inversion Purity:** Apply alliance mirroring strictly once at the season input boundary.
10. **Position Hold Feedforward:** Overcome static friction (kS) along error unit vector.
11. **Hardware Read Caching:** Sensors read once per control cycle in readSensors().
12. **Robustness & Fail-Safe Neutralization:** All control paths catch unexpected exceptions and command safeHardware() (neutral zero outputs).

---

## 5. Finding Classification & Action Thresholds

- **High Impact (Mandatory Immediate Fix):**
  - Realistic incorrect control or mathematical calculation.
  - Unsafe outputs, ignored freshness or enable conditions.
  - Lost or dropped commands, failed cancellation, partial initialization leaks.
  - Unhandled NaN/Infinity propagation escaping to actuators or state estimators.
- **Performance (Mandatory Fix when Measured):**
  - Allocations in steady-state 50Hz+ control loops.
  - Blocking calls or lock contention on the main loop thread.
  - Repeated expensive calculations in loops.
- **Lower Priority (Document & Defer):**
  - Stylistic, cosmetic, or documentation polish without functional risk.
  - Speculative edge cases outside realistic robot physical boundaries.

---

## 6. Execution Plan and Checkpoint Batches

- **Batch 1: Governance, CI, Build Tooling & Ledger Pruning**
  - Prune 63 orphaned records from `docs/audits/file-reviews.json`.
  - Reconcile 86 build scripts, CI workflows, and release tools.
  - Validate baseline with `python scripts/audit_inventory.py`.
- **Batch 2: ARESLib-Kotlin Core Math, Kinematics & Estimation**
  - Audit stale and pending files in `ARESLib-Kotlin`.
  - Verify matrix condition numbers, safe normalization, and EKF singularity fallbacks.
  - Run `:core:test`, `:core:apiCheck`, and verify source-tree hash.
- **Batch 3: ARES-Analytics Gateway, Services & Telemetry**
  - Audit stale and pending files in `ARES-Analytics/gateway` and `ARES-Analytics/app/service`.
  - Verify NT4 client thread safety, OAuth loopback server, Google Drive picker, run comparison analyzer.
  - Run `:gateway:test`, `:shared:test`, `:app:test`.
- **Batch 4: ARES-Analytics UI, ViewModels & Desktop**
  - Audit stale and pending files in `ARES-Analytics/app/viewmodel` and `ui`.
  - Verify Compose recomposition stability, state decoupling, and instance lock mechanisms.
  - Verify production file size limits (<= 750 lines, 0 grandfathered files).
- **Batch 5: Robot Starters, Templates & XRP Platform**
  - Audit starters (`ARES-FTC-Starter`, `ARES-FRC-Starter`, `ARES-XRP-Starter`, BioBuzz, Lightbot).
  - Verify template integrity, zip archive hashes, and starter export tooling.
- **Batch 6: Integration, Ledger Sealing & Final Campaign Verification**
  - Run full repository test suites across all Gradle products.
  - Run `pwsh scripts/verify-monorepo-policy.ps1`.
  - Refresh ledger fingerprint and seal `docs/audits/file-reviews.json`.
  - Generate final audit evidence summary and close the campaign.

---

## 7. Exit Criteria

The goal is complete when and only when:
1. Every file in the 2,964 baseline has an explicit, valid disposition in `docs/audits/file-reviews.json` (`pending == 0`, `stale == 0`, `orphaned == 0`).
2. All confirmed high-impact and measured performance defects are fixed, regression-tested, and integrated.
3. Every subproject passes its test suite (`ARESLib-Kotlin`, `ARES-Analytics`, `ARES-FRC`, `ARES-FTC`).
4. Monorepo policy gate (`pwsh scripts/verify-monorepo-policy.ps1`) is 100% green.
5. All physical hardware limitations and deferred lower-priority observations are explicitly documented in `docs/audits/DEEP_AUDIT_REPORT.md`.
