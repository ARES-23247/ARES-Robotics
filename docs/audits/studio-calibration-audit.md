# Studio geometric calibration audit - pass 14

This pass reviews `SysIdRegressionSolver.kt`, its existing regression tests, and the new
calibration boundary tests. The class fits Pinpoint spin geometry, mecanum track width,
stationary vision noise, and linear encoder scale. Feedforward regression and transient
classification live in `SysIdService`, despite the solver's former misleading class comment.

## Findings and changes

- Failed or unknown analyses could leave an earlier recommendation available in state.
  Each analysis now clears prior calibration outputs before validation and reports failure
  without publishing a replacement. A subsequent valid run clears the error normally.
- Pinpoint fitting solved a matrix without checking whether headings varied enough to
  distinguish a fixed field origin from a rotating offset. The centered fit requires mean
  squared heading-vector variation above `1e-6`; constant and near-constant headings fail.
  This identifiability threshold does not certify physical calibration quality.
- Pinpoint fitting now eliminates the fixed origin analytically. For centered position
  `(dx, dy)` and heading vector `(dc, ds)`, the fitted offset is the mean of
  `(dc*dx + ds*dy, dc*dy - ds*dx)` divided by mean `(dc² + ds²)`. This is the same
  least-squares model without allocating a `2N × 4` matrix and response vector.
- Track-width fitting unwraps heading while accumulating regression sums instead of
  allocating and traversing a separate heading-history array. It preserves the producer's
  wheel ordering and the relation `trackWidth = 2*k - recordedWheelbase`.
- Vision variance could overflow while squaring finite deviations, even when the resulting
  standard deviation was representable. Scaled means, scaled residuals and `hypot` now
  compute the sample deviation without that intermediate overflow. Circular heading
  averaging and the `N-1` divisor remain unchanged.
- Finite inputs could produce infinite offsets, encoder scales, or deviations. Outputs and
  relevant intermediates now validate before entering recommendation state. Calibration
  rows must have complete finite columns and strictly increasing nonnegative timestamps.

The Pinpoint producer was traced: it sets offsets to zero when starting the spin and sends
estimated pose. Physical pod geometry, zero-offset acknowledgement, estimator influence,
and fit quality remain separate end-to-end validation concerns. No sensor truth was
substituted and no robot command or tuning proposal was sent during this pass.

## Validation

The initial seven new methods produced **five failures** against the old implementation.
The original XML is retained at `ARESLib-Kotlin/build/audit-pass14-baseline.xml`.
The final audit suite has **14 passing methods**, alongside **four existing regression
methods**. Tests cover full and partial turns, both directions, field/encoder origins,
identifiability, chronology, stale-result cleanup, changing geometry, finite arithmetic,
extreme but representable deviations, unrepresentable results and recovery after failure.
The raw heading difference is checked before calling `wrapAngle`, whose non-finite
fallback would otherwise hide overflow as zero rotation.

The full Studio gate passed against unchanged local candidate `17.0.3-rc.b81c0156add9`:
**1,258 passing tests with six opt-in skips** across app/shared/gateway. App tests executed;
shared/gateway reused up-to-date results. Coverage tasks also executed **56 dashboard
smoke tests** and **one performance-baseline test**, all passing. These are headless
desktop tests, not evidence of a visible Studio window or physical hardware behavior.

Kover reports **139/140 executable lines (99.3%)** and **106/132 branches (80.3%)** for
`SysIdRegressionSolver.kt`. This does not establish every numeric combination or caller.
XML is `ARES-Analytics/app/build/reports/kover/report.xml`; test XML is in the corresponding
`app/build/test-results` task directories. The configured app coverage gate, production
Kotlin file-size check, release alignment and monorepo policy passed. Shared guidance and
links in 175 current documents passed after staging. Logs are
`ARESLib-Kotlin/build/audit-pass14-*.log`.

This pass changes only Studio source/tests and audit documentation. Library identity and
starter hashes remain unchanged; robot consumer suites were not rerun for these desktop
calibration changes. No wall-clock calibration benchmark or allocation measurement was
performed; removal of the matrix and heading array is established by source inspection.

## Remaining work

The next pass must address `SysIdDataCollector`: incomplete indexed samples can be
accepted when the final channel arrives, missing numeric fields can shift flattened
columns, and absent acceleration is conflated with explicitly supplied zero acceleration.
Its growing buffers and repeated immutable-list appends also need bounded collection work.
Those findings do not grant that file completion credit. Signal/lease lifecycle and
`SysIdService` regression/FFT require separate remaining coverage.

The full monorepo goal remains active. These are desktop mathematical tests, with no
physical calibration, loop-time, rendered-window or device deployment evidence. Changes
remain local without push, merge or remote release.
