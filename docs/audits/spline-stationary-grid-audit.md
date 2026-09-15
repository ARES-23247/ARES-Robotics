# Stationary parameters in spline sampling

Pass 99 checks reversals and regular stalls between the previous sample parameters.
The cubic with scalar controls [0,1,1,-2] has derivative 3-6t-6tÂ² and a reversal at
(sqrt(3)-1)/2. Its 40-step grid missed that root and never imposed a stop. One preserved
failure-before regression demonstrates the missing extremal position.

## Changes

Both parsed and generated natural-cubic paths now discover common roots of their two
quadratic derivative components before constructing geometry. A strictly monotone
control coordinate proves there is no interior stationary root and avoids exact arithmetic.
Other candidates use exact BigDecimal representations of the stored Double controls.
Proportional polynomials, linear/quadratic combinations and elimination of quadratic
terms are checked without division or tolerance-based common-root decisions. A near
miss therefore remains a regular curve, whose curvature still needs appropriate sampling.

Root locations use a stable quadratic formula and decimal square-root iteration, with
precision chosen from the exact coefficient precision. This preserves tiny terms that
distinguish an interior root from an endpoint. Locations are then rounded to Double grid
positions; an interior location that rounds onto a segment boundary rejects explicitly.
Multiple roots rounding to the same grid position share a sample, with stop classification
taking priority. This is finite numerical sampling, not exact real-number geometry.

Simple common roots impose stops. Repeated common quadratic roots retain their unique
third-derivative direction and permit smooth travel. This classification accompanies the
sample, so a rounding residual in the evaluated derivative cannot undo the stop or invent
a reversal at a regular stall.

Critical parameters merge with the regular grid and constraint boundaries. Sorted unique
positions share the 100,000-sample budget before geometry allocation. Monotone lookup
avoids a per-sample search or boxed map key. Exact arithmetic and its allocations occur
per candidate segment, not per sample; no overall timing improvement is claimed.

## Validation and remaining scope

Thirteen new methods cover the original reversal, tiny scaling, exact coordinate
transforms, independent component roots, near misses, mixed polynomial degrees, linear
components, two simple roots, a repeated off-grid root, root-free cases, natural-cubic
overshoots, total-budget accounting, constraint deduplication and roots unrepresentable
near either endpoint. Some methods cover multiple related cases.

The profiler remains partial for unsampled curvature extrema, near-stationary regular
curves, chord/arc-length approximation, long-handle resolution and point-towards zones.
Stationary-root insertion does not certify continuous-curve speed/acceleration bounds.
No physical hardware timing or usable Studio-window result is claimed.

## Final validation

The frozen source passed 219 pathing tests and API checks. Thirteen new methods include one preserved failure-before case. Public API signatures are unchanged.

Kover `SplineStationarySampling.kt`: 59/62 lines and 85/96 branches executed. Kover `SplineMotionProfiler.kt`: 220/220 lines and 111/116 branches executed. Execution coverage does not certify the unsampled geometry; the profiler remains partial.

Source `a162fc17846c67cede63c0af9689a6ec3ed63278`; library tree `af2714ebd99afc5dea56ceec8fca1acf8b3f7aa3`.
Local candidate `17.0.3-rc.af2714ebd99a`.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 2177 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs; counts do not imply every test was freshly executed. Conditional Studio skips remain recorded in XML.

Copied XML, hashes, logs and candidate BOM identity are under `ARESLib-Kotlin/build/audit-pass99-verified-evidence/summary.json`; focused XML is under `ARESLib-Kotlin/build/audit-pass99-focused-evidence/`. No physical timing, measured JIT allocation rate or usable Studio-window result is claimed.
