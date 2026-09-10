# XRP mecanum output and failure handling - pass 44

This pass fully reviews XrpMecanumHardwareIO and its standard implementation, plus three new
test files. It also corrects two shared differential implementation details discovered while
testing the mecanum boundary. XrpMotorDouble and the XRP simulation engine retain their pending
whole-file review status. No physical hardware or MicroPython controller claim follows from
these JVM IO tests.

## Findings and changes

- Independent per-motor clipping changed the requested mecanum motion during saturation.
  For example, forward 1.2 m/s and left 0.4 m/s with a 0.8 m/s maximum should produce powers
  [0.5, 1, 1, 0.5]; the old implementation produced approximately [1, 1, 1, 1]. Drive now
  computes one divisor from the requested maximum and all absolute wheel speeds, then divides
  each wheel by it. The standard implementation reuses four-element scratch and captures each
  chassis field once. It no longer constructs wheel-value objects on the periodic path.
- Nonfinite power input left other motors active. Any invalid component now neutralizes the
  whole vector. Invalid chassis fields, invalid maximum speed and nonfinite wheel calculations
  also command all four powers neutral. Raw finite setPowers retains independent explicit
  clipping to [-1, 1]; coupled motion scaling belongs to drive.
- A failed write or refresh previously propagated while other motors remained active. Both
  operations now attempt every stop before rethrowing the original error. stop preserves the
  first failure, suppresses later distinct failures and still visits all four motors. Reused
  exception objects cannot self-suppress. Successful refresh calls each motor once; drive,
  setPowers and stop do not perform hidden feedback refresh.
- Wheel radius was not validated. The standard constructor now rejects nonfinite/nonpositive
  arguments. Extended tests caught an initial implementation mistake: validation called an
  overridable getter before subclass initialization. Both standard XRP drive constructors now
  validate their argument during property initialization. Subclasses own any overridden
  configuration; constructor validation is not a hardware-identity or enable check.
- The initial two-stage wheel-speed normalization could round a valid power ratio to zero
  with a subnormal maximum linear speed. This also affected the differential implementation
  added in pass 43. Two new regressions failed before refinement. Direct division by the larger
  of maximum speed and maximum wheel magnitude preserves the representable power ratio and
  removes redundant scaling arithmetic from both drive paths.

## Evidence

All six original regression methods failed against the old mecanum implementation in 6s:
saturation, invalid power, failed writes, incomplete refresh, stop aggregation and radius.
Baseline XML is preserved in `ARESLib-Kotlin/build/audit-pass44-before-evidence`, with the
command log at `audit-pass44-before.log`. The extended constructor test failed in 5s before
its fix (`audit-pass44-extended.log`, `audit-pass44-constructor-before.xml`). Both subnormal
power tests failed in 5s before direct normalization (`audit-pass44-power-range-before.log/.xml`).
Assertions were not weakened to obtain passing results.

The final focused gate passed all 28 XRP methods with no failures, errors or skips, plus API
checks and Kover XML, in 5s (`audit-pass44-final-focus-complete.log`). The 16 new methods include
failure injection at each write/refresh position, combined cleanup failures, repeated exception
identity, invalid/overflowing vectors, default-interface behavior and subclass initialization.
The public API remains unchanged.

The independent numerical oracle uses seed 4401 and 2,000 mixed translation/rotation commands,
with separate exact-binary-input BigDecimal wheel equations and 80-digit power division. Half
the maximum-speed bounds are ordinary and half span binary exponents -1074 through 1023.
Each output is finite, bounded and within 2e-14 absolute power of its reference. Dimensions
and chassis requests stay in the documented finite sampled ranges; hand-picked subnormal and
overflow tests complement sampling. This does not prove all floating-point configurations.

The allocation test executed after 50,000 warmup ticks, measuring zero bytes in two consecutive
10,000-tick windows of standard drive, in-memory motor refresh and stop. Its reused chassis
input changes each tick and an output checksum prevents unobserved dead work. This is bounded
host allocation evidence, not a physical loop-time, entire-JVM or custom-adapter guarantee.
Custom implementations using the interface default still allocate drive scratch.

Source commits 014c329f and 18293301 contain this pass. The first local candidate
`17.0.3-rc.37d59acddd46` passed its library gate but was superseded after the power refinement.
Final candidate `17.0.3-rc.333960e4e8fc` binds exact library tree
`333960e4e8fc89db4f88098cd0a6c2898b14987b`. Stable planned branch versions remain ARES 17.0.3
and Studio 7.0.4. The final full-library gate passed 1,248 methods with no failures, errors or
skips, plus API checks and isolated candidate publication in 12s. Gradle retained up-to-date
results for unaffected test tasks. FTC/FRC and their starters passed 109/134/14/34 methods,
generated-project checks and FTC debug assembly with the same final candidate. Studio's gate
passed in 22s: its unchanged ordinary test tasks remained up-to-date (1,779 passed methods,
six existing opt-in skips); 56 dashboard methods and one performance-baseline method reran and
passed, along with Kover, version alignment and file-size checks. Dashboard smoke persisted and
restored all 12,000 frames without drops, with replay load 19.3346 ms, scrub p95 19.4685 ms and
rapid-seek burst 4.7681 ms. This is headless host evidence, not visible-window or robot timing.

Repository policy passed, including source identity, archive integrity and links in 205 current
documents (38 historical records excluded). Logs are `audit-pass44-library-final.log`, the four
`audit-pass44-{ftc,frc,ftc-starter,frc-starter}.log` files, `audit-pass44-studio.log` and
`audit-pass44-policy.log` under ARESLib-Kotlin/build. The final staged inventory contains 2,517
tracked files: 244 reviewed, 67 partial and 2,206 pending, with no stale or orphaned records.
The full-file goal remains active.

Kover reports 49/52 lines and 55/56 branches for the complete mecanum IO file. The differential
file retains 37/46 lines and 39/44 branches; default-interface, distance-getter and exceptional
cleanup paths remain incompletely exercised there. Whole-file review is separate from line/
branch execution. XML and exact invoked test-file lists are preserved under
`ARESLib-Kotlin/build/audit-pass44-verified-evidence/summary.json` and `xrp-kover.xml`.

## Limits and remaining work

The raw IO layer does not arm a robot, validate feedback age or latch controller faults.
Stop failures mean neutral was attempted, not achieved; regression tests deliberately retain
that distinction when a fake motor rejects its stop. The real controller/adapter remains
responsible for ownership, configuration, freshness and recovery. Hardware response and
timing are unmeasured. The full swerve solver, XRP motor-double contract, simulation engine,
remaining estimator/control/Studio/tooling scopes and pending file inventory remain in the goal.
Prior intermittent and opt-in validation limits remain open. No push, merge, remote publication
or device action occurred.
