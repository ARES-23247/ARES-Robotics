# Studio Tuning & Calibration Lifecycle Bounded Audit Report

Worker submission; independent corrections and actual validation are recorded in
[the coordinator checkpoint](AGY_TUNING_CHECKPOINT.md). Submission claims are not acceptance.

**Date:** 2026-09-23 | **Root:** `C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/gemini-tuning-lifecycle-audit`
**Git Baseline:** `9426ee3c86f331910b2ce1656e33c774506a44e1` (clean, uncommitted changes)
**Role:** Gemini 3.8 Flash High implementation worker coordinated by Codex (file tools only).
**Status:** Candidate fixes and regression tests implemented locally; **unverified** until coordinator combined test execution.

## 1. Candidate Fixes Implemented
1. **SysId Aborted Sample Discard on Late `NONE` (`SysIdDataCollector.kt`):** Baseline pre-existing defect. When a routine is stopped/disarmed (`!isRoutineRunning && !isLoading`), a subsequent `SysId/Status = "NONE"` from the robot now clears the buffer and returns `null` without running `computeSampleAnalysis`.
2. **Buffer Clearing on Disarm and Mode Loss (`SysIdViewModel.kt`):** Baseline omitted buffer clearing on `SysIdIntent.DisarmCalibration` and when `SysId/ModeEnabled` dropped to `0.0`. Added `dataCollector.clearBuffer()` to both.
3. **Disarm Transport Error Capture (`SysIdViewModel.kt`):** Baseline let exceptions escape uncaught on disarm STOP publication. Wrapped in try/catch and recorded in `_state.errorMessage`.
4. **Replay Mode & Disconnect Gating (`TuningViewModel.kt`, `SysIdViewModel.kt`):** Gated observation loops and push/pull intents on `isConnected && !isReplayActive`. Set `isRobotConnected = current.connected && !current.replay` in `SysIdViewModel`.
5. **Target Epoch Gating (`TuningViewModel.kt`):** Baseline never checked target epoch during live tuning. In Round 1, the worker introduced an epoch check that captured epoch *after* publication (worker regression). In Round 3, `expectedEpoch` is captured synchronously at `pushOne`/`pushAll` before coroutine launch, validated under `requestMutex` before publication, and checked in `awaitTuningResult`.

## 2. Rejected Worker Regressions & Restorations
1. **ARMING False Feedback Disarm (REJECTED):** Worker round 1 disarmed on `SysId/Armed = 0.0` during `ARMING`. Baseline routine publishes false before receiving STOP/lease handshake. Restored baseline guard order in `SysIdSignalGenerator.kt` (negative feedback disarms established `ARMED` only).
2. **`finishMotion` Network STOP Writes (REJECTED):** Worker round 1 added suspending STOP writes on target switch in `finishMotion`. Restored baseline non-suspending `finishMotion` without network writes to avoid polluting successor sessions.
3. **`requireMotionAuthorization()` Changes in `SysIdSignalGenerator.kt` (REJECTED):** Restored original parameterless implementation without unrequested mechanism checks. `SysIdSignalGenerator.kt` has zero production diffs from baseline.
4. **Non-Existent Test APIs & Dispatcher Await (REJECTED):** Removed nonexistent `SysIdState.identificationResult` and removed redundant nondeterministic natural completion test. Packed `SysId/Data` uses milliseconds (`timestampMs`).

## 3. Remaining Source-Contract Limitations
- **ViewModel Replay Gating:** Checking `!isReplayActive.value` prevents UI dispatch, but does not provide atomic serialization if replay transitions while a transport packet is already in-flight on the socket.
- **Physical Motion on Disconnect:** If connection drops after robot receives `START_`, Studio cannot send network STOP over the dead socket; safety relies on robot-side lease/watchdog timeouts.

## 4. Test Fixtures & Ownership
- Owned fixtures inspected: `ExternalTuningProposalAuditTest.kt` and `TuningPromotionLifecycleAuditTest.kt` instantiate real `Nt4ClientService(db)`; `ProjectModelArchitectureTest.kt` tests sources via string checks. None use Mockito mocks; no stubbing was required or modified.

## 5. Regression Tests Added (Marked NOT RUN)
Commands for coordinator execution:
- `./gradlew :app:test --tests "com.ares.analytics.viewmodel.TuningLiveRequestAuditTest" --no-parallel --console=plain`
- `./gradlew :app:test --tests "com.ares.analytics.viewmodel.sysid.SysIdAcknowledgementAuditTest" --no-parallel --console=plain`

**`TuningLiveRequestAuditTest.kt`:**
- `push live request rejected when replay active` [NOT RUN]
- `push all rejected when replay active` [NOT RUN]
- `replay mode activated during await aborts live request as unknown` [NOT RUN]
- `target epoch change during await aborts live request as unknown` [NOT RUN]
- `target switch before queued coroutine executes prevents sending request` [NOT RUN]
- `target switch while waiting behind active request mutex prevents second request from publishing` [NOT RUN]
- `target clear during publication callback followed by matching acknowledgement is rejected as unknown` [NOT RUN]
- `disconnect or replay clears live observations and consumer support` [NOT RUN]
- `pulling constants when disconnected or in replay fails with descriptive error` [NOT RUN]

**`SysIdAcknowledgementAuditTest.kt`:**
- `false feedback while ARMING followed by fresh true can arm and prevents START before true` [NOT RUN]
- `delayed old START completion after connection switch cannot revive motion and sends no new commands to successor` [NOT RUN]
- `late status NONE after routine stopped clears buffer without analyzing` [NOT RUN]
- `disarm calibration intent clears data collector buffer and revokes arm` [NOT RUN]
- `mode loss while routine running clears collector buffer and revokes arm` [NOT RUN]
- `disarm calibration intent captures transport failure in error message` [NOT RUN]
