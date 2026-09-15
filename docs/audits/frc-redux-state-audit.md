# FRC Redux state audit - pass 117

## Scope

Fully reviewed `MarvinAction.kt`, `MarvinState.kt`, `MarvinReducer.kt`, and all eight existing
methods in `MarvinReducerTest.kt`. Added eleven methods in `MarvinStateAuditTest.kt`.
Updated the previously reviewed configuration with one shared inventory-capacity constant.
Reviewed and bounded simulator inventory metadata reconciliation; `Dyn4jSimulation.kt` remains
partial beyond that scope. The sensor/timer entry point in `MarvinSuperstructure.kt` was traced
but its output/lifecycle implementation is not newly claimed as fully reviewed.

## Findings and changes

- Late phase-one slamtake events could restart rollers after cancellation or completion.
  Timer actions now require an active sequence, a timestamp not preceding its start, a known
  phase and the matching current phase for retraction. Duplicate phase-one, unknown-phase,
  inactive and prior-cycle events leave nested state unchanged. Phase two still permits
  finishing directly from phase one, preserving existing delayed-loop completion behavior.
- Inventory assignments accepted negative/unbounded values; detector arithmetic could overflow
  an integer. Accounting now clamps to the existing simulator hopper limit of 40 before edge
  arithmetic. Configuration centralizes that existing value; the intake gate and metadata deque
  use it too. Direct corrupt counts can no longer cause unbounded metadata allocation. This is
  consistency with the checked-in simulator limit, not independent physical capacity certification.
- Invalid flywheel frames compared raw RPM against the sanitized cached zero, repeatedly copying
  unchanged invalid observations. Deadbands now compare sanitized values. Numeric deadbands also
  explicitly replace nonfinite cached fields, allowing a valid observation to repair them even
  when validity flags are unchanged.
- Every sensor frame copied the outer state tree, including unchanged observations; frames with
  many changed sensors also created several intermediate Marvin states. The reducer now updates
  changed slices locally and creates at most one outer Marvin state per sensor frame. Floor
  velocity/current changes share one slice copy. Unchanged nested state and already-safe inhibited
  drive state retain identity. Core root reduction still creates its timestamped root state;
  this pass does not claim an allocation-free whole reducer.
- Generic/default superstructure reads created a new complete Marvin fallback repeatedly. The
  fallback is now shared safely: all fields and nested slices are immutable, and helper methods
  return independent copies.
- A nonfinite climber position command became a valid minimum-position command in position mode.
  It now selects zero-voltage mode, preserving the inactive position target. Finite positions
  retain their existing mechanism-travel clamps. This avoids both unintended endpoint motion
  and throwing from the reducer, which would invalidate the Store.
- Removed a duplicate action documentation comment. Action units, deterministic RobotClock
  timestamp defaults, sensor validity defaults and public state field shapes remain unchanged.

## Regression evidence

Eight initial methods ran against original source; six failed. They cover stale timers,
inventory boundaries, unchanged frames, repeated invalid RPM, nonfinite-cache recovery and
fallback identity. Deadband/validity behavior and inhibited command suppression passed before
and after. Before XML/log are preserved in `ARESLib-Kotlin/build/audit-pass117-before-evidence/`.

Two supplemental methods verify repeated invalid detector frames preserve the last trusted
edge without recounting a held piece, and direct simulator metadata reconciliation handles
Int.MIN_VALUE/Int.MAX_VALUE while storing only 0..40 records. The huge simulator count was tested
only after bounding, avoiding a deliberately unbounded allocation on the original implementation.
The original invalid-detector behavior still clears the live detected flag while retaining its
trusted prior edge; this was explicitly preserved during the sensor-block refactor.

A separate one-method regression reproduced the invalid-climber-position behavior before that
fix (`climber-before.xml`, one failure). Thus seven failing regression methods were reproduced
across the two before runs; the final class contains eleven methods.

The existing eight reducer methods also verify ordinary setters and units, no unintended
cross-mechanism geometry coupling, phase transitions, invalid detector behavior, held-piece
recovery and measured/per-motor flywheel readiness. New tests verify root timestamps continue
to advance even when nested identities are reused, and safety gates continue allowing sensor
observations while suppressing mechanism commands.

## Validation and limitations

Full FRC validation passed 248 tests with zero failures, errors or skips, including eleven new
methods and the eight existing reducer methods. Five core zero-GC methods passed. Generated
project/namespace checks and monorepo policy passed. Gradle reused valid unchanged outputs.
All four final validation processes reached terminal exit zero. Copied XML/logs, before-run
hashes and source hashes are retained in `ARESLib-Kotlin/build/audit-pass117-verified-evidence/summary.json`.

Copy reductions are supported by source structure and object-identity regressions; no wall-clock
speedup or full-loop zero-allocation claim is made. Climber tests verify the selected neutral
command mode and value, not physical motor response. Remaining simulator event accounting,
subsystem timer clock anomalies and complete IO/lifecycle behavior remain outside the partial
files' verified scope.

The library is unchanged at candidate `17.0.3-rc.100852e472fb`, source tree
`100852e472fbeeba64fdf799665f51b4687f7f1b`. No hardware run, push, merge or release occurred.
