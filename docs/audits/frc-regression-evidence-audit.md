# FRC regression evidence audit - pass 128

Reviewed all remaining pending FRC test files as one batch: six Kotlin test classes
and the autonomous catalog/routine fixtures. This closes their file reviews, not
the remaining production-code or hardware validation gaps.

## Corrections

`ARESRobotTest` changed simulated controls without publishing new Driver Station
data or refreshing the snapshots consumed by the hand controller. Its purported
button branch coverage was unsupported. Each smoke frame now publishes the sample,
calls `robotPeriodic` to refresh snapshots, runs teleop and asserts that no mechanism
fault was silently latched. HAL initialization uses a JUnit assertion; the fixture
resets and attaches the Driver Station, publishes mode transitions and resets shared
state during finally-protected teardown. The test remains a lifecycle/control smoke
test; dedicated controller tests assert the effects of individual buttons.

`ARESRobotTimedBehaviorRegressionTest` now resets Driver Station state and restores
the system clock even if robot close throws. Its existing assertions cover alliance
selection, initialization timing, simulation sensor/estimator propagation, disabled
targets, explicit fault recovery, topology strings and field-unit arithmetic. This
does not demonstrate estimator accuracy under noisy real sensors.

`MarvinSuperstructureSafetyTest` previously discarded flywheel and intake-roller
voltage writes, leaving its all-output inhibit claim incomplete. Those fake IOs now
record voltage, and the inhibit case asserts all seven zero-effort channels and
that no velocity/position setpoint API was used. The remaining eleven tests check
effort scaling, measured clearance, stale feedback, rotation units and output math.
The fake IOs exercise the subsystem boundary, not vendor safety enforcement.

Added a flywheel math case that puts NaN, both infinities and a negative value in
each motor position, checks invalid target/tolerance parameters, and checks the
inclusive 250 RPM spread boundary. Existing cases retain strict readiness tolerance
and per-motor rather than average-only checks. All pass.

Renamed the repository source-presence check: this project is not a zero-scheme
fixture, and string assertions do not execute generated lifecycle behavior. Its
other checks establish closeable types, packaging/dependency/source constraints
and the presence of atomic-download code, not a successful remote offset fetch.

## Unchanged tests and fixtures

`AresFrcRemediationTest` independently computes the rear-facing target bearing from
the offset shooter origin and verifies cowl clamping in rotations through the real
reducer. Both tests pass; they do not measure ballistic accuracy.

The test-only autonomous catalog has one enabled, mirrored blue-origin start at
(2,2,0). Its referenced routine drives to (3.6,2.65,0), schedules prepare/collect
markers at 0.1/0.45 progress, gates feeding on arrival, waits 0.5 seconds and stops
the shooter/stows intake. IDs, references, finite geometry, marker ordering and
action names are coherent. Existing native-auto tests decode these classpath
resources, test mirroring and selection, and ensure they are absent from production
generated routines. They are executable test inputs, not competition defaults or
proof of a complete physical drive-and-shoot run.

## Evidence

Generated-project verification and the full FRC suite passed: 299 tests, zero
failures/errors/skips. Policy, documentation links and staged whitespace checks
passed. Logs, per-class XML and all reviewed-file hashes are retained under
`ARESLib-Kotlin/build/audit-pass128-verified-evidence/`.

No production code changed; no allocation benchmark or consumer matrix was repeated.
No hardware, rendered GUI, remote fetch, push, merge or release was performed.
