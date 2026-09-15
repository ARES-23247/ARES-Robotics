# FTC current reconciliation and sampling audit - pass 88

This pass closes the FTC aggregate-current and registry-lifecycle scope left by pass 87, and
reviews the shared cached-current sampler. It covers coherent arithmetic, optional measurement
fallbacks, ownership, voltage-sampling timing and steady-loop allocations. All work stays local.

## Confirmed defects and fixes

- Aggregate reconciliation subtracted motor estimates before calibration but added them afterward.
  A measured 12 A branch became 6.4 A after downward calibration; a measured 20 A branch became
  25.6 A after upward calibration. The new core `updateFromCurrentSources` entry point reconciles
  branch measurements against the calibrated samples from the same frame, then advances budget
  hysteresis exactly once. Existing `update` and its binary signature remain available.
- Combining all branch differences before clamping let one branch's low measurement cancel
  another branch's additional load. Two branches requiring max(10, 5) + max(10, 15) = 25 A were
  reported as 20 A. Each selected branch now keeps its own conservative floor. Shared motors are
  subtracted only once for overlapping aggregates, which may conservatively overestimate load.
- Reconciliation repeated each covered motor's effort, scale and velocity reads. It now consumes
  the same cached model sample used for calibration. A test asserts one read of each input and
  one read of the selected aggregate; current calibration remains round-robin.
- Unknown mechanism current was dropped and treated as zero. Uncovered invalid non-motor leaves
  now invalidate the budget and set available effort to zero. Invalid optional aggregates can
  fall back to registered modeled constituents. Unregistered motors in the new core entry point
  are rejected as unknown; their load is never silently assumed to be zero.
- Replacing a registry motor left its old model slot active. Identity-based `unregister` preserves
  surviving electrical models, calibration, round-robin position and trip history. FTC tracks its
  registry-owned slots, removes obsolete ones and preserves explicitly external models. Unchanged
  registry topology takes a linear identity-comparison path without allocating new snapshots.
- Timestamp zero was mistaken for an unsampled voltage frame. Clock rewind could retain stale
  healthy voltage, and ordered signed timestamp subtraction could overflow. Sampling now tracks
  initialization explicitly, reseeds voltage on rewind and saturates ordered elapsed overflow.
  The normal 20 ms sampling interval and filter time constant are unchanged.
- Duplicate current-provider identities and cyclic coverage declarations could suppress every
  valid reading, including a cycle beside an independent source. The sampler reads duplicate
  identities once and conservatively retains readings not represented by a selected provider.
  Custom validity callbacks can no longer bless nonfinite or negative current.

## Test evidence and scope

Nine FTC regressions failed before the corresponding changes. Four independent shared-sampler
regressions also failed before their fixes. Failure XML is preserved under
`ARESLib-Kotlin/build/audit-pass88-before-results/`.

The new tests additionally exercise calibration preservation, manager replacement, external-model
ownership, unregister/round-robin behavior, unknown-model rejection, one-transition-per-frame
hysteresis, invalid-battery read avoidance, sampler capacity growth/shrink/reset and exception
isolation. Existing FTC and FRC power tests check their shared-sampler integration.

The FTC allocation test now includes four motors and two aggregate branches. It warms the JVM,
requires two consecutive zero-allocation windows of 10,000 updates, and bounds one-time overhead.
It explicitly skips when allocation instrumentation is unavailable instead of silently returning
as a pass. An early run with the old short warmup observed 704 bytes in its second window; this
was not treated as a zero-allocation result. Final observations are recorded with validation below.

The full FTC coordinator was reviewed, including its raw-voltage brownout path, Floodgate
plausibility/overload selection and scale distribution. Motor-setter exceptions propagate to the
existing fatal-update/safe-hardware boundary in `FtcBaseRobot`; that boundary was traced and was
not changed. Standalone users still own hardware neutralization when update throws. Broader FRC
power behavior and Floodgate calibration/integration remain separate audit scopes.

These host tests do not establish physical loop deadlines, electrical-current accuracy or fuse
protection. Cyclic/overlapping ownership is handled conservatively and may reduce available power
until the hardware's coverage declarations are corrected. No upstream library defect is claimed.

## Final validation

The final source passed 89 selected core/FTC/FRC tests and all library API checks before freezing. Twenty-five new methods include thirteen distinct failure-before regressions. The core API adds unregister and updateFromCurrentSources while retaining the existing update signature. The allocation test observed zero bytes in each of two consecutive 10,000-update windows.

Source `bf67f70ad93ffc675deedc1ff86cd970a0d048cb`; library tree `fad5f84844de787b5a969a1bb6cd32a392daa60b`.
Local candidate `17.0.3-rc.fad5f84844de`.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 2022 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs; passing counts do not imply every test was freshly executed. Conditional Studio skips are recorded in the copied XML.

Copied XML, per-file hashes, build logs and candidate BOM identity are recorded under `ARESLib-Kotlin/build/audit-pass88-verified-evidence/summary.json`. Validation is on the host and consumers; no physical timing, current accuracy, fuse protection or usable Studio-window result is claimed.
