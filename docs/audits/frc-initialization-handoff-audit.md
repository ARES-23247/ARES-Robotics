# FRC initialization handoff audit

Pass 106 traces ownership after the hardware factory returns and before composition finishes.

## Finding and change

Previously, mechanism casts and topology setup occurred before every returned IO owner was retained.
Drivetrain and vision registration happened only inside the later `FrcSwerveRobot` constructor. If
composition failed earlier, the season robot's cleanup registry lacked some completed owners.
`robotInit()` also did not itself arrange immediate teardown when initialization threw. These are
source-level ownership and exception-path findings; no native failure-before run is claimed.

The composition root now retains all closeable drivetrain, vision and mechanism IO in the existing
registry before casts or other composition work. Later normal registration still supplies topology
and polling behavior. The registry closes an object once by identity across its device and lifecycle
lists, so early retention does not duplicate successful-path teardown. Simulation, dashboard input
and PDH fields retain their existing season-root ownership.

Initialization now invokes partial teardown immediately on failure, suppresses any cleanup failure
onto the original exception and rethrows the original. Close has a synchronized first-entry guard so
a later cleanup request cannot repeat native teardown. All existing cleanup stages and their order
remain intact. The guard does not promise successful disposal of hardware whose close operation fails.

## Validation

Five tests use the real `HardwareRegistry` to cover failure midway through normal registration,
retained-but-unregistered owners, successful duplicate registration, null/non-closeable inputs,
repeated registry teardown, immediate initialization-failure cleanup, preservation of both failures
and no cleanup on successful initialization. One test also constructs an actual `ARESRobot` under
host HAL and closes it twice without running `robotInit()`.

The installed Phoenix 26.1.1 `CANBus` API was inspected with `javap`: it exposes no close method or
`AutoCloseable` contract. No speculative bus-close operation was introduced. Runtime library ownership
contracts were read to establish the handoff; their entire implementations are not newly certified.

These changes add only startup/lifecycle bookkeeping. Early retention does not register polling,
refresh hardware or add periodic allocations. No physical timing or native leak-rate measurement is
claimed. The library source remains unchanged at candidate `17.0.3-rc.de2cb9c407a0`; validation is
scoped to full FRC tests, generated-project verification and repository policy.

## Remaining scope

`ARESRobot.kt` remains partial for complete initialization with injected vendor failures, concurrent
initialization/close, repeated initialization, nested resource failures and runtime simulation behavior.
Resources hidden inside constructors that fail before returning still require constructor-local
cleanup. The tests establish ownership and attempted teardown, not physical actuator neutralization.

## Final evidence

Full FRC validation passed 176 tests, including all five methods in `FrcHardwareHandoffTest`, with zero failures, errors or skips. Generated-project and namespace verification and monorepo policy passed. Gradle reused valid unchanged outputs.

Copied FRC JUnit XML and successful logs, with verified SHA-256 hashes, are recorded in `ARESLib-Kotlin/build/audit-pass106-verified-evidence/summary.json`. No native failure-before execution is claimed; the original ownership gap was identified from the allocation/registration sequence.
