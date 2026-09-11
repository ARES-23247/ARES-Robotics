# FRC shooter test isolation audit - pass 134

Reviewed every test and recording IO fixture in `MarvinShooterSubsystemTest` and
`MarvinMeasuredSotmRegressionTest`, including their moving-shot timing and readiness
assertions. No production calculation defect was identified in this pass.

Both invalid-motion tests advanced observation timestamps to 1020 ms while leaving
the mocked clock at 1000 ms. The future-observation guard could therefore explain
the stopped shooter independently of the intended invalid-motion guard. Tests now
advance the clock and explicitly assert a current observation timestamp and false
motion validity before invoking the controller.

The IO integration case previously exercised the moving-shot entry point with a
stationary chassis. It now supplies positive field X velocity, checks that the shot
is valid and the virtual target leads in the opposite direction, then verifies RPM
delivery and invalid-motion shutdown through recording IO. Existing measured-motion
tests already cover commanded-versus-measured velocity, acceleration history,
duplicate observations and stale/future timestamps; no duplicate suite was added.

Shot comparisons now require both reference and actual results to be valid, avoiding
agreement between cleared results. Static readiness fixtures also advance mocked
time with their sensor timestamps. Corrected a comment that described repeated
control calls as two observations.

These are test-evidence corrections, not robot runtime optimizations. Shared
ShotSetup comparisons verify the integration inputs and outputs, not an independent
ballistic derivation. Recording IO cannot establish four-motor hardware readiness,
physical loop latency or real projectile accuracy.

Validation: all 13 focused tests and the full 302-test FRC suite passed, with zero
failures/errors/skips; generated-project verification also passed. Policy and
documentation-link checks passed. XML, logs and reviewed source hashes are retained
under `ARESLib-Kotlin/build/audit-pass134-verified-evidence/`.
