# FTC calibration plant audit - pass 177

Read CalibrationTestOpMode and the headless CalibrationVerificationApp. Two executed
plant regressions failed before correction: nonfinite applied voltage contaminated the
cached velocity, and setVelocityRpm ignored maxEffortScale entirely.

The private first-order plant now rejects nonfinite voltage/RPM/effort to zero voltage,
bounds finite effort to [0,1], and caps velocity-mode voltage to 12 times that effort.
Raw voltage remains limited to +/-12 V. The model still uses 420 RPM/V and an 0.08
response per refresh: one saturated-voltage step is 403.2 RPM, one quarter-effort step
from rest is 100.8 RPM, and safe coasts at 0.92 of the previous sample. This is a simple
voltage-driven smoke-test plant, not a calibrated current-limited motor/controller model.
Its fixed-step response remains dependent on refresh frequency.

Setup, waitForStart and calibration enablement previously preceded the try/finally
cleanup boundary. They are now inside it after successful robot construction. Stop or
interruption before Start returns through cleanup without enabling calibration. This
control-flow correction was inspected statically; interruption/enablement failure
cleanup has not yet been exercised against a constructed simulator robot, so the source
remains partial rather than receiving a blanket lifecycle certification.

Three focused tests instantiate only the private plant through reflection. They verify
saturation/coasting, finite recovery after NaN/infinity commands, quarter/zero/negative/
nonfinite effort and invalid RPM handling. No visibility change or test dependency was
added to production APIs. The calibration application itself remains pending runtime
validation, especially startup cleanup and bounded supervision under clock changes.

The unchanged library candidate is `17.0.3-rc.100852e472fb`. Evidence is retained under
`ARESLib-Kotlin/build/audit-pass177-verified-evidence/`. No simulator launcher, socket
server, physical robot, release or push was invoked. Android source was unchanged.

Validation: three focused tests and all 9 simulator tests pass without skips; simulator classes compile.
Monorepo policy, documentation links and staged whitespace checks pass. Unchanged Android,
library and other-product suites were not rerun.
