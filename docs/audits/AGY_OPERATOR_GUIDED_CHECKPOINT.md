# Local operator journey and guided-experiment checkpoint

Date: 2026-09-23. Baseline: `56c53cc60568855e00ca1c39c2f74c06996899e6`.
This continues the [reviewed tuning batch](AGY_TUNING_CHECKPOINT.md). Changes remain local;
no remote CI, push, PR, release or deployment was performed.

## Rendered Lightbot operator journey

The actual Studio window ran from the baseline commit with released ARESLib **19.1.4**.
The normal setup UI created a disposable copy of the bundled Lightbot example **3.0.66**; its robot source
is the released example, not a rebuilt archive containing the previous local FTC facade fix.
That distinction does not affect this LIVE_SAFE heading-gain journey. The local neutral-hold
facade correction retains its separate baseline/fixed constructor regression evidence.

The app used `-PskipKill`, a dedicated `-ParesIsolatedDesktopHome`, and loopback test-control
port 49323. It preserved the immutable runtime snapshot. Startup reported a visible owned HWND
`6559360`, followed by settled presentation with `alwaysOnTop=false`, focused/active/showing true.
Exact-window captures were inspected; compilation or a live process was not treated as UI proof.

Observed through normal UI actions and actual NT4/simulated robot IO:

| Step | Evidence and result |
| --- | --- |
| Setup and launch | Created Lightbot through the setup wizard, selected Local Sim, and used **Verify & launch**. The generated verification build passed and the simulator connected on port 5810. Captures 003-008. |
| Local tuning mode | Selected registered `ARESTuningTeleOp`, then **Start driving**. The simulator reported running/control-ready state. This control performs the existing INIT/START sequence; no mode or arm telemetry was injected. Captures 009-010. |
| Staging | In Robot > Tuning > Advanced profiles & calibration, staged heading derivative gain **0.08 -> 0.09**. The UI reported that nothing had been written or pushed. Capture 020. |
| Calibration arm | Used the existing arm confirmation and 60-second lease. The banner reported robot-confirmed arm. Capture 021. The later expiry was also visible as `Calibration arm timed out` (024). |
| Live request | The UI showed **Waiting for Heading derivative gain acknowledgement** (022), then **Robot acknowledged ... applied experimentally** (023). Source remained **0.08**, Current became **0.09**. |
| Independent wire observation | A subscription-only observer received `/Tuning/Parameters/ftc.drive.heading.kd/Requested = 0.09`, `RequestNonce = 1`, `Acknowledgement = 1|1|APPLIED`, and `Current = 0.09`. No acknowledgements or robot state were fabricated. |
| Explicit disarm | Renewed the expired lease, observed robot-confirmed arm (025), then used **DISARM** while that lease was active. The UI reported `Operator disarmed` (026). Independent `/SysId/Armed` observations went false -> true -> false; mode enable remained true until OpMode stop. |
| Stop with active output | Armed loopback keyboard control on Dashboard, held W, and used the visible OpMode Stop control. Captures 028-030 show control-ready, control-active and waiting-for-TeleOp states. All four motor-power topics had nonzero values before stopping and ended at **0.0**, with driver-station state **DISABLED**. |
| Preservation and local overlay | All **13 authored `.ares` files** remained byte-identical. The only gain persistence was `TeamCode/.ares/local/tuning/runtime.arestuning`: LOCAL_EXPERIMENTAL, heading kd **0.09**, linked to the canonical competition profile. |
| Graceful close | Closed through the owned window's native close path. Studio PID **38028**, simulator PID **28048**, and their recorded wrapper PIDs exited. Ports 49323 and 5810 and both owned runtime snapshots were gone. No broad process kill was used. |

The observer was a separate read-only NT4 subscriber using announced topic IDs and MessagePack
frames. It did not read or mutate private controller state. Output neutralization and the recorded
timestamps are desktop simulator observations, not electrical or target-controller latency results.

Retained evidence is under coordinator worktree `build/agy-ui-check/`: 30 interaction captures,
`startup.png`, `app-run.log`, `nt4-observer.json`, `nt4-drive-stop.json`,
`drive-stop-summary.json`, before/after authored-file hashes, a copy of the experimental overlay,
and `shutdown-check.json`. `ui-evidence-manifest.json` binds the source identity and 39 artifacts.
The exact compile/run commands and test-control port are retained with the local task history.

## Guided-experiment lifecycle defect

Two parallel AGY workers used `gemini-3.8-flash-high --effort high` in disjoint worktrees.
The guided worker identified a pre-existing realistic defect: an evaluation can finish after
the student selects a different candidate or begins another draft, staging obsolete gains and
replacing the new context. Loading another experiment also surfaced a false cancellation error.
These are proposal/evidence defects; no automatic robot actuation or canonical promotion is claimed.

The accepted change cancels superseded work, advances an operation generation on context changes,
checks that generation after suspended work, and propagates coroutine cancellation. Completed disk
work is not claimed to be rolled back; obsolete completions cannot stage a proposal or replace the
current UI context. Existing workspace scope ownership still cancels on workspace identity changes.

Independent review narrowed the worker's patch and corrected its tests:

- Rejected unrelated foreign-workspace filtering and literal path equality changes.
- Restored `comparisonReport = report`, which the worker accidentally removed during correction.
- Replaced scheduler-idleness assumptions with an entered comparison barrier and joined owned jobs,
  including cancellation completion. Tests use one event-loop context, bounded waits, and cleanup.
- Selected the baseline candidate explicitly: the real list sorts newest first, so the worker's
  original default-selection assumption was wrong. Corrected a `description`/`explanation` API error.
- Kept a positive control that persists evaluation, stages the intended proposal and exposes the
  comparison report. Tests use the real evaluator and repository through public intents.

The corrected regression against unchanged production ran **4 tests: 3 intended failures and
1 positive-control pass**. Candidate selection and Begin exposed stale staging; LoadExperiment
exposed `StandaloneCoroutine was cancelled` in the operator error state. No timeout was used as
defect evidence. The earlier test-compilation failure log remains separate.

Final corrected validation: **57 tests passed, zero failures, errors or skips**. The suite includes
the four new lifecycle regressions, 13 existing guided-experiment tests, 12 profile authoring tests,
13 promotion persistence tests and 15 value-resolution tests. Command from `ARES-Analytics/`
with JDK 17 and the released dependency mode:

```powershell
.\gradlew.bat :app:test --tests '*GuidedExperimentLifecycleAuditTest' `
  --tests '*GuidedTuningExperimentTest' --tests '*TuningPromotionPersistenceAuditTest' `
  --tests '*TuningProfileAuthoringTest' --tests '*TuningValueResolutionAuditTest' `
  --no-parallel --console=plain
```

The build completed in 1m31s; this is a build duration, not a robot loop measurement.
Logs/JUnit evidence remain in `build/agy-ui-check/guided-corrected/` and `guided-corrected.log`.
Only the guided viewmodel and its regression require new runtime validation; the already accepted
wire/robot implementation and generated-consumer checks remain valid for unchanged code.
The new tests belong to the existing Analytics app CI scope. No CI workflow was redesigned.
Local source-policy and changed-path classification results are recorded with this checkpoint.

## Profile-persistence review and delegation limits

The second worker found no practical production defect in its bounded load/promote review.
Independent inspection confirmed existing preflight revision/catalog checks and transaction use;
existing tests already cover stale review, failed replacement and competing promotion. Its proposed
new test file was not integrated: it duplicated coverage and omitted the declarations required by
its load fixture. A no-defect review does not require manufacturing code or tests.

The worker reports are retained in their worktrees as submissions, not independent acceptance.
Main conversations: guided `7f5acee9-b879-46f3-bfd9-256f5e09ca26` (two turns), persistence
`82f7fa7c-fe86-4588-a4e6-677943004aa5` (one turn). The initial guided exploration exceeded the
requested read budget; the budget is a prompt constraint, not an enforced CLI limit. Both outputs
still required independent review. No Codex savings percentage or robot performance gain is claimed.

Deduplicated conversation/step metrics from `worker-efficiency-final.json`:

| Worker | Successful file reads | Distinct paths | Repeated reads | Tool errors | Reported cumulative duration |
| --- | ---: | ---: | ---: | ---: | ---: |
| Guided, including correction | 35 | 11 | 24 | 4 | 622 s |
| Persistence | 8 | 8 | 0 | 4 | 178 s |

Reads include guidance and necessary verification, so repeats are not all waste. These counters
show that excessive rereading and correction effort remain the main observed delegation costs.
The persistence worker stopped without a production patch, and independent review avoided adding
redundant tests. AGY's cumulative input/output/cache/thinking counters are retained as reported;
they are not billing equivalents or a measured Codex-only comparison. Keep the next briefs narrow,
explicitly permit a no-change result, and review completed patches rather than polling live drafts.

## Remaining boundaries and next supported action

The selected rendered live-tuning journey is verified on desktop. Physical-controller timing,
electrical output behavior and hardware acceptance remain unavailable. The narrow right-hand
calibration selector wraps some lesson-only labels badly at the default window size; it did not
block this journey and is deferred as a usability issue. This run does not validate every calibration.

The selected regression and operator scenarios now pass. A specific next source
question is workspace configuration lifetime: some models are remembered by workspace ID while
their configuration includes a project path. Establish whether a supported same-ID path change
can retain an obsolete model before proposing a fix; this is a review lead, not a confirmed defect.
Keep the autonomous goal active and all new work local.
