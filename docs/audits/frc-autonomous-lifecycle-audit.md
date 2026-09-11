# FRC autonomous lifecycle audit - pass 115

## Scope

Fully reviewed `FRCAutoOrchestrator.kt` and `FrcAutoCapabilities.kt`, plus four existing
test files: `FrcNativeAutoContractTest` (nine methods), `FRCAutoAllianceMirroringContractTest`
(one), `FrcGeneratedRoutineRuntimeTest` (two), and `GeneratedDriveSinkTest` (four).
Added seven methods in `FrcAutoFailureAuditTest`. Read and checked the production action
catalog, autonomous catalog and do-nothing routine against generated bindings and runtime
factories. These are declarative inputs, validated through generation and contract tests.

Also traced the complete generated capability adapter to understand transforms, footprint
checks, task construction and lifecycle delegation. Its ledger entry remains partial:
drive-task resource masks, delegate cleanup under exceptions, trajectory preset limits and
marker/group lifecycle combinations require dedicated follow-up. The large teleop controller
was sampled only and is not claimed as reviewed here.

## Confirmed defects and fixes

- `stop()` cancelled routines before marking itself finished. A cancellation dispatch failure
  skipped the finished state, stop commands, mechanism inhibit and direct hardware safety.
  The finished state now commits first; remaining cleanup operations are each attempted.
- Drive zero, X-brake, mechanism inhibit/fault latch and direct hardware safety were sequential
  without failure isolation. A failed earlier operation skipped later ones. The existing
  `FrcCleanupFailures` helper now collects failures while attempting every operation, then
  propagates the first error with subsequent failures attached.
- Initial cancellation and drive stopping happened outside the preflight fault boundary.
  Those failures now enter the fault lifecycle before any selection or routine arming.
- Cancellation errors could retain the orchestrator's active execution/configuration references.
  These references now clear in `finally`.
- A failed Selected telemetry write suppressed Status and Error diagnostics during abort.
  Status fields and independent diagnostic writes now get their own attempts. Telemetry
  failures still propagate after safety work; they are not silently treated as success.
- Existing native-auto tests did not close their robot fixtures. JUnit cleanup now attempts
  every constructed robot and restores the clock/command registry even if closing fails.

The new aggregation allocations occur on initialization and terminal/failure transitions,
not each healthy periodic update. Reusing the existing helper avoids another cleanup utility.

## Failure evidence and Store contract

Seven new methods ran against the original production source: five failed. The failures cover
cancellation, independent stop stages, initial stop fault handling, independent diagnostics,
and direct hardware safety after a reducer invalidates the Store. Two factory/transfer tests
passed before and after. The stop-stage table exercises drive-zero, brake and inhibit failure
positions after the fix; the original table stops at its first failed assertion.

A preliminary fixture omitted required routine JSON collections and was corrected before
collecting the authoritative before evidence. An initial fault fixture also incorrectly expected
Redux recovery after a reducer exception. Store deliberately rejects all subsequent dispatches
after a reducer/estimator failure to preserve estimator integrity. The final regressions distinguish
recoverable action-observer failures from fatal reducer failures. In the fatal case they require
direct hardware safety and a finished runner while confirming that the Store stays invalidated.
They do not weaken that contract or claim an inhibit action can commit to a failed Store.

The final before XML and log are preserved under
`ARESLib-Kotlin/build/audit-pass115-before-evidence/`. Fixed source was temporarily backed up
while the original HEAD implementation ran against the corrected seven-method test file, then
restored before full validation.

## Behavior verified without changes

Action descriptors/factories match generated action keys; each invocation produces a fresh task
with its declared resource mask. Unsupported arguments fail explicitly and unknown keys return
no implementation. The readiness predicate checks measured flywheel/cowl validity and tolerances;
it is a readiness condition, not an enable/arm authorization. The orchestrator enforces mechanism
safety before starting and during execution.

The transfer gate waits at most 2,000 ms for readiness. Readiness at 1,999 ms starts one 450 ms
transfer, completing at 2,449 ms; readiness at/after the deadline cannot start it. Started-transfer
elapsed-time rewind terminates it. End/cancel zeros feeder/floor targets. Existing tests verify
transfer rearming and consumed-trigger completion, selection locking, both-alliance preflight,
safe fallback preserving localization, and stop cancellation.

Existing numeric tests verify FRC X-axis reflection, heading/tangent and curvature signs,
generated teleop alliance translation without rotation reversal, normalized axis limits, and
assist ownership. Production autonomous selection contains only the reviewed do-nothing entry;
the drive-and-shoot routine is a test fixture, not a newly enabled match routine.

## Validation and limitations

Full FRC validation passed 225 tests with zero failures, errors or skips, including seven
new methods and sixteen existing methods reviewed in this group. Generated-project/namespace
verification and monorepo policy passed. Gradle reused valid unchanged outputs. Both final
processes reached terminal exit zero. Copied XML/logs and source hashes are recorded in
`ARESLib-Kotlin/build/audit-pass115-verified-evidence/summary.json`.

Hardware safety tests observe a registered `SubsystemIO.safe()` probe reached through the real
robot/registry boundary. They do not establish physical motor neutralization, CAN behavior or
HIL performance. No mathematical formula needed correction in the reviewed scope. The library
is unchanged at candidate `17.0.3-rc.100852e472fb`, source tree
`100852e472fbeeba64fdf799665f51b4687f7f1b`; lifecycle-only changes do not require a new library
matrix or a repeated steady-loop allocation benchmark. No push, merge or release occurred.
