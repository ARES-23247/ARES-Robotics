# Estimator numerical stability and shared covariance calculations

Pass 76, in progress, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Confirmed fixes

- `Matrix3x3.inverse` formed a shared reciprocal that overflowed for a matrix whose
  complete inverse was representable. Cofactors now divide by the determinant before
  restoring the input scale. The existing normalized near-singular cutoff is retained.
- Scalar Kalman innovation/prediction sums could overflow, producing zero or NaN gain.
  Overflowing sums now use normalized variances; ordinary inputs retain direct arithmetic.
- Opposite finite extreme observations could have an infinite residual and be ignored.
  That case now evaluates their finite weighted combination.
- An underflowed gain erased representable prior covariance. The posterior now multiplies
  the smaller variance by the well-resolved weight. Invalid initial/reset state or variance
  fails before mutation; invalid observations/noise retain the existing suppressed-update behavior.
- Forward odometry and EKF interpolation/replay formed the product of translation and
  heading noise scales before taking its square root. Overflow/underflow corrupted finite
  correlated noise. Both now multiply the individual square roots.
- Forward, interpolated and replayed EKF covariance calculations share one scalar kernel.
  Primitive arguments snapshot covariance before output writes and preserve scratchpad
  aliasing. This removes repeated formulas; no loop-time improvement is claimed.
- Matrix documentation now distinguishes allocation-free mutators from allocating value
  operators and inverse/transpose results.
- Stationary dwell and recovery used zero as an inactive timestamp sentinel. Explicit
  active flags now preserve zero-time intervals through deep copies and immutable snapshots.
  Direct-call clock rewinds restart active intervals; forward elapsed-time overflow cannot
  suppress dwell completion. Movement and beaching interrupt stationary learning.
- Runtime timestamp subtraction could accept a backwards timestamp after overflow or reject
  a large forward interval. Ordering is checked first; large forward intervals use the
  existing 100 ms cap. Pose reset clears old dwell/vision diagnostics while retaining bias.
- Bias learning now evaluates its exponential only while learning. `expm1` also preserves
  small positive weights. The public snapshot/workspace timing flags change generated JVM
  constructor/copy signatures; consumers must be rebuilt against the new candidate together.

## Evidence so far

Seven numerical regression methods failed before their respective fixes: four in the initial
eight-method fixture, followed by one each for replay noise, forward noise and posterior
underflow. Logs and copied XML are under `ARESLib-Kotlin/build/audit-pass76-`, with suffixes
`before`, `cross-scale-before`, `forward-scale-before` and `posterior-before`.

Nine of ten initial timing/ownership methods also failed before their fixes; their separate
baseline is `audit-pass76-timing-before.{log,xml}`. Two further timing tests verify recovery
hydration and movement/invalid-sample boundaries.

The final focused invocation passed 84 methods, including 14 new numerical methods,
12 timing/ownership methods, snapshot/external-estimator tests and all five
`ZeroGcRegressionTest` methods. Reference checks use a
60-digit scalar recurrence, independent indexed dense matrix products, seeded matrices
across seven scales, and midpoint/chord arc calculations for interpolation/replay.
Stored-motion and reconstructed legacy history both pass; forward output/Q aliasing passes.
These extreme-value tests establish numerical behavior, not realistic sensor operating ranges.

The API dump was reviewed: only snapshot/workspace timing flags and their generated
constructor/copy/components changed. Full candidate/API/consumer validation and final
file-ledger credit are pending. No physical hardware timing or calibration was performed.

## Remaining scope

This batch reviews numerical kernels, scalar filter behavior, estimator timing and immutable
snapshot transfer. Public history capacity/insertion/alias boundaries, global calibration
mutation, complete vision-controller behavior and physical calibration remain separate scopes.
Direct callers still own monotonic history/observation sequencing; restarting a timing interval
does not reconstruct a valid old trajectory after a clock rewind. Snapshot hydration preserves
current scalar state, not historical camera corrections or the full past trajectory.
