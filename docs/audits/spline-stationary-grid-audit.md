# Stationary parameters in spline sampling

Pass 99 checks reversals and regular stalls between the previous sample parameters.
The cubic with scalar controls [0,1,1,-2] has derivative 3-6t-6t² and a reversal at
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

Pending frozen-candidate validation.
