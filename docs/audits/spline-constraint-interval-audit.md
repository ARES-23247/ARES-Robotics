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

The complete existing seven-method SplineProfileAuditTest was also reviewed and executed. Its endpoint/edge limits and rotation cases remain valid. Its curvature check asserts the finite-difference contract rather than analytical curve accuracy; its printed desktop timing has no threshold and is not a robot latency guarantee.

## Final validation

The frozen source passed 193 pathing tests and API checks. Twelve new methods include six distinct preserved failure-before cases. Public API signatures are unchanged.

Kover `SplineConstraintSampling.kt`: 45/45 lines and 46/48 branches executed. Kover `SplineMotionProfiler.kt`: 192/192 lines and 94/100 branches executed. Line execution is not proof of numerical correctness; the profiler remains partial for the scopes above.

Source `58ba65dd72cbbd7f141cbf9e0786275ff5346d92`; library tree `821a789fb038223c89751ff66b59bb49f9bdbf49`.
Local candidate `17.0.3-rc.821a789fb038`.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 2151 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs; counts do not imply every test was freshly executed. Conditional Studio skips remain recorded in XML.

Copied XML, hashes, logs and candidate BOM identity are under `ARESLib-Kotlin/build/audit-pass97-verified-evidence/summary.json`; focused XML is under `ARESLib-Kotlin/build/audit-pass97-focused-evidence/`. No physical timing, measured JIT allocation rate or usable Studio-window result is claimed.
