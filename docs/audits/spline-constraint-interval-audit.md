# Spline constraint interval audit

Pass 97 examines constraint coverage between samples and the cost of selecting an active
zone. It closes a different profiler scope from pass 96's input and allocation checks.

## Confirmed errors

The profiler selected constraints only at regular samples. A linear 1 m path sampled
every 0.05 in waypoint-relative position skipped a speed zone at 0.501..0.509 entirely.
Narrow acceleration zones and zero-width speed constraints were missed too. Three new
regression methods failed on the previous source.

Adding boundaries exposed a further transition problem: when the first overlapping zone
ended, an endpoint could retain its higher speed while the following edge belonged to a
slower zone. Leaving a faster zone for the global limit had the same problem. Two more
methods failed before the edge correction.

A computed midpoint was not sufficient to identify every edge's zone. For adjacent
floating-point boundaries, the midpoint can round onto an endpoint and select the wrong
limit. A sixth method reproduced this. The sweep now queries the open interval immediately
after the earlier boundary, avoiding midpoint arithmetic entirely.

## Final behavior and efficiency

- Sorted, unique constraint boundaries supplement the regular grid. Existing aligned
  boundaries add no samples, and zero-width zones retain their single constrained point.
  The shared 100,000-sample budget includes additions before geometry allocation.
- Every edge interior has a constant zone priority because every boundary is sampled.
  Its speed ceiling constrains both endpoints, keeping interpolated speeds within that
  ceiling. Its acceleration bound includes both endpoints and the interior, and both
  forward and backward energy sweeps use that same edge bound.
- Zone selection preserves first-input-match priority and inclusive endpoint semantics.
  A boundary speed can be reduced further by its neighboring edge; priority does not
  authorize a discontinuous speed change.
- An ordered activation sweep and priority queue replace a full zone scan at every
  sample. Each zone enters and leaves the queue at most once: O(N + Z log Z) selection
  work replaces O(N Z), with O(Z) scratch storage. A snapshot provides random access even
  for caller-supplied linked lists. No cursor is allocated for unconstrained profiles.
- Regular samples remain; constraint samples can improve the existing next-sample event
  mapping and change which sample is nearest a rotation target. Event/rotation mapping
  rules are unchanged. No separate point-towards boundary refinement is added here.

These are source complexity and numerical observations, not measured robot loop times
or JIT allocation rates. Constraint selection is part of path construction.

## Validation scope

Twelve new methods include six distinct preserved failure-before cases across three
runs. Checks cover speed throughout narrow intervals, edge energy bounds, point zones,
aligned and duplicate boundaries, overlapping transitions, return to global limits,
exact total-budget accounting, multiple segments and adjacent floating-point boundaries.

A seeded sweep compares inclusive and open-interval cursor results with an independent
linear first-match oracle over 25 sets of 40 unsorted overlapping zones. Another checks
public sampled speeds at more than 20,000 positions across 20 randomized paths. These
checks test physical ceilings and priority semantics rather than asserting a wall-time
threshold. Acceleration evidence concerns the emitted edge energy bounds, not a new
continuous-time interpolation or jerk guarantee.

SplineMotionProfiler remains partial for geometric sampling accuracy, long handles,
curvature approximation, stationary/cusp behavior and point-towards zone refinement.
The complete constraint helper and new test class are reviewed; broader PathPlanner
schema compatibility and hardware validation are not implied.

## Final validation

Pending frozen-candidate validation.
