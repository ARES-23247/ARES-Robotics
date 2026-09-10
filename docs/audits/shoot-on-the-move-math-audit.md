# Shoot-on-the-move math audit

Pass 80 in progress. Focused math, API and FRC caller validation passed; candidate matrix pending.

## Corrections

- Five damped iterations left a nonzero intercept residual even with constant flight time, and
  could remain far from a solution for steep tables. Each piecewise-linear flight-time segment
  and both clamped tails are now solved; the earliest nonnegative valid intercept is selected.
- Heading feedforward omitted the derivative of distance-dependent flight time and the changing
  tangential velocity of an offset rotating shooter. The implicit derivative now includes both.
- Invalid geometry could return a plausible endpoint RPM. `ShotResult.isValid` now distinguishes
  a usable solution; failed calculations clear every field. Both FRC aiming paths cancel flywheel
  and feeder intent before dispatching targets when the result is invalid.
- Negative flight times and distance breakpoints are rejected. Public interpolation validates its
  table, preserves NaN queries as undefined, handles narrow valid segments and avoids overflowing
  a representable midpoint. Configured runtime lookups bypass repeated table validation.
- RPM and cowl share one interval lookup. The intercept solver uses primitive locals with no
  iteration workspace allocation; lookup interval searches are binary.
- FRC acceleration previously assumed a zero initial velocity and differentiated on control-loop
  timestamps. It now starts with a measured baseline, advances only on new observations, retains
  the derived acceleration between observations, and resets after gaps, invalid input, static
  aiming or trigger release. Feedback older than 100 ms or dated in the future stops firing intent.

## Model and independent checks

For relative target position R, shooter field velocity v, distance d and a linear flight-time
segment tau(d)=a*d+b, the equation d=|R-v*tau(d)| reduces to a quadratic. The implementation
normalizes its coefficients, uses stable quadratic roots, handles the linear degeneration, checks
segment membership and geometric residual, then checks the chosen time against the original table.
Endpoint clamps and multiple roots have dedicated regression cases.

Independent tests bracket flight time instead of reproducing the production quadratic, compare
constant-flight-time geometry, and central-difference the aim angle over advancing/rotating poses.
A velocity-quadrant sweep, invalid-result reuse, interpolation extremes and mode-transition cases
provide additional coverage. The old approximate translating-shot expectation was replaced with an
independently bracketed solution for its actual calibration segment.

Fifteen regression methods failed before the corresponding fixes: nine core mathematical/input
methods, two invalid-result caller methods and four observation-timing/freshness methods. Logs/XML
are retained in `ARESLib-Kotlin/build/audit-pass80-*-before.*`. Focused validation passed 27 core
and 15 FRC tests without failures/errors/skips; the intentional three-method ShotResult API addition
was dumped and checked before freezing. Evidence is copied under
`ARESLib-Kotlin/build/audit-pass80-focused-evidence/`.

The allocation test measured zero bytes over 10,000 samples after warmup. Its host timing diagnostic
averaged 1,530 ns/sample; this is not a physical robot latency or worst-case deadline measurement.

## Limits and remaining validation

This is a constant chassis field-velocity/angular-rate model over the configured delay, with a
stationary target and an empirical flight-time table. It does not independently model drag or
chassis acceleration; the season caller retains its explicit 0.2 s acceleration projection.
At table knots feedforward uses a one-sided slope, and endpoint-clamped slopes are zero. Rates
inside 5 cm are suppressed; zero-distance, nonrepresentable and singular/ill-conditioned solutions
are invalid. Invalid shots stop flywheel/feeding and leave the existing cowl position target alone.
IO freshness and actuator safety remain enforced by downstream hardware contracts as well.

Finish frozen candidate/library/API/consumer validation and update the file ledger with exact
source identity and coverage. No hardware operation, push, merge or release is authorized by this
batch. The wider file audit remains active, including the previously recorded FTC empirical
calibration and FRC SysId boundary follow-ups.
