# FRC disable cleanup audit

Pass 104 examines disable cleanup sequencing and close failure collection.

## Confirmed defect

The disable callback called generated-control cancellation, autonomous stop, SysId stop, mechanism
inhibition and both rumble stops sequentially. An early exception skipped all later operations,
including mechanism inhibition/neutralization. The original six calls were extracted unchanged into
the function used by `disabledInit()`; a regression against that sequence failed after the first
injected exception (four test methods, one failure).

The sequence now attempts every stage in its original order, retaining the first error and attaching
subsequent errors before rethrowing. This preserves failure visibility while ensuring a throwing owner
cannot prevent later owners from receiving their cleanup calls. Initialization guards are unchanged.

## Shared collection and rejected hypothesis

Close already attempted its individual cleanup stages. Its local error collector now uses the same
tested season helper as disable; its order and resource-clearing `finally` blocks are unchanged.
The suspected same-instance suppression problem did not reproduce: Kotlin's suppression behavior
already handles the same throwable safely. That is retained coverage, not a newly fixed defect.

The helper is lifecycle-only. Its inline attempts avoid callback wrappers at direct call sites;
one collector is created per disable/close invocation. No periodic work or hardware polling is added.
No JIT allocation-rate or physical latency claim is made.

## Validation and remaining scope

Four tests cover failure at each of the six disable stages, the same exception from two owners,
secondary errors including an `AssertionError`, finally-block execution, first-error identity and
successful call ordering. These exercise the function called by `disabledInit()`, with injected actions.
They do not instantiate a full season robot or prove physical motor neutralization. The original
aggregation-only test run passed all three methods before the disable regression was added.

`ARESRobot.kt` remains partial: complete initialization, shutdown reentrancy, nested resource teardown
and failures inside device neutralization require further integration coverage. The helper's guarantee
is that each supplied operation is attempted, not that failing hardware successfully stops.

The shared library remains unchanged at local candidate `17.0.3-rc.de2cb9c407a0`. Full FRC tests,
generated-project verification and monorepo policy validate this season-only change; unrelated
consumer matrices are not rerun.

## Final evidence

Full FRC validation passed 165 tests, including all four methods in `FrcCleanupFailuresTest`, with zero failures, errors or skips. Generated-project and namespace verification and monorepo policy passed. Gradle reused valid unchanged outputs.

Copied FRC JUnit XML and successful logs, with verified SHA-256 hashes, are recorded in `ARESLib-Kotlin/build/audit-pass104-verified-evidence/summary.json`. Initial failure XML is preserved in `ARESLib-Kotlin/build/audit-pass104-before-evidence/`.
