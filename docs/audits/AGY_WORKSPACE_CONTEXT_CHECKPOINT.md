# Local workspace context checkpoint

Date: 2026-09-23. Baseline: `e5331cc7c5c3094160ae47c17180365a501fbd62`.
Continues the [operator/guided checkpoint](AGY_OPERATOR_GUIDED_CHECKPOINT.md). All work stays local.
No push, PR, protected-main merge, release, deployment or remote CI run is part of this batch.

## Confirmed practical defects

**Changing a project folder while retaining the workspace ID skipped runtime teardown.**
Profile Settings saves `config.copy(projectPath = ...)` without changing the ID. MainViewModel
previously released runtime ownership only when the ID changed. The existing release hook disarms
keyboard control, stops NT4/diagnostics, and joins builds, deployments and the managed simulator.
Skipping it made the new configuration observable before those old owners were released.
The MainViewModel regression observes both state and the persisted workspace file around the real
SaveConfig intent and an explicit release barrier. It fails on unchanged production at the intended
assertion; a preference-only save passes without requiring teardown.

The scope and model graph also used only the workspace ID in multiple remember keys. They now
include the project path together: cancelling the old scope without replacing its models would
strand those models with cancelled jobs. Related mission/content/pending-launch state resets at
this boundary. Configuration effects include the replacement model's identity so initialization
is repeated even if league, navigation or a project path is otherwise unchanged. A Compose scene
test changes actual configuration state, checks preference continuity, observes cancellation of
an old child job, executes work on the new scope, and verifies cancellation on scene disposal.

**A superseded tuning load could restore the old shared ProjectSession selection.**
The old load checked its generation before dispatch and before publishing its view state, but
ProjectSession.snapshot changes shared session state. A delayed old load could change that state
after the new project was loaded, causing subsequent revision-bound promotion to fail as stale.
The reproduction uses actual projects, repository, session and public tuning intents with controlled
dispatch. Both queued work and work already entering the session reproduced this pre-existing bug.

The accepted fix cancels superseded tuning loads and checks request ownership inside the existing
ProjectSession selection lock, immediately before any selection/read side effect. This closes the
gap between a caller-side generation check and acquiring that lock, including a cancelled prior
workspace model. Existing three-argument session callers retain their behavior through a delegating
overload. This batch does not claim that every asynchronous session caller has adopted the guard.
Already completed disk mutations are not rolled back by a later workspace change.

## Independent acceptance and validation

Two AGY workers ran concurrently using `gemini-3.8-flash-high --effort high`, isolated at the same
baseline with disjoint files. The workspace worker supplied model-lifetime changes; the promotion
worker supplied load cancellation/generation checks. Codex reviewed both, required corrections,
added the shared-lock guard and the in-flight/replacement-model regressions, and ran all commands.

- Existing baseline: **16 tests pass** (workspace selection and promotion lifecycle).
- Corrected new regressions against unchanged production: **6 tests, 3 intended failures and
  3 passes**, no errors/skips. Failures prove skipped teardown and the two old-session restores.
- Reviewed worker candidate: **7 tests, 1 intended failure**. The caller-side checks still failed
  when the old session call had already started. The accepted fix therefore includes the guard
  under the shared lock, rather than claiming cancellation alone solves synchronous work.
- The worker's separate claim of a false cancellation error did **not** reproduce; that test
  passed on baseline and is retained as a cancellation control, not counted as another defect.
- Corrected final suite: **78 pass**, zero failures, errors or skips. This includes 8 new
  workspace/load regressions and controls, 12 existing session tests, 15 promotion lifecycle tests,
  28 live-request tests, 10 external-proposal tests, 4 guided lifecycle tests and 1 workspace-ID
  transition test. Build duration was 1m48s, not a robot timing measurement.

Initial fixture failures are recorded separately, not treated as defects: the worker used a wrong
metadata filename and assumed runCurrent could finish real IO. Codex used `.ares/project.json`,
observed state/barrier completion instead of scheduler idleness, and joined owned resources before
cleanup. A coordinator Mockito null-matcher error was corrected before accepting baseline evidence.
The trivial project-key equality test was dropped; it merely restated the implementation.

Validation uses JDK 17, released ARESLib 19.1.4, and serialized Analytics Gradle outputs. Command
from `ARES-Analytics/`:

```powershell
.\gradlew.bat :app:test --tests '*TuningPromotionContextAuditTest' `
  --tests '*TuningPromotionLifecycleAuditTest' --tests '*WorkspaceProjectSaveAuditTest' `
  --tests '*WorkspaceProjectContextAuditTest' --tests '*MainViewModelWorkspaceTransitionTest' `
  --tests '*ExternalTuningProposalAuditTest' --tests '*TuningLiveRequestAuditTest' `
  --tests '*GuidedExperimentLifecycleAuditTest' --tests '*ProjectSessionTest' `
  --no-parallel --console=plain
```

Evidence is retained in coordinator worktree `build/agy-workspace-check/`: exact prompts/worker logs
remain in the respective worker `build/gemini-audit/` directories; coordinator baseline, candidate
and corrected Gradle logs/JUnit, CI classification, source-policy and scoped ledger checks are kept
separately. No version or release artifact changed. Existing Analytics app CI owns these tests.

## Delegation efficiency and remaining boundaries

| Worker | Conversation | Turns | Successful reads / distinct paths | Tool errors |
| --- | --- | ---: | ---: | ---: |
| Workspace | `354b5dba-47b1-4d35-b40c-8771cf747919` | 2 | 21 / 9 | 0 |
| Promotion context | `3e29a629-8229-417c-9bcc-f9feed21d86c` | 1 | 19 / 7 | 3 |

Reported cumulative durations were about 422 s and 244 s. Metrics deduplicate conversation/step
IDs and are preserved in `worker-efficiency-final.json`. Repeated reads include verification;
the prompt read budget remains advisory. Short briefs and a targeted correction narrowed the work,
but independent review still found important omissions. No measured Codex quota saving is claimed.

These are desktop JVM, real file/session and Compose scene checks. No new native Studio window,
live robot link or physical controller was exercised in this batch. The earlier rendered operator
journey remains evidence for its recorded source, not proof of this new folder-switch interaction.
No loop-time or allocation improvement is claimed. Hardware timing/electrical validation remains
unavailable. A useful next boundary is other asynchronous ProjectSession callers: determine whether
their cancellation/request ownership can similarly allow obsolete selection changes before adding
guards. This is a review lead, not a claim that those callers are defective. Keep the goal active.
