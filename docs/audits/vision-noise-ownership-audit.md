# Vision noise numerics and field-map ownership

Pass 78 in progress, 2026-09-10. Changes remain local and unfrozen; no candidate validation
or file-ledger credit has been claimed for this pass.

## Confirmed corrections

- Unmapped tag range used only target-space Z. Off-axis and vertical displacement now contribute
  through the full translation norm. Hypot also avoids squaring distance before normalization.
- The covariance factorization ignored the upper triangle and could publish an accepted NaN pose
  from a malformed prior. Finite, symmetric, positive-semidefinite covariance is now checked before
  and after correction. Correlation normalization checks small axes independently; zero variance
  requires zero cross covariance. Dimensionless tolerances allow floating-point roundoff.
- Absolute innovation-pivot cutoffs rejected valid small covariance, while products of Cholesky
  pivots overflowed for large correlated covariance and produced incorrect gains. The filter now
  normalizes P and R before addition, whitens the residual in those units, and solves for the gain
  without forming an inverse. Existing caller workspaces are reused.
- A common normalization scale could erase valid independently small covariance axes. Each axis
  now uses its own standard-deviation scale, with the gain restored to physical units after solving.
  The independent oracle checks NIS and every gain/posterior entry for three permutations of axis
  scales 1e-120, 1 and 1e120, with nonzero translation residuals.
- The fixed posterior diagonal floor could increase uncertainty above a valid smaller prior.
  Joseph correction now preserves the calculated nonnegative variance without that absolute floor.
- Observation-specific standard deviations were scaled again by distance, tag count and incidence.
  Store processing now distinguishes reported and baseline uncertainty per axis. It skips geometric
  scaling when every axis already has observation-specific uncertainty; MegaTag2 retains its
  deliberately uninformative heading variance. New optional scalar-API flags preserve baseline
  behavior for existing source callers.
- The inner filter used its default ambiguity limit even after the Store's configured limit passed
  the outer filter. The configured value now reaches both checks. Invalid limits fail closed, and
  ambiguity explicitly marked unavailable does not become a spurious rejection or numerical scale.
- Delayed replay could publish non-finite pose/covariance and persist a capture-time history split
  before replay succeeded. Splitting and replay now use the existing scratch buffer; invalid replay
  leaves live pose, covariance and every history entry unchanged. Original adjacent poses are kept
  as scalars when reconstructing legacy motion in place. This adds no per-frame history allocation.
- The starting-pose helper ignored configured dimensions and placed default XRP starts outside
  their centered field. Starts now use resolved dimensions and the existing FTC/FRC wall inset,
  bounded for small fields. Invalid explicit dimensions throw before producing a pose. This helper
  positions a point, without asserting robot-footprint clearance or changing simulator waypoints.
- File-based field loading bypassed semantic validation. It now uses the shared validator and
  preserves the accepted configuration when dimensions, duplicate tags or other field data fail.
  The AprilTag lookup documentation now identifies its independently owned, mutable pose values.

## Evidence so far

Seventeen distinct methods failed before their fixes: seven numerical/covariance/range cases,
two repeated observation-uncertainty cases, two ambiguity-contract cases, four field-boundary
cases, one failed-replay atomicity case and one mixed-axis scaling case. Copied baseline
XML/logs are under `ARESLib-Kotlin/build/audit-pass78-` with suffixes `before-expanded`,
`observation-before`, `ambiguity-before`, `field-before`, `replay-before` and `axis-before`.

Additional tests compare every gain/covariance entry against an independent elimination-based
oracle for ten dense positive-definite matrices at five scales from 1e-24 to 1e240. They also
cover singular valid priors, indefinite matrices, zero/small-axis invalid cross covariance and
invalid target range. Existing vision, fractional replay and zero-allocation checks run with them.
The latest focused run passed 107 tests with zero failures, errors or skips. It includes existing
field/vision/replay/calibration tests, independent estimator math checks and five zero-allocation
regressions. A closed-form rotated trajectory and covariance case also verifies in-place replay
of legacy entries without stored motion. Copied XML, log and source hashes are recorded in
`ARESLib-Kotlin/build/audit-pass78-final-focused-evidence/`.

## Remaining scope before the candidate matrix

- FieldLayouts and the facade retain the explicit process-global activeTags map, configured by
  FTC/FRC startup. Store-owned history does not imply store-owned field configuration. No supported
  contract for simultaneous distinct field maps in one process was established; this observation
  does not justify a new ownership API. Manual manager assignment remains a compiled-code boundary;
  the file-loading fix does not make all configuration objects deeply immutable.
- Known-tag baseline noise retains its planar distance/yaw-incidence heuristic. Physical accuracy
  of this model needs calibrated camera evidence; reported observation uncertainty bypasses it.
- Complete API snapshots, source identity, the full dependency-ordered candidate matrix and ledger
  reconciliation once the batch is stable. New optional parameters change JVM descriptors.
- Geometry.kt and its existing four-method fixture were read; default-locale formatting needs a
  contract decision before treating its fixed-decimal test expectation as a production defect.

These are host numerical and control-flow tests. They do not establish camera calibration accuracy,
physical robot loop timing or hardware behavior.
