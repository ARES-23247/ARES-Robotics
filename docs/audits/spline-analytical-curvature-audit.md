# Analytical curvature at spline samples

Pass 98 checks emitted geometric curvature, stationary samples and shared cubic joins.
This follows the constraint-interval pass; it does not claim complete sampling accuracy.

## Confirmed errors

For controls (0,0), (0,100), (1,100), (1,0), the midpoint has derivatives B'=(1.5,0)
and B''=(0,-600). Its exact curvature is -266.6666666667 per metre. The old neighboring
tangent/distance estimate was about -2.01845, allowing an emitted speed of 0.99542 m/s.
The actual pointwise 2 m/sÂ² centripetal ceiling permits only about 0.08660 m/s.

A collinear reversal cusp retained 0.19544 m/s instead of stopping. Further regressions
found that a right-angle shared waypoint retained 1.41421 m/s, and a continuous-tangent
join ignored the larger outgoing curvature (6666.66667 per metre). Four distinct test
methods have preserved failure-before evidence across the initial and junction runs.

## Corrections and cost

An internal differential evaluator caches each cubic's control differences and shares
first derivatives between tangent and curvature evaluation. Regular sampled parameters
use cross(B', B'') / |B'|Â³. Unit vectors and binary exponent scaling avoid intermediate
squared-norm underflow on tiny representable geometry. Nonzero regular curvature that
cannot be represented as a finite nonzero Double is rejected explicitly.

At a sampled reversal or divergent-curvature endpoint, velocity is forced to zero.
Curvature uses a finite zero placeholder there because no finite geometric value exists;
it is not an assertion that the singular geometry is straight. Constant segments and
single-point profiles also stop. Collinear collapsed endpoints retain their regular
one-sided limits. A cubic with B'=B''=0 but nonzero B''' has a unique travel direction
on both sides and can continue along it. Public BezierSpline helper contracts are unchanged.

Shared waypoints account for both incoming and outgoing geometry. Tangent discontinuities
force a stop; a tolerance of 32 ulps of pi permits angular floating-point roundoff. The
larger absolute one-sided curvature constrains speed at a continuous-tangent join. Normal
natural-cubic continuity is tested to avoid introducing an artificial junction stop.

The old finite-difference curvature sweep is removed. Differential evaluation reuses
primitive output fields, with one evaluator per segment and one BooleanArray of stop
flags per constructed path. Position/result allocations remain. This trades sampled
approximation for analytical arithmetic; no measured overall speedup or robot latency
improvement is claimed.

## Evidence and limits

Thirteen new methods cover the four failures, an independent power-basis derivative
oracle on 50 deterministic curves, reversal identities, scaling down to 1e-240, regular
and singular stationary cases, both sides of joins, constant geometry and unrepresentable
curvature. The existing tight-curve regression now uses its analytical closed form rather
than repeating the removed finite-difference formula.

SplineMotionProfiler remains partial. Curvature is analytical at emitted parameters;
unsampled curvature extrema and stationary roots, chord/arc-length approximation,
long-handle resolution and point-towards refinement still need review. This pass does not
certify continuous-curve centripetal acceleration, continuous-time acceleration or jerk,
nor does it claim hardware or usable Studio-window validation. Finite Path sample checks
and follower rejection of nonfinite fields remain intact.

## Final validation

The frozen source passed 206 pathing tests and API checks. Thirteen new methods include four distinct preserved failure-before cases. Public API signatures are unchanged.

Kover `SplineDifferential.kt`: 48/48 lines and 21/22 branches executed. Kover `SplineMotionProfiler.kt`: 212/212 lines and 108/114 branches executed. Execution coverage does not certify the unsampled geometry; the profiler remains partial.

Source `dd4eee4f58aa4c5e78f6bc15e60531ab4bb3fab1`; library tree `d18f9bd5f972a5af872e5a311fe3cf12e9e3fe0c`.
Local candidate `17.0.3-rc.d18f9bd5f972`.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 2164 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs; counts do not imply every test was freshly executed. Conditional Studio skips remain recorded in XML.

Copied XML, hashes, logs and candidate BOM identity are under `ARESLib-Kotlin/build/audit-pass98-verified-evidence/summary.json`; focused XML is under `ARESLib-Kotlin/build/audit-pass98-focused-evidence/`. No physical timing, measured JIT allocation rate or usable Studio-window result is claimed.
