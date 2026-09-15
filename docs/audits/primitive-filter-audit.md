# Primitive filter math and repeated work audit - pass 39

This pass reviews all of `LowPassFilter.kt`, `MedianFilter.kt` and `SlewRateLimiter.kt`,
their three existing filter test files and three added test files. The shared filters
operate on caller-supplied measurements and elapsed seconds. Retained output does not
establish sensor freshness; enable, feedback validity and actuator safety remain caller
responsibilities. The nearest mathematical contract now documents these boundaries.

## Confirmed findings and fixes

- Even-window medians could overflow when averaging two finite, same-sign large values.
  The midpoint now halves operands only when their direct sum overflows. Ordinary sums
  preserve subnormal midpoint rounding. Invalid reset values clear history.
- Median updates and getters repeated full-window copying and sorting. A preallocated
  sorted window now removes the expired ring entry and inserts the new value using binary
  search and primitive array shifts. Updates are O(N) worst case; getters and rejected
  measurements read the cached median in O(1). Duplicate values and signed zeros preserve
  the sorted-window contract. No per-update array is created. Documentation no longer
  promises rejection of arbitrary impulse bursts without phase delay.
- Low-pass weighting could freeze when `RC + dt` overflowed, or lose a representable
  contribution when a tiny relative weight rounded away. The calculation normalizes time
  weights by their larger operand and rescales rare underflow cases by binary exponents.
  Subnormal signal pairs are raised before weighting; stationary signals return directly.
  Endpoint clamping prevents rounded weighted sums from escaping their convex interval.
  The existing backward-Euler recurrence remains in use; this is not exact continuous-time
  exponential filtering, and different timestep partitions can give different results.
- Nonfinite low-pass resets poisoned subsequent estimates. They now clear history.
  Nonpositive elapsed time previously could seed or bypass a low-pass filter, and could
  reseed a cleared slew limiter. Both reject that time before initialization or bypass.
- Simultaneously overflowing slew differences and rate-times-time budgets could emit
  infinity even when the permitted endpoint was finite. Half-scale comparisons and updates
  retain the bounded intermediate result or reach the finite target when allowed. Ordinary
  finite budgets retain their normal path. Magnitudes are cached when rates are configured.
  Existing sign normalization, zero directional budgets, initialized constructor/reset
  behavior and explicit clear/reseed behavior are preserved.

Public method and constructor signatures are unchanged. Low-pass state is used by the
holonomic facade; FTC mecanum feedforward uses the slew limiter. No production median
call site was found, but it remains a published API. These are mutable single-owner
primitives; this pass does not add concurrent access support.

## Validation

The initial nine regression methods all failed against the previous implementation in
1m 14s (`ARESLib-Kotlin/build/audit-pass39-before.log/xml`). They cover finite median
overflow, invalid resets, overflowing/tiny low-pass weights, invalid-time seeding and
overflowing slew steps. The final additions comprise 16 boundary methods, three independent
oracle methods and one allocation method.

The focused gate passed all 47 methods in 11s, including the existing five-method
`ZeroGcRegressionTest` (`ARESLib-Kotlin/build/audit-pass39-focused.log`). The oracles use
5,000 median operations across five capacities, 36 high-precision RC-weighting cases and
294 high-precision slew-clipping cases. Oracle tolerances allow floating-point rounding,
including endpoint-scale tolerance for extreme cancellation. They do not prove correctly
rounded results for every representable input. The dedicated allocation test executed,
without a skip, and measured zero bytes in two consecutive warmed-up 10,000-update windows.
It covers ordinary/rejected inputs, repeated getters and rare scaled arithmetic.

The full library test, API, Kover and isolated publication gate passed in 1m 38s. Reports
contain 1,140 passing tests, including 783 core tests, with no failures or skips. Changed
and dependent suites executed; unchanged project-schema and telemetry-schema tests were
up-to-date. Kover reports:

| Source | Executable lines covered | Branches covered |
| --- | ---: | ---: |
| LowPassFilter.kt | 41/42 | 44/44 |
| MedianFilter.kt | 37/38 | 24/24 |
| SlewRateLimiter.kt | 35/36 | 46/50 |

Source commit `d54d101a` binds candidate `17.0.3-rc.f252cc83428a` to library tree
`f252cc83428a90552c129f0a28269a4b152b3302`. The branch already carries the planned final
17.0.3 bump relative to local main's 17.0.2; this pass assigns a new immutable prerelease
and source identity. Publication was only to the isolated local validation repository.

FTC (109 tests), FRC (134), FTC starter (14) and FRC starter (34) passed against that exact
candidate. Generated-project verification ran, and both FTC APKs built. Only the invoked
debug/simulator results are counted; historical release-variant XML is excluded. Logs are
`ARESLib-Kotlin/build/audit-pass39-{library,ftc,frc,ftc-starter,frc-starter}.log`.
The explicit report manifest and XML snapshots are under
`ARESLib-Kotlin/build/audit-pass39-verified-evidence`; recursive counts of all build
directories would double-count archived evidence and stale variants.

The full Studio gate passed in 3m 23s (`ARESLib-Kotlin/build/audit-pass39-studio.log`):
1,777 ordinary methods passed, six existing opt-in methods were skipped, and all 56
dashboard smoke methods plus the performance-baseline method passed. Shared, gateway and
app suites executed. Coverage verification, release alignment and production Kotlin
file-size checks passed. The dashboard fixture persisted/restored all 12,000 frames;
replay load was 21.4652 ms, scrub p95 22.9049 ms and rapid-seek burst 5.3314 ms. These are
desktop fixture measurements, not isolated filter or physical robot performance.

Repository policy passed (`ARESLib-Kotlin/build/audit-pass39-policy.log`), including source
identity, unchanged starter archive hashes, shared guidance and links in 200 current
documents. Inventory: 2,486 tracked files, 193 fully reviewed, 62 partially reviewed and
2,231 pending, with no stale or orphaned records. File review is independent from suite
execution and line/branch coverage.

## Limits and next work

Measured JVM allocation and asymptotic work reduction are separate from physical robot
loop duration, jitter, device heap behavior and tracking response. No on-device timings,
hardware motion or visible Studio session were exercised. Ordinary filter accuracy,
warmup/reset behavior, extreme arithmetic and current consumer suites are tested; this
is not exhaustive numerical or hardware verification.

Next scopes include joystick conditioning and adapter allocations, calibrated interpolation,
remaining kinematics/estimation and the existing Studio lifecycle, policy and retention queue.
Previously documented intermittent performance/process/simulator failures and opt-in tests
remain open. All changes stay local and the repository-wide goal remains active.
