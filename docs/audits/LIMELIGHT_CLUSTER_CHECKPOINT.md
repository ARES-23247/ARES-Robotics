# Limelight cluster checkpoint — 2026-09-15

Scope: add camera-relative cluster aiming points to FTC Limelight 3A input, Redux/replay, and the
exported BioBuzz example. The broad mathematical audit remains paused. No new task, sub-agent,
hardware process, push, merge, or public release was started.

Implementation checkpoint: `31f026154`, isolated branch `codex/limelight-clusters`, based on
`1ae94a9ed`. Other work in the original checkout was preserved. Local candidate
`19.1.0-rc.eb4645845af3` binds library tree `eb4645845af38f2b590d755b486b9ec836b872db`.

## Verified behavior

- Each tag reconstructs the same configured aiming point; all 15 nonempty visibility subsets
  pass across 24 independently generated SciPy rotation-vector trajectories (0.8–5.4 m).
- Conflicting detections, duplicate tag IDs, invalid/missing optical poses, unknown tags, bad
  latency, future/expired captures, disconnection, and close cannot retain valid targeting output.
- Cluster cameras suppress all combined MegaTag field observations, including mixed moving/static
  frames. Moving targets cannot update or reseed the EKF. Camera identity remains separate when
  combining sources; immutable Redux snapshots survive adapter buffer reuse.
- Cluster observations round-trip through the existing action logger/replay path. Re-polling a
  cached frame cannot renew its capture time.
- FIRST SDK 12.0 BioBuzz geometry is available in the season-owned preset. The canonical example
  descriptor generates its optional camera; actual generated code accepts injected optical poses
  through Limelight IO while intake and flywheel run together. Missing tags clear the target, and
  Stop neutralizes mechanism and drive outputs. The Android APK builds against SDK 11.1.0.

This is agreement-gated point reconstruction from individual tag poses, not the FTC SDK's joint
corner/PnP cluster pose solver. Existing mechanism bindings remain explicit; no uncalibrated
automatic aim or fire command was added. See [setup and API usage](../../ARESLib-Kotlin/docs/limelight-clusters.md).

## Validation evidence

Evidence directory: `build/cluster-evidence/` in this worktree. Full logs and machine-readable
summaries are retained locally; public source contains the regression fixtures and tests.

| Check | Result |
| --- | --- |
| Full library tests | 3,056 passed; no failures/errors/skips |
| Library API checks and isolated publication | Passed |
| Exported BioBuzz generated project, robot/simulator tests, APK | 45 passed |
| FTC generated project, robot/simulator tests, APK | 190 passed |
| FRC consumer | 306 passed |
| FTC starter generated project, tests, APK | 17 passed |
| FRC starter | 206 passed |
| CI changed-path scopes | 22 passed; existing library/BioBuzz scopes include the new checks |
| Source/release policy | Passed |
| Studio/shared/gateway compatibility | 2,684 passed; 6 opt-in checks skipped; no failures/errors |

The skipped Studio checks cover three opt-in template integration suites, native file-chooser UI,
physical hardware, and the dashboard performance baseline. No rendered Studio UI or hardware
validation is claimed. The exported BioBuzz build and robot scenario above ran directly.

The final BioBuzz archive was compared against all 150 archive entries in the tested project;
there were no source differences after normalizing line endings. This includes the corrected
test teardown call. All template versions/hashes are updated locally; these versions are not published.

The first full library run identified the new action missing from the replay test's exhaustive
action fixture; the fixture was extended and the complete run passed. The first consumer compile
caught an incorrect test-only `close()` call, corrected to the lifecycle's idempotent `stop()`.
Studio preflight caught stale workflow template URL/hash pins, which were synchronized before rerun.
These were introduced during this work, not pre-existing runtime defects. Initial sandbox denials
for Gradle caches and temporary Git fixtures were resolved through the normal approval mechanism.

## Performance and hardware boundary

Warmed desktop geometry benchmark, four clusters / four members each, 2,000 measured updates:
median **3.0 µs**, p95 **3.3 µs**, p99 **3.5 µs**, worst observed **125.7 µs**. No update exceeded the
1 ms incremental desktop geometry budget. Measured geometry allocation was **0 bytes** over those
updates. The surrounding FTC robot loop budget is 20 ms.

These figures exclude Limelight image processing, USB/polling latency, SDK JSON parsing, and Redux
snapshot publication. They are not Control Hub or camera measurements. New immutable observations
allocate at the existing Redux ownership boundary; no whole-loop allocation claim is made.

No physical cameras/controllers were available. Remaining hardware checkpoint: correct Full 3D
pipeline and 82.55 mm tag size, intrinsic and mounting calibration, one-tag-at-a-time sign/origin
checks against the measured cell opening, realistic cell angles/occlusion, image detection under
motion, end-to-end latency, and enabled/freshness/Stop checks with the calibrated aim controller.
The simulator test injects optical observations; it does not render or detect AprilTag images.

FIRST source archive provenance is retained in `source-archives.json`: Vision 12.0.0 SHA-256
`756294f8307da88b85a73c5a804dbc20f65dd60af97534ed3dea8f38e528aba8`; Hardware 11.1.0 SHA-256
`eeebb871b8b85e3ac1cfe445237ee60a11a26e6b4e58ba5c32f57d429b2aa2dd`.

Next action supported by evidence: validate the real camera's reported opening and motion latency
before binding cluster feedback to automatic aiming. Joint PnP, an editor for arbitrary cluster
layouts, and image-level simulation are optional follow-ups, not implied by these software results.
