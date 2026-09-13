# State snapshots and reducer efficiency audit

Pass 223 continues the non-overlapping monorepo audit in the isolated local audit worktree.
It closes the previously pending immutable vision snapshot, root/routine/path/superstructure
reducer and routine/path state cohort. Shared geometry is included only for extracting the
already reviewed Euler calculation. Store and controller behavior are integration context.

## Findings and fixes

1. **Retained poses had divergent Euler math.** `Pose3dSnapshot` still used the older
   direct atan2/asin formulas after mutable `Rotation3d` gained a coherent singularity
   representation. At roll 0.3, pitch -pi/2 and yaw 0.8, planar heading changed from about
   1.1 radians to 0.463647609 radians merely by taking a snapshot. Reconstructing the
   orientation from the reported Euler values could produce a different quaternion.
   Near singularity, asin rounded pitch to pi/2 instead of preserving a 1e-9 offset.
   Both types now call the same internal primitive helper: stable atan2/hypot pitch and
   a consistent roll-zero/yaw choice at the roundoff-sized singular boundary.
   Public API and exact quaternion retention are unchanged. Unit quaternion inputs remain
   a precondition; capture does not repair invalid sensor orientation.

2. **Repeated unchanged actions rebuilt slices, maps and the root.** Indicator, Prism,
   identical subsystem instances and unchanged path-progress updates now reuse their
   slice. The root reuses its snapshot only when every reduced slice has the same identity
   and the timestamp is unchanged. All actions still reach the reducers and Store observers.
   Distinct subsystem instances are published even if equal, without calling user equality.
   Numeric checks preserve data-class signed-zero/NaN equality semantics. Genuine changes
   and new timestamps still publish new snapshots.

3. **Six purported reducer tests were placeholders.** The path and superstructure test
   files contained only unconditional assertions, including a purported safety-interlock
   check that exercised no safety code. They now test real detour/progress transitions,
   independent mechanism/light updates, previous-state preservation, repeated values,
   missing names and floating-point behavior. Safety interlocks remain controller/IO
   responsibilities; these tests do not claim physical safety validation.

Routine lifecycle reduction needed no source fix under its ordered, unique-execution-ID
producer contract. New checks cover request/start/step timestamps, all three terminal kinds,
unknown-step pass-through, compilation-failure fallback, independent concurrent invocation
entries and bounded terminal history over 100 invocations. This does not close the separate
RoutineManager concurrency, document-ownership or task-callback reentry scope.

## Focused evidence

The initial 11-test baseline produced six failing scenarios: three snapshot math failures
and three redundant-allocation/identity failures. These overlap the two production issues;
they are not six distinct bugs. Baseline XML and the successful final 98-test focus are
preserved in `ARESLib-Kotlin/build/audit-pass223-verified-evidence/`.

The focus includes 16 added test methods and six replaced placeholder methods, plus existing
geometry, drive/vision/root, routine-manager and allocation regressions. All public API checks
passed without an API dump change. Snapshot tests compare reconstructed quaternions, both
pitch singularities, near-singular pitch, ordinary and negated unit quaternions, before/after
planar projection, every vision-measurement field, nested-pose mutation and matrix row/storage
ownership. Reducer tests preserve observer/action-listener delivery and avoid user equality.

The warmed desktop JVM mixed repeated-intent workload fell from **2,080,264 bytes to 0 bytes
over 10,000 dispatches**. Its actions and timestamp are reused; it is not a moving-robot or
whole-loop allocation claim. The same workload in the full library run measured 560 bytes,
also below the 4,096-byte regression bound. The existing Store/EKF focus measured 904,880 bytes per 1,000
moving reductions. Geometry scalar, holding-routine and other existing allocation regressions
also passed. No physical loop latency, native Studio window or HIL result was measured.

## Candidate and downstream validation

Source commit: `9bff66fa5eb5857b8e5ddec9c9f0f349dffc994f`.
Library tree: `2cb4931d225ead9c69dd676c1062b98d27ad31d4`.
Local candidate: `17.0.30-rc.2cb4931d225e`.

Canonical versions are ARES/FTC/FRC starters 17.0.30, Studio 7.0.30 and XRP/Lightbot 3.0.29.
All work remains local; the candidate repository is isolated under the worktree's build output.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,640 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,043 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,472 passing results, zero failures/errors and six existing Studio
skips. The skips cover three opt-in starter integration scenarios, native file chooser,
dashboard performance baseline and physical dashboard validation. Gradle results may be
executed, up-to-date or restored from cache; focused results are not counted twice.
FTC/starter generated-project checks and APK assembly passed; FRC/starter generated-project
checks passed. All 410 candidate file hashes were reverified after consumers finished.
Monorepo policy passed, including source/version/archive identity, shared guidance and links
in 385 current documents, with 38 explicitly historical records skipped. Four normalized
starter archive comparisons differ only in release version properties.

## Coverage and boundaries

The ledger accounts for 2,980 tracked files: 1,244 fully reviewed, 171 partially reviewed
and 1,565 pending, with zero stale or orphaned records. This is file review and appropriate
validation accounting, not universal executable coverage.

Full review credit applies to the selected small source files and six selected test files.
The existing complete geometry and architecture documentation records retain their previous
scope, supplemented by the shared helper extraction and the new retention/reuse contract.
RobotState remains partial: this pass traced its relevant scalar/measurement/custom-state
definitions, but did not close all collection ownership and coordinate documentation claims.
Path lists and mutable point payloads retain their explicit shared ownership; callers must keep
them stable while states/followers retain them. Custom subsystem values must remain immutable.
Reducers still store supplied intents/progress; validity, freshness and output enable/neutral
policy belong to the existing downstream control boundary.

This pass is progress toward the active goal. Unrelated pending files and partial scopes remain.
