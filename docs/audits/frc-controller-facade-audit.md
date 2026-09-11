# FRC controller facade audit â€” pass 112

## Group scope

Reviewed six related source files: `MarvinControllerBase`, `MarvinCowlController`,
`MarvinFlywheelController`, `MarvinFeederController`, `MarvinConfig`, and
`CanonicalDrivebaseConfig`. Also read all methods in the existing
`MarvinControllerReduxConsistencyTest` (one), `MarvinControlAndFreshnessRegressionTest`
(five), and `CanonicalDrivebaseConfigTest` (four).

The review covers controller units, state-only dispatch, readiness boundaries, feeder/floor
interlocks and transfer lifetime, canonical/runtime tuning mapping, immutable-state preservation,
profiled module offsets, simulation dimensions/gains, and shot-table structure/units/limits.
Configuration values are reviewed as checked-in declarations; their physical calibration and
the accuracy of comments describing surveyed or official geometry are not independently certified.

## Findings and changes

- Cowl commands accepted NaN and mapped infinities to travel endpoints. Nonfinite values now
  throw before publishing a target; finite commands retain their 0..1.80 rotation clamp.
- Flywheel spin-up accepted nonfinite or negative RPM and could set the active latch. Invalid
  RPM now throws before changing the target or arming. Normal spin-up, stop and inhibit behavior
  remain covered. Rejection preserves previous state; outer controller fault handling remains
  responsible for neutralization when a controller throws.
- Feeder rollback detection relied on the sign of a subtraction. Rewinding from Long.MAX_VALUE
  to Long.MIN_VALUE produced elapsed=1 and left transfer active. Direct timestamp ordering now
  detects rollback before the wrapped elapsed value can extend a transfer.
- Generic dispatch-on-change comparisons boxed double values in unchanged loops. A primitive
  overload in the FRC base removes boxing at all seven double-comparison sites across the three
  facades, while Boolean dispatch continues through the existing base implementation. Javap shows
  14 `Double.valueOf` calls before and zero after. Signed zero compares equal; finite command
  behavior and actual-change dispatch are preserved. NaN current state is repaired by a finite target.

Nine new tests ran before the changes; four failed (the three correctness cases and an allocation
check). Before XML is retained under `ARESLib-Kotlin/build/audit-pass112-before-evidence/`.
An initial test compilation error from accessing internal shot-table arrays was corrected by
using the public interpolation API before collecting that behavioral evidence.

## Evidence and limits

New tests exercise atomic rejection, rollback overflow, dispatch suppression and allocation,
readiness/validity tolerances, all eight start-interlock combinations, floor assist, transfer
timeout, clamp/stop/inhibit behavior, runtime parameter mapping, and shot outputs over 0..10 m.
The configured shot object also validates its table shapes, ordering and finite values at
construction. Public interpolation checks verify monotone finite outputs and cowl travel limits;
they do not establish real projectile trajectories or replace calibration.

The baseline no-change test observed 77,424 allocated bytes over 100,000 ticks; the fixed test
observed zero. This is a warmed host observation and bytecode improvement, not a constant baseline
per-tick allocation claim: JIT escape analysis can eliminate some generic boxing. No redundant
Redux notifications occurred after initialization. Tests restore the mock clock and unsubscribe
listeners. The broader hardware/IO/reducer implementation remains separately scoped.

Only four FRC implementation files change. Shared library candidate `17.0.3-rc.100852e472fb`
remains unchanged. No physical hardware, loop deadline or simulator-window result is claimed.

## Final validation

Full FRC validation passed 212 tests, including nine new facade methods and ten existing methods reviewed in this group, with zero failures, errors or skips. Five core zero-GC methods also passed. Generated-project/namespace verification and monorepo policy passed. Gradle reused valid unchanged outputs.

Before: Unchanged cowl/flywheel/feeder facades: 77424 bytes / 100000 ticks.

After: Unchanged cowl/flywheel/feeder facades: 0 bytes / 100000 ticks.

Copied JUnit XML, logs, source and bytecode hashes are recorded in `ARESLib-Kotlin/build/audit-pass112-verified-evidence/summary.json`. The before run has nine tests and four failures.
