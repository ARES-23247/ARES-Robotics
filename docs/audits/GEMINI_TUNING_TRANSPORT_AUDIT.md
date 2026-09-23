# Tuning Transport Replay & Target Epoch Audit

Worker submission; independent corrections and actual validation are recorded in
[the coordinator checkpoint](AGY_TUNING_CHECKPOINT.md). Submission claims are not acceptance.

## Exact Reviewed Paths
- `ARES-Analytics/app/src/main/kotlin/com/ares/analytics/service/Nt4ClientService.kt`
- `ARES-Analytics/app/src/main/kotlin/com/ares/analytics/service/nt4/Nt4OutboundPublisher.kt`
- `ARES-Analytics/app/src/main/kotlin/com/ares/analytics/service/nt4/Nt4ConnectionLifecycle.kt`
- `ARES-Analytics/app/src/main/kotlin/com/ares/analytics/service/TelemetryStore.kt`
- `ARES-Analytics/app/src/main/kotlin/com/ares/analytics/viewmodel/TuningViewModel.kt`
- `ARES-Analytics/app/src/test/kotlin/com/ares/analytics/service/Nt4TuningRequestWireAuditTest.kt`
- `ARES-Analytics/app/src/test/kotlin/com/ares/analytics/service/Nt4TuningReplayBoundaryAuditTest.kt`

## Classification & Origin
- **Classification:** Stale live mutation / transport boundary race defect (source finding pending test execution).
- **Origin:** Asynchronous dynamic publisher registration in `Nt4OutboundPublisher.ensurePublisher` suspends while sending schema text frames. During suspension, live transitions (log replay activation via `isReplayActive = true` or target epoch increments via `clearLiveTargetState()`) occur without altering socket generation (`tuningConnectionId`). Because ViewModel guards run prior to transport invocation, they cannot intercept suspended transport coroutines, allowing stale tuning mutations to be enqueued to a live robot and committed to local telemetry.

## Demonstrated Failure Sequence (Source Analysis)
1. `Nt4ClientService.publishTuningRequest` is invoked with valid connection generation.
2. `Nt4OutboundPublisher.publishTuningRequest` calls `ensurePublisher` for parameter/nonce topics, suspending on WebSocket text frame delivery.
3. Log replay starts or live target resets, incrementing `targetEpoch` without changing socket identity.
4. `ensurePublisher` completes. Baseline socket checks pass.
5. The combined binary frame (`Requested` + `RequestNonce`) is enqueued via `outgoing.trySend`, delivering a stale parameter modification to the live robot.
6. Local observation frames are written to `TelemetryStore`, polluting replay or the new target epoch.

## What Changed
- **`Nt4OutboundPublisher.kt`:** Added `isLiveValid: () -> Boolean = { true }`. Evaluated at invocation, after each dynamic registration, and immediately before `trySend`. Fails closed (`false`) if context became stale before enqueue.
- **`Nt4ClientService.kt`:** Restored `outboundPublisher` visibility to `private`. In `publishTuningRequest`, captured `initialEpoch = telemetryStore.currentTargetEpoch()`, verified `!isReplayActive.value`, and passed `isLiveValid = { !isReplayActive.value && telemetryStore.currentTargetEpoch() == initialEpoch }`. Preserved `true` as enqueued-only (acknowledgement still required); verified `isLiveValid()` before each telemetry write to suppress stale local writes without claiming rollback of enqueued wire frames.
- **`Nt4TuningReplayBoundaryAuditTest.kt`:** Added tests using unlimited channel and `CompletableDeferred` gates to verify: invocation rejection, suspended-registration replay rejection, target epoch advancement rejection, live delivery, and post-enqueue transition telemetry suppression. Added test reflection helper and guaranteed client `disposeAndJoin()` cleanup.

## Test Expectations (Tests NOT RUN)
- Replay active at invocation immediately returns `false` (0 wire/telemetry frames).
- Replay activation or epoch advancement during suspended registration returns `false` and enqueues 0 binary frames (baseline fails assertion, not timeout).
- Live transmission retains atomic two-message binary wire frame.
- Post-enqueue transitions return `true` but suppress local telemetry.

## Limitations
- Tests are **NOT RUN**; findings and fixes are based on source analysis pending coordinator Gradle verification.
- Only tuning transport is modified; no hardware or live network validation was performed.
