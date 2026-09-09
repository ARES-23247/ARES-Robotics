# Generated Kotlin subsystem controller audit - pass 10

This pass reviews ordinary controller timing, feedback freshness, and basic PID/bang-bang
math in `SubsystemControllerRenderer.kt`. It adds compiled behavioral cases to
`SubsystemKotlinGeneratorTest.kt`: tests execute emitted Kotlin with immutable state and
fake IO, rather than merely inspecting source strings. The renderer and large existing
test file remain partially reviewed.

## Confirmed findings and fixes

- Negative integral gains used the error sign for anti-windup, allowing the integral to
  accumulate further into saturation. Conditional integration now uses the sign of the
  integral contribution. Multiplying signs avoids overflow in the sign calculation.
- A zero integral gain still accumulated error, eventually retaining enormous or invalid
  history. Zero integral and derivative gains now bypass their unused calculations.
- Bang-bang control could command a positive minimum for a negative error, or a negative
  maximum for a positive error. A direction unavailable in a one-sided interval now
  commands neutral. Overflowing error subtraction also commands neutral.
- Repeated/backward timestamps were clamped into positive elapsed time. PID controllers
  now neutralize and clear history on nonpositive or overflowing elapsed time. They keep
  a timestamp anchor so repeated rejected timestamps cannot re-enable the nominal first
  step. Timestamp zero is valid; the first accepted step remains the nominal
  20 ms. Subsequent valid periods use actual elapsed time without a hidden 100 ms cap.
  Stateless direct/servo/bang-bang control does not manufacture an unused time step.
- Ordinary updates did not enforce feedback age. A shared freshness check rejects stale,
  future, and overflowing ages before ordinary, homing, or automatic-recovery output.
  Explicit neutral-recovery/calibration handshakes retain their existing request order
  and use the same freshness predicate. Homing also observes the interlock gate.
- Brownout/current-budget scales above one could amplify output. Output-producing paths
  now require a finite scale in (0, 1]; rejected scales neutralize and reset history.
- PID arithmetic overflow could be hidden by output saturation or poison later updates.
  Non-finite error, integral, derivative, or combined output now resets and neutralizes.

## Validation

The original 11 compiled scenarios all failed against the pre-fix renderer and passed
with the initial fixes. Five additional cases separately cover unused integral state, bang-bang error overflow,
derivative overflow/recovery, feedback-age overflow, and elapsed-time overflow. All
**75 generator tests pass**, including one compiled test executing **16 boundary scenarios**.
Only the two expected deterministic artifact hashes changed after reviewing the emitted
controller diff; the other generator tests passed before the hashes were updated.
The full library test/API/local-publication gate passed for candidate
`17.0.3-rc.d4c39e42a386`, source tree `d4c39e42a3868566936e226e9853560538ebff40`.
The library reports contain **1,090 passing tests**: the 75 generator tests executed
in the focused run; unchanged library suites reused up-to-date results in the full gate.
FTC (109), FRC (134), FTC starter (14), FRC starter (34), and Studio (1,244 passing,
six opt-in skips) passed against the same isolated candidate. FTC TeamCode/simulator,
FTC starter TeamCode, and Studio app tests executed; FRC, FRC starter, FTC starter
simulator, and Studio shared/gateway tests reused their up-to-date results.

Source identity, unchanged bundled starter archive hashes, and monorepo policy passed.
The changes are committed locally as `ef2b8708`; no remote publication or deployment
occurred. Physical testing and Studio opt-in cases remain open. Guidance and links in
171 current documents passed.
Logs are under `ARESLib-Kotlin/build/audit-pass10-*.log`.

## Remaining work

Generated profiled-position motion still needs a full acceleration/arrival/reversal
review. The core trapezoid profile needs an explicit overspeed-initial-state regression
before it can be reused safely. Feedforward, homing/recovery state machines, continuous
wrapping extremes, invalid bounded targets, and multi-loop prepare-before-write behavior
need further compiled behavioral coverage. The corresponding MicroPython advanced
profile/feedforward contracts remain open from pass 9.

No on-robot timing, allocation benchmark for the emitted controllers, motor response,
or electrical validation was performed. Core zero-GC tests are separate library evidence;
they do not establish the allocation behavior of every generated controller. No release,
push, merge, or hardware deployment is authorized by this audit goal.
