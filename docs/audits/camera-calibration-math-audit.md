# Camera calibration math and data-loading audit

Pass 239 audits previously pending camera calibration services, their numerical diagnostics,
telemetry loader and service tests. Pass 238 finished clean at
`25e0986c29f6a90c3217daa107bcfee3dfff53a6`. This pass validates Studio tree
`001ca2ffd327427927eaf159f93f0960bffff729` against unchanged local ARESLib candidate `17.0.42-rc.526a048d8dfb`
and library tree `526a048d8dfb1092e89be95c8e906e9fbc516027`.

## Confirmed findings and changes

1. **Optical and robot rotation axes were mixed.** The old solver rotated a right/up/depth
   vector, then permuted its components, while returning those optical Euler angles as robot
   roll/pitch/yaw. Even an independently generated 90-degree mounting yaw produced a large
   spurious roll. Measurements now explicitly represent the tag in camera optical coordinates
   (right/down/forward). They convert to ARES forward/left/up as `(z,-x,-y)` before applying
   the mounting rotation. Returned angles describe `Rz(yaw) Ry(pitch) Rx(roll)` in robot axes.
   Legacy `targetSpace*` property names remain, with their corrected meaning documented.
   The separate robot-in-target-space alignment contract is unchanged.
2. **Missing or unobservable data reported a successful zero calibration.** Empty poses and
   zero uncertainty were indistinguishable from a perfect fit. Nonfinite direct inputs could
   return NaN poses. Both entry points now reject invalid or unobservable geometry. A
   cross-covariance rank check admits noncollinear planar data and rejects repeated/collinear
   points. Symmetric reflected data without a unique proper rotation is rejected. Multiple
   tags at one heading can be observable; the old quadratic heading-span gate was insufficient.
3. **Diagnostics could manufacture confidence.** The old code regularized a singular
   information matrix, or substituted a zero matrix after inversion failure. The new analytic
   Jacobian and rank-checked SVD covariance do not invent finite Euler uncertainty at gimbal
   lock. A canonical physical pose remains available from the pose-only entry point there.
   SSE/(3N-6) is documented as residual variance in square meters, exposed as `residualVariance`;
   `reducedChiSquared` remains a compatibility name. There is no supplied observation variance
   to make this a dimensionless chi-squared statistic. Diagnostic equality now agrees with
   hashing for signed zero and NaN.
4. **The loader accepted malformed and inactive observations.** Loose digit extraction
   accepted arbitrary component names and threw on oversized suffixes. Fractional camera IDs
   were truncated into valid IDs, and IsActive was ignored. The loader now uses exact topic
   grammar, exact nonnegative IDs, finite numeric geometry, authoritative text handling and
   fresh active flags when present. Scoped components never borrow missing values from a
   generic camera stream. Optical rotations are unused by this fit and no longer required.
5. **Submillisecond identity and repeated work were lost.** Nearest samples now use original
   microseconds and deterministic earlier-sample ties within the existing 100 ms bound. One
   classification pass replaces repeated filtering and per-frame regular-expression creation.
   Database loading selects only 16 relevant topics, uses existing ordered export pages without
   per-topic downsampling, and checks the 100,000-update cap and row-count consistency. It
   neither loads the whole robot log nor silently fits a truncated recording.

All 12 initial regressions failed against the old implementation; their XML is preserved.
The old synthetic test copied the solver's transform and used loose angle tolerances. Service
tests now use independently composed physical rotations and tight pose assertions. Their empty
input expectations now require an explicit failure. Pure tests no longer create unused
databases, and actual database fixtures close their connection before deleting owned files.

The unused `OdometryCalibrationSolver` was an empty class with no algorithms. Its only owner
allocated it without calling any methods. The class and allocation were removed; this does
not add or claim an odometry calibration feature.

## Numerical method and efficiency

The fit minimizes equal-weight 3D translation residuals. Known field-tag positions first
rotate into the level robot frame. A centered 3x3 cross-covariance SVD yields the proper
rotation and centroid difference yields translation. This replaces two duplicated 200-step
finite-difference optimization implementations with a shared, noniterative rigid fit. A
determinant correction preserves a proper rotation. The mathematical reduction follows the
[NIST Kabsch-Umeyama derivation](https://nvlpubs.nist.gov/nistpubs/jres/124/jres.124.028.pdf);
the [EJML SVD API](https://ejml.org/javadoc/org/ejml/simple/SimpleSVD.html) supplies the decomposition.
The [Limelight camera-space axes](https://docs.limelightvision.io/docs/docs-limelight/pipeline-apriltag/apriltag-coordinate-systems)
support the optical basis conversion.

The diagnostics use the derivatives of robot-frame Euler rotations and a 6x6 information
matrix. Independent checks include a symmetric six-point dataset with closed-form residual
variance/covariance, and a central-difference Jacobian built from separate physical axis
rotations at a mixed-angle noisy solution. Tests cover heading wrap, both pitch singularities,
planar geometry, reflection ambiguity, nonfinite/overflowing inputs and equality semantics.
Both public fitting entry points read each observation once: a counting list measured exactly
12,000 accesses for 12,000 observations. This verifies structural work reduction; it is not
a robot-loop latency or end-to-end wall-clock benchmark.

Loader cases cover malformed/oversized keys, invalid IDs, active flags, scoped/generic
isolation, text placeholders, source-microsecond selection, skew boundaries, supported timestamp
extremes, multiple pages, oversized/changing recordings, and owned DuckDB roundtrips. Initial
boundary fixtures violated TelemetryFrame's timestamp invariants; they were corrected to use
consistent microseconds and the declared maximum timestamp. Production validation was retained.

## Validation

The final focused run passed 30 tests: 26 new audit methods and four revised service tests.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,142 | 6 |

Full Studio validation has 2,191 passing results, zero failures/errors and six unchanged
opt-in skips. App tests executed; unchanged build dependencies include up-to-date/cache
evidence. Focused tests are not counted twice. All 410 unchanged library candidate files
were rehashed; prior library and robot consumer validation is retained. Monorepo policy
passed, including 401 current-document link checks and 38 historical skips.

## Coverage and limits

The ledger accounts for 3,045 tracked files: 1,366 reviewed, 190 partially reviewed and
1,489 pending, with zero stale or orphaned records. These are scoped file-review and
appropriate-validation counts, not universal executable test coverage.

Calibration assumes a level robot rotating around a fixed, known field origin. Tag coordinates
must be relative to that rotation center. The inputs cannot identify robot translational
motion, an unknown origin, tag-map errors, optical distortion or systematic vision bias.
Covariance is a local approximation for IID isotropic translation noise. Legacy recordings
using the old ambiguous coordinate interpretation must be checked against the documented
camera-space contract before reuse.

Use a completed recording. Row-count checks cannot detect equal-count concurrent replacements,
and nearby scalar timestamps are not proof of one atomic sensor capture. No physical camera,
robot, HIL, rendered Studio window or real-time control loop was exercised. Current monorepo
call-site searches found the shared calibration publisher used by tests, not a production
robot routine; external producers were not validated. No library bytes, versions, starter
archives, remote CI, push, merge, release or deployment changed. The broad telemetry document
retains partial review; this pass verifies its calibration section only.

Machine-local evidence is in `ARESLib-Kotlin/build/audit-pass239-verified-evidence/`, including
the failing baseline, focused/full XML and logs, input-access count, candidate hash comparison,
policy output and summary. The overall monorepo audit remains active.
