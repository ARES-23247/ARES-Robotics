# Mechanism integration audit â€” pass 109

## Scope and reproduced findings

Reviewed shared `FlywheelSim` and `IntakePivotSim`, their torque equations, integration,
input and reset boundaries, and their FRC caller/feedback-test compatibility. Five new test
methods failed against the original implementations; the copied JUnit XML is under
`ARESLib-Kotlin/build/audit-pass109-before-evidence/`. An earlier test-import compilation
error was corrected before collecting this failure evidence.

The pivot's default damping rate is approximately 256.33 /s. A 20 ms Euler step has
`rate * dt = 5.13`, beyond the linear damping stability limit of 2. At constant 6 V,
the original pivot numerically reversed into the lower stop instead of advancing.
An independent 10 microsecond RK4 torque integration supplies the trajectory oracle.

The flywheel's one-second Euler update overshot its analytical damped motor response.
Nonfinite voltage could permanently contaminate model state, negative time was accepted,
and invalid physical parameters were accepted at construction.

## Changes and cost

The flywheel now solves its constant-voltage linear ODE exactly using `expm1` for small
time steps, retaining its unilateral zero-speed stop. Work remains constant per update.
The pivot integrates linear motor damping exactly and evaluates nonlinear gravity at a
predicted midpoint, using substeps no larger than 5 ms. Four substeps cover a 20 ms loop.
Hard stops dissipate impact velocity. A 50 second / 10,000 substep limit bounds work on
unexpectedly large input durations; zero time is an exact no-op.

Both models cache immutable motor/damping coefficients instead of re-deriving current,
torque and acceleration coefficients on every update. The pivot reuses full/half-step
response factors; no per-substep collections or temporary objects are constructed.
Small-damping position factors use a series to avoid subtractive cancellation.
All parameter/input/result checks precede state publication; the pivot uses local scalar
state throughout the update. Reset requires a finite physical angle from 0 to 120 degrees.
Unrepresentable intermediate arithmetic is explicitly rejected rather than published.

FRC adapter defense tests now inject corrupted private feedback with test-only reflection,
because the model update API rejects NaN at entry. Their zero-effort/invalid-feedback
assertions remain unchanged. Production code does not use reflection.

## Validation scope and limits

Twelve new methods cover analytical flywheel response, time partitioning and braking,
default pivot stability and independent RK4 agreement, gravity-free position/velocity,
small-damping limits, hitches, stops, reset, parameter rejection, atomic invalid inputs,
bounded work and warmed-loop allocation. The required core zero-GC regression also runs.
The allocation test measures 100,000 pairs of 20 ms updates after warmup, allowing only
4 KiB total measurement/JIT overhead. Its timing output is a host diagnostic, not a robot
loop deadline guarantee or a before/after speedup claim.

Public API signatures are preserved; invalid-input behavior is deliberately stricter.
Default physical constants remain approximations. Numerical reference tests do not
calibrate hardware, validate arbitrary extreme parameter sets, certify exact impact times,
or exercise a usable simulator window. Gravity integration is approximate and does more
work than the unstable single Euler step. Physical HIL is not performed.

Shared-library changes require a frozen source identity and a new isolated candidate,
then full library/API checks and dependency-ordered FTC, FRC, starter and Studio validation.

## Final validation

Source `22d3875347296e5d3ef53bedbfdeb91bcfd4b52b`; library tree `100852e472fbeeba64fdf799665f51b4687f7f1b`. Local candidate `17.0.3-rc.100852e472fb`.

Twelve new model tests and five core zero-GC regressions passed. Five failure-before cases are preserved. API checks passed. Final full-library allocation diagnostic: Mechanism pair: 272 bytes / 100000 updates, 306.063 ns/update on this host.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 2198 | 0 |
| ftc | 111 | 0 |
| frc | 187 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs; counts do not imply every test was freshly executed. Conditional Studio skips remain recorded in XML.

Copied XML, verified hashes, logs and candidate BOM identity are recorded in `ARESLib-Kotlin/build/audit-pass109-verified-evidence/summary.json`. No physical loop-time or simulator-window result is claimed.
