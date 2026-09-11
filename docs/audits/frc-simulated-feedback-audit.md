# FRC simulated mechanism feedback audit

Pass 108 reviews all six season simulator IO adapters and three existing adapter test files.

## Reproduced findings

The cowl, climber, intake pivot and flywheel controllers used model feedback directly in their error
calculations. Non-finite feedback produced non-finite voltage, or saturated effort for infinite position,
even though the corresponding feedback-validity property reported unavailable data. Four new methods
reproduced these failures independently. A fifth reproduced flywheel velocity control accepting input
marked invalid, stale or frozen by the fault timeline.

Position controllers now read feedback once and write zero effort when that value is non-finite.
Flywheel velocity control similarly requires finite, trustworthy velocity, writes zero on rejection and
reports `lastWriteAccepted = false`. Direct voltage control retains its existing separate contract;
it does not require velocity feedback. Existing target clamping and geometry are unchanged.

## Efficiency and fixture ownership

The flywheel's model-matched feedforward coefficient `ke + frictionCoeff * resistance / kt` depends
only on immutable model parameters. It is now computed once at adapter construction. Each velocity
write also reuses one measured RPM value for its error calculation. No model-timing improvement or
physical allocation-rate claim is made.

All three existing test files constructed simulation objects without closing them. Their nine test
methods now use deterministic `use` scopes, including the test with two simulators. Assertions are
unchanged. The new tests also close every simulator and restore the mock clock after fault injection.

## Review and validation scope

All six adapters were read in full: position/effort limits, cowl degree-to-rotation conversion, intake
roller conversion, direct-voltage boundaries, current approximations, floor RPS, optional feeder
detection, flywheel model feedforward and fault-validity behavior. Feeder and floor required no source
change. Their finite/invalid voltage behavior, currents and detection validity are directly exercised.
The three existing files cover all-adapter behavior, safe output, effort scaling, readiness and timeline
faults; all were read and executed.

Seven new methods cover the five reproduced failures, feeder/floor semantics, and eight finite or
non-finite voltage cases across seven direct-output channels. The preserved expanded failure-before
XML contains six methods and five failures; the direct-voltage table was added afterward.

These are adapter-boundary reviews, not validation of the underlying integration models or their
physical accuracy. The shared `FlywheelSim` and `IntakePivotSim` integration equations were located
for tracing feedback but require a separate numerical stability review. No HIL, simulator GUI,
physical calibration or measured loop-time result is claimed.

Only season adapters/tests change; library candidate `17.0.3-rc.de2cb9c407a0` remains unchanged.
Validation uses full FRC tests, generated-project verification and repository policy.

## Final evidence

Full FRC validation passed 187 tests, including seven new feedback/boundary methods and nine existing adapter methods, with zero failures, errors or skips. Generated-project and namespace verification and monorepo policy passed. Gradle reused valid unchanged outputs.

Copied FRC JUnit XML and successful logs, with verified SHA-256 hashes, are recorded in `ARESLib-Kotlin/build/audit-pass108-verified-evidence/summary.json`. Initial failure XML is preserved in `ARESLib-Kotlin/build/audit-pass108-before-evidence/`.
