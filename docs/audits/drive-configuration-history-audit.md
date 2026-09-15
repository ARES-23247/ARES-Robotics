# Drive configuration and control-history audit â€” pass 84

This pass continues the partial Mecanum feedforward/configuration scopes from passes 82â€“83.
It reviews coupled wheel-output validation, configuration ownership, derivative/PID/slew history,
voltage compensation, array reuse and the facade's stop/recovery boundaries. Work remains local.

## Confirmed defects and fixes

- Nonfinite feedforward/PID coefficients, invalid slew limits and invalid timing could leave
  feedforward energized or silently disable part of the controller. Invalid required configuration
  now neutralizes the entire calculated wheel vector and resets dynamic state. Invalid timing is
  no longer replaced with a fictitious 20 ms interval.
- Public PID properties could disagree with the controllers actually used; bulk updates left
  those public values stale, and integral-only configuration was ignored. Property and bulk
  updates now configure the same four controllers coherently, with reset on changed gains and
  no reset for unchanged configuration. Legacy initial-value setters forward to active settings.
- Rejected input and explicit stop could retain ramp/acceleration history. The controller now
  shares one allocation-free reset path, used by invalid calculations and facade safe/close/raw
  mode transitions/recovery/zero-power operation. Startup feedback remains temporarily unavailable
  until observed; it does not create a permanent output fault merely for lacking its first pair.
- Individual invalid motor scales could silently distort one wheel while others continued. Both
  per-wheel and master scales now require finite values in [0,1]; invalid scales neutralize and
  latch the cluster. Valid derating still applies exactly once at the physical output boundary.
- Invalid chassis values or finite kinematic overflow could be hidden by normalization. The
  facade rejects both before normalization can convert them to plausible zeros.
- Input/output aliasing destroyed target values, and short output buffers retained old effort.
  Four targets are captured before clearing output; short buffers clear their available slots,
  and trailing slots beyond the four-wheel contract remain untouched.
- Finite saturated effort could overflow during voltage compensation and become zero. Clamping
  in nominal-effort units before multiplying avoids that intermediate overflow. Unrepresentable
  raw feedforward arithmetic still rejects the entire coupled output.
- A numerically rejected PID calculation returned zero while feedforward continued. The existing
  PID validity flag is now public with a private setter, so the hardware module can reject the
  combined output. This intentionally adds one public getter; PID arithmetic itself is unchanged.

Encoder velocity converts to SI once per wheel instead of being divided during both validation
and feedback. Unchanged slew settings preserve ramp state; valid rate changes reuse limiter
objects. Zero acceleration gain avoids unnecessary and potentially overflowing differentiation.

## Evidence and limits

Nineteen distinct regression methods failed before the corresponding fixes: the initial 18-method
suite and a later finite-PID-arithmetic composition regression. The chassis validation method was
also extended to reproduce finite kinematic overflow. Preserved logs/XML are under
`ARESLib-Kotlin/build/audit-pass84-*-before.*`.

Additional checks cover valid unit conversion, derating, configuration aliases, startup feedback
recovery, rate-change continuity, short/aliased/extended buffers, and tiny positive timesteps.
The allocation test exercises feedback plus slew after warmup and measures per-thread allocation
over 10,000 cycles. It is a host diagnostic, not physical robot deadline evidence.

Native FTC hub PID gain application/retry and constructor initialization rollback remain explicit
follow-ups in the motor cluster/facade. This pass validates software-controller configuration and
output boundaries; it does not claim that changing software gains updates hub-resident PID gains.
The earlier FTC flywheel cached-read and diagnostic-clearing scopes remain pending as well.

## Validation

Before freezing, all 275 FTC hardware tests, four core composition-status tests and both affected
API checks passed. The selected affected classes account for 104 methods (100 FTC plus four core).
The new drive-configuration class has 26 methods. Selected XML and full module logs are copied under
`ARESLib-Kotlin/build/audit-pass84-focused-evidence/`.

The final allocation run measured zero bytes over 10,000 cycles after warmup, exercising software
feedback and slew. The public PID getter `getLastCalculationValid()Z` is the only core API addition;
FTC public API remains unchanged. The generated API diff was reviewed before source freeze.

Source `a01209fbf849a78c4c48c5e43e7ca162ffce1b21`; library tree `4f31ca679d8897fab234951756efbaae70662337`.
Candidate `17.0.3-rc.4f31ca679d88` was published only to the isolated local repository.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 1944 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project checks,
FTC assembly, Studio Kover/version/file-size checks and monorepo policy passed. Gradle reused valid
unchanged outputs. The six Studio skips remain the three conditional fresh-template checks, native
file chooser, physical dashboard and optional performance baseline. No new standalone dashboard
benchmark or usable-window check is claimed.

Copied XML, manifests, log hashes and candidate BOM identity are under
`ARESLib-Kotlin/build/audit-pass84-verified-evidence/summary.json`. The whole-monorepo goal remains
active; these suite results do not account for unread source files.
