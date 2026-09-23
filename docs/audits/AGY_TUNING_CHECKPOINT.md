# Local AGY tuning checkpoint: independent review

Date: 2026-09-23. Source baseline: `9426ee3c86f331910b2ce1656e33c774506a44e1`;
local saved-workflow commit: `674901bb3393d49c9b4ce6fb95c4999f82def77f`.
Coordinator branch: `codex/agy-audit-coordination`. New changes remain local.

## Practical findings and accepted changes

| Finding | Origin and impact | Change |
| --- | --- | --- |
| A delayed `SysId/Status = NONE` can fit collected samples after remote disarm. Explicit disarm and mode-loss paths also retained samples. | Pre-existing; invalid experiment results could become gain proposals. | Discard aborted collection; clear data on explicit disarm/mode loss; preserve natural completion. |
| Disarm transport exceptions escape the intent handler without a useful operator error. | Pre-existing; local authorization was already revoked, but failure feedback was missing. | Preserve cancellation and expose the publication failure in state. |
| Queued tuning actions and acknowledgements are not bound to the selected telemetry target epoch. | Pre-existing; a changed target can inherit pending work or misleading confirmation. | Capture identity at the public action, recheck before dispatch and acknowledgement. |
| Tuning publication can resume after replay/target changes during publisher registration. | Pre-existing; stale live parameter mutation. | Validate live context around suspended registration and before enqueue; suppress stale local echoes. Successful enqueue remains distinct from robot acknowledgement. |
| FTC season facade omits its neutral-output hold from the tuning context. | Pre-existing; disabled-only parameters are rejected even during the runtime's permitted stationary hold. | Wire the existing base hold predicate, matching the starter. No safety predicate or library policy was weakened. |

Tuning observations/actions are gated during replay/disconnect, and SysId displays replay as
unavailable for live control. This does not create a new hardware enable or override robot leases.

## Independent review corrections

The first lifecycle submission would reject ordinary false feedback while ARMING and send old
operation STOP/token writes into a successor connection. Both changes were rejected. A later
claimed restoration actually removed existing capability checks; the coordinator compared the diff,
preserved the rejected artifact, and restored the exact baseline signal generator. That production
file has no accepted change.

Other rejected work includes newer-nonce fast failure (the existing timeout is sufficient),
string-matching banner colors, production visibility widened only for tests, nonexistent state/API
names, synthetic tests that bypassed the changed facade, and incomplete client cleanup. The final
constructor test captures the actual facade callback. Transport tests use controlled registration
suspension with a draining wire, preserve successful-enqueue semantics, and dispose owned clients.
Compilation exposed additional worker test API/import errors; the coordinator corrected those and
a coroutine assertion that ran before the scheduled acknowledgement poll.

The integrated transport change exceeded the existing service's 750-line limit. Its cohesive live
publication operation was extracted into `Nt4TuningRequestTransport.kt`, leaving the client at 728
lines. The public wire/replay tests passed after extraction; no maintainability limit was relaxed.

Worker reports remain scoped submissions, not independent acceptance:
[operator](GEMINI_OPERATOR_PATH_AUDIT.md), [lifecycle](GEMINI_TUNING_LIFECYCLE_AUDIT.md),
[transport](GEMINI_TUNING_TRANSPORT_AUDIT.md). This report controls final validation claims.

## Validation

Use released dependency mode: ARESLib `19.1.4` from the configured GitHub Maven channel, JDK 17,
and the local Android SDK. No library source, release versions, archives or publication changed.

- Independent abort reproduction on baseline: **1 test failed** at the intended completion-path assertion.
- Corrected collector suite: **31 passed**, including independent known-plant normal completion.
- Combined Studio scope: **154 passed; 0 failures/errors/skips**. Includes those collector tests,
  tuning request/acknowledgement, wire/replay boundary, signal generator, promotion/proposal,
  architecture and consumer-support checks. Counts are not additive to the preceding 31.
- FTC baseline: **27 tests, 1 expected failure**, specifically the new real-facade neutral-hold assertion.
- Corrected FTC scope: **27 passed; 0 failures/errors/skips**.
- Generated-consumer integration: **1 outer test passed**, exercising two fresh generated projects;
  each passed **32 nested tests** (25 TeamCode and 7 simulator). Outer/nested counts describe different
  layers, not distinct source coverage. Evidence is under `consumer-roundtrip-evidence/generic/operation-3`
  and `operation-4` in the app build directory.
- After transport extraction: **13 wire/replay tests passed; 0 failures/errors/skips**. These overlap
  the 154 Studio checks. Only comment encoding was restored afterward; executable code is unchanged.
- Source-policy verification passed: guidance, documentation links, maintainability inventory and
  unchanged release identity. No size-limit or CI-policy exceptions were introduced.
- All **6 guidance tests passed**. The first restricted attempt failed while creating temporary Git
  fixtures; the normally approved rerun passed. This was an execution-permission issue, not a test fix.
- Existing changed-part CI selects FTC and Studio, including `:app:consumerRoundtripTest`. The generated
  Analytics size inventory conservatively also selects shared/gateway. Those implementations did not
  change and their full suites were not rerun locally. No remote CI execution or redesign is claimed.

Reproduce the focused validation from the corresponding product root with JDK 17 and the local
Android SDK (default released dependencies):

```powershell
# ARES-Analytics
.\gradlew.bat :app:test --tests '*TuningLiveRequestAuditTest' --tests '*SysIdAcknowledgementAuditTest' --tests '*SysIdSignalGeneratorTest' --tests '*SysIdLiveCollectionAuditTest' --tests '*ExternalTuningProposalAuditTest' --tests '*TuningPromotionLifecycleAuditTest' --tests '*ProjectModelArchitectureTest' --tests '*Nt4TuningReplayBoundaryAuditTest' --tests '*Nt4TuningRequestWireAuditTest' --tests '*ConsumerRoundtripSupportTest' --no-parallel --console=plain
.\gradlew.bat :app:consumerRoundtripTest --tests '*GenericStarterConsumerRoundtripIntegrationTest' --no-parallel --console=plain
# ARES-FTC
.\gradlew.bat :TeamCode:testDebugUnitTest --tests '*AresTuningOperatorAuditTest' --tests '*FtcSeasonLifecycleAuditTest' --tests '*StationaryCalibrationGateTest' --no-parallel --console=plain
# Monorepo root
.\scripts\verify-monorepo-policy.ps1
python -m unittest discover -s scripts/tests -p test_agent_guidance.py
```

Commands and JUnit reports are retained under the coordinator's ignored `build/agy-audit/`.
The initial combined Studio run failed test compilation; its log is preserved alongside the corrected
passing run. Generated-consumer runtime validation uses desktop simulated IO. No new physical-controller
or rendered-window acceptance is claimed. The scoped review ledger preserves prior records and binds
these changes to their current content. The local Git checkpoint supplies the reproducible source identity.

## Delegation efficiency and reusable setup

Three separate AGY worktrees used `gemini-3.8-flash-high --effort high`; workers used source-file
tools, and Codex owned build scheduling, review, integration and the ledger. The user authorized
three parallel workers and local-only work. The reusable workflow and goal record that setup.

An intermediate diagnostic snapshot counted 102 successful operator reads over 44 paths (58 repeat
reads, 62 failed calls across initial/continuation attempts), and 87 lifecycle reads over 27 paths
(60 repeats, 9 failed calls). Some rereads were necessary review; many operator failures were guessed
paths. A new transport brief with verified paths and a bounded read budget produced its first patch
with 10 successful reads over 10 paths and one failed call. This is evidence of less exploration,
not a controlled performance benchmark or proof of patch quality; independent correction remained
necessary. Detailed snapshots are in `build/agy-audit/worker-efficiency.json` and
`worker-efficiency-final.json`; the latter deduplicates terminal conversation/step IDs across all
retained initial and correction streams:

| Worker | Successful reads | Distinct paths | Repeated reads | Failed tool calls |
| --- | ---: | ---: | ---: | ---: |
| Operator | 113 | 48 | 65 | 64 |
| Lifecycle | 126 | 31 | 95 | 11 |
| Transport | 12 | 10 | 2 | 1 |

The successful main conversations required three, three and two turns respectively, including
coordinator feedback. Their final AGY-reported elapsed times were 1,498 s, 1,441 s and 346 s. These
are different tasks with overlapping execution and substantial independent Codex correction, so they
cannot be used as an apples-to-apples speedup or summed into wall-clock time. Future briefs retain
verified paths, an 8-12-read exploration boundary and final patch review instead of repeated draft reads.

AGY counters include cumulative conversation/cache data and must not be summed blindly across
continuations. The account-wide Codex snapshot was 5% weekly used; no task-level counterfactual
establishes net savings. No robot loop, allocation or sensor-to-output performance improvement is
claimed. Cosmetic/style work and speculative optimizations remain deferred.

## Remaining boundaries

The available tests use desktop JVMs, mock/simulated IO and existing consumer seams. Physical
neutralization, controller timing and a complete rendered operator journey remain hardware/GUI
checkpoints. A packet already enqueued can still be delivered; a later context change cannot recall
it, and a successful enqueue never proves application. These guards do not claim global atomic
serialization with cross-thread replay transitions. Robot-side authorization and leases remain required.

This bounded tuning batch closes with the verified local checkpoint. The autonomous improvement
goal remains active; a future batch must choose a distinct practical risk from retained evidence.
The rendered operator journey is the next explicit validation gap, subject to safe window/process
ownership. Do not resume the historical audit-every-file campaign or publish automatically.
