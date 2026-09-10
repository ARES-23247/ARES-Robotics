# Holonomic control composition and numerical audit

Pass 58, 2026-09-10. Local branch `codex/robot-loop-math-audit`.
Source `5b7f8468`, candidate `17.0.3-rc.d620adab542d`.

## Findings and fixes

The object overload wrapped raw NaN/infinite headings into zero before validation. It now forwards
raw headings into the shared validation boundary. Valid primitive and object inputs use the same
represented-angle normalization, including large finite angles. The explicit target heading remains
authoritative; the target pose supplies position only.

A PID or ADRC returning neutral after a fault was indistinguishable from valid zero feedback.
Feedforward could therefore continue moving while an active controller rejected its configuration
or arithmetic. Internal per-calculation status now accompanies feedback without allocating a result
object or changing the public API. The drive controller captures each axis status immediately and
neutralizes all axes on failure. An unused PID overridden by valid ADRC does not inhibit motion.
Independent feedback instances remain required for independent axis history.

Invalid frames previously retained controller history. All rejected drive frames now reset PID and
ADRC history, preventing retained integral/derivative effort on recovery. Position differences and
combined feedback/feedforward must be representable; overflow neutralizes the whole command.
ADRC now rejects invalid limits/ranges, negative bandwidths, corrupt observer state and overflowing
cached/intermediate values. Observer state commits together only after both next values are finite;
rejection resets to the finite measurement, or zero. Existing numerical thresholds |b0| > 1e-9 and
continuous period > 1e-9 remain explicit. Negative plant gain and zero bandwidth remain supported.

Computing sqrt(acceleration / curvature) could underflow the ratio to zero despite a representable
speed limit. Taking the roots before division retains that limit. Signed curvature and reverse speed
keep their existing semantics. This cap applies to feedforward; it does not prove the combined
feedback command respects physical tire-force or centripetal acceleration limits.

Large finite vectors could overflow during frame rotation, discretization or norm calculation before
the final speed clamp. Translation is now scaled before the linear operations, and its scale restored
subject to the final norm bound. This preserves direction for representable large inputs while
including discretization in the speed limit. It adds scalar arithmetic but remains O(1), with reusable
storage and no controller-side loop allocations. No overall CPU speedup is claimed.

Telemetry runs after validation; backend exceptions clear reusable output/history and propagate the
original failure. Backend cost and any diagnostic overflow from extreme coordinate projections are
separate from the bounded actuator result. The calculator owns no clock, IO, enable/freshness latch,
angular velocity cap, or robot lifecycle. Those remain responsibilities of the surrounding pipeline.

## Evidence

The original source failed eight of ten initial regression methods. The baseline log and XML are
retained in `ARESLib-Kotlin/build/audit-pass58-before.log` and `audit-pass58-before-evidence`.
The expanded run caught a test assertion treating -0.0 from a valid negative plant gain as different
from neutral +0.0; that assertion now compares numerical zero with tolerance. This was a fixture
correction, not a production defect. A mistaken wrapper invocation from the monorepo root did not
run Gradle; the corrected command ran from the library root.

The final focused gate passed 100 tests, including 19 new methods and existing PID, ADRC,
holonomic follower/controller and zero-GC regressions. Tests cover raw-heading rejection, malformed
controller configuration, arithmetic failure, coupled neutralization, recovery, curvature underflow,
overflowing vector norms, override selection, telemetry failure, reverse travel and tangent fallback.
A seeded independent inverse-SE(2) oracle compares 3,000 ordinary commands, including rotation,
feedback, discretization and final clamp. These samples do not establish all floating-point inputs.

The existing loop probe measured 20,000 reused-output calls at 0 allocated bytes, versus 800,000
bytes for independently owned results. Local latency was p50 400 ns, p95 799 ns, p99 1,000 ns.
The allocation assertion remains at most 4 KiB over the measured batch. It is a warmed JVM sample,
not a deadline or physical robot guarantee. This probe uses PID axes with telemetry disabled;
ADRC/telemetry backend timing was not separately measured. Focused Kover: holonomic controller 111/117 executable
lines, 84/136 branches; PID 95/95 lines, 131/140 branches; ADRC 72/73 lines, 93/122 branches.
Uncovered branches remain visible; full source review is distinct from complete branch execution.

Focused evidence is in `audit-pass58-final-focus.log` and `audit-pass58-focused-evidence`.
The full library gate passed 1,506 tests with no failures/errors/skips, API compatibility checks,
core Kover reporting and isolated publication in 1m47s. The same loop probe again measured 0 reused
bytes versus 800,000 owned bytes; p50/p95/p99 were 200/300/301 ns in this run, illustrating local
runtime variation. Full PID branch coverage was 133/140; controller and ADRC counts were unchanged.
Source policy passed (219 current Markdown documents, 38 explicit historical exclusions), including
agent guidance, source identity and archive checks.

Candidate consumers passed in dependency order: FTC 109 tests, FRC 134, FTC starter 14 and FRC
starter 34, with generated-project verification and both FTC application assemblies. Studio passed
in 3m22s: all shared/gateway/app test tasks reran, with 1,779 passing tests and six opt-in skips.
Dashboard smoke (56) and performance (1) reran and passed, together with coverage verification,
release alignment and production-file-size checks. The six skips remain limitations, not passes.
Logs, XML files, core Kover and SHA-256 manifests are retained under
`ARESLib-Kotlin/build/audit-pass58-verified-evidence`, alongside the per-product pass58 logs.

## Review boundaries and remaining work

Full file review closes `HolonomicDriveController.kt`, its original five-test suite, the existing
four-test `RobotLoopMathTest.kt`, and all three new test files. PID's previous full audit is preserved
with the added status paths reviewed here. ADRC's composition/validation/state-commit boundaries
are reviewed, but its observer discretization, saturation law and continuous-coordinate extremes
remain a separate full audit; it receives partial credit only. Its existing seven-test suite was read
in full and executed. Public signatures remain unchanged; the full API compatibility gate passed.

The follower still handles marker progress separately from feedback validity. Drive facade pose
snapshots and dispatched output actions can allocate. Sequencer progress/timing, ADRC dynamics,
and actual hardware loop timing remain open. No hosted CI, live Studio window, electrical or
physical hardware validation was performed in this pass.
