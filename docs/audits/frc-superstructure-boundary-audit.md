# FRC superstructure boundary audit - pass 122

## Scope

Reviewed the complete `MarvinSuperstructure.kt`: cached sensor observations and validity,
per-motor flywheel readiness, slamtake timers, inhibited output handling, effort scaling,
position/velocity units, intake/climber clearance arbitration and feed-forward calculations.
Seven new boundary tests supplement the existing superstructure safety and shooter suites.

## Findings and fixes

- A failed zero-voltage write in the inhibited path prevented later mechanisms from receiving
  their stop commands. All seven output channels are now attempted independently. The first
  error is rethrown with later errors suppressed. Inline failure accumulation avoids allocating
  a lifecycle collector on every inhibited loop. Tests inject failure at each output and verify
  every stop attempt, including two simultaneous failures.
- Timer subtraction could overflow or run backward, leaving slamtake rollers active. Integer
  millisecond comparisons now cancel on clock rewind or invalid active phase, and interpret
  signed elapsed overflow as a very long forward interval. Completion takes priority after the
  1500 ms deadline, avoiding a redundant retract phase after a delayed loop. Normal 500/1500 ms
  boundaries remain exact, with no floating-point seconds conversion.
- A retained climber position target blocked intake deployment even after the controller selected
  neutral voltage mode and measured the climber fully retracted. Only an active position-mode
  target now contributes that constraint. Measured climber position/validity and actual requested
  climber motion still block unsafe intake deployment.
- Nonfinite cowl, intake-pivot and climber position targets became valid minimum-endpoint commands.
  They now request zero effort instead. Finite travel clamps and brownout geometry preservation
  remain unchanged. An invalid active climber target retains conservative collision arbitration.
- Sensor processing now reuses one cached flywheel RPM observation for readiness and the Redux
  snapshot instead of reading the cached getter twice when velocity was valid.

## Evidence

Six initial boundary methods ran against original source; five failed. The normal timer-boundary
test passed before and after. The final seven methods also cover unknown active phase cancellation.
Fixtures call the actual subsystem and Store/reducer with controlled IO interfaces. They test command
attempts and values, not motor behavior or CAN delivery.

Full FRC validation passed 285 tests with zero failures, errors or skips. Five core allocation
regression methods passed. Generated-project/namespace verification, monorepo policy and current
documentation links passed. Gradle reused valid unchanged outputs. Before XML, final XML/logs and
source hashes are retained in `ARESLib-Kotlin/build/audit-pass122-verified-evidence/`.

Compiled `writeOutputs` has no normal-path object/array construction instructions. Its two remaining
construction instructions are compiler-generated unexpected-enum exceptions; IO implementations and
failure suppression may allocate independently. No whole-loop allocation or timing guarantee follows.

## Limits

Physical mechanism stops, calibration, collision clearances and brownout response were not exercised.
Stop delivery failures still propagate to the existing caller safety handling; attempting all outputs
cannot guarantee a disconnected or failed controller accepted zero. Vendor IO safety/lifecycle paths
remain separate partial audit scopes.

ARESLib is unchanged at candidate `17.0.3-rc.100852e472fb`, tree
`100852e472fbeeba64fdf799665f51b4687f7f1b`. No push, merge, release or HIL run occurred.
