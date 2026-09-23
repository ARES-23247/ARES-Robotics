# Local editor session checkpoint

Date: 2026-09-23. Baseline: `e93d927e84438e6c6c1171aec1b4913ee9ed74a9`.
Continues the [workspace context checkpoint](AGY_WORKSPACE_CONTEXT_CHECKPOINT.md).
This batch is local only; no publication, protected-main merge or release/version change.

## Confirmed defects and accepted changes

An obsolete Field Editor or Drivebase Builder load could restore the previous shared
ProjectSession selection after another project had loaded. Cancelling the previous model's scope
does not interrupt synchronous work already entering the session. Local view-state checks alone
do not protect the session's mutable selection. The consequence reproduced here is selection
reversion and invalid revision ownership, not a demonstrated write to the wrong project's files.

Both editors now track and cancel superseded loads and check coroutine activity/request generation
inside the existing session selection lock. They retain their public constructor interfaces and
normal edit/save behavior. Field loading also avoids continuing obsolete document/image work.
No shared-library or robot control contract changed.

Drivebase Builder additionally allowed an older completed read to overwrite a newer reload's
draft and revision. Its load generation now guards state publication as well as session access.
The regression changes real on-disk track width from 0.36 m to 0.42 m while holding delivery of
the first read. This distinguishes obsolete data from two equivalent reads of the same file.
Dirty drafts still require the existing discard confirmation before reloading.

These defects exist in the unchanged baseline; they were not introduced by the new worker patches.
The earlier session guard overload provided a way to fix these callers but did not automatically
guard them. Completed disk writes are not rolled back by subsequent cancellation.

## Independent validation

Codex reviewed both complete production diffs and corrected the proposed tests before acceptance.
The initial worker tests depended on a new dispatcher constructor argument, had incorrectly timed
replacement construction, could block both reloads, used a nonexistent UI document property, and
did not reliably release/join resources on assertion failure. Those issues were corrected locally.
The first same-project test was also non-discriminating: delaying a read before the snapshot meant
both reloads observed the new bytes. It was strengthened to hold the old result after the read.

Final new regressions against **unchanged production**: **7 tests, 4 intended assertion failures,
3 passes**, no skips. Failures cover same-model field selection, cancelled field/drivebase model
replacement, and an obsolete drivebase draft/revision. Positive controls cover actual field save,
reviewed drivebase save, and absence of a false cancellation error. The cancellation control was
already passing and is not counted as a separate defect. An earlier fixture timeout is retained
separately and is not a product finding.

The fixed combined suite passes **86 tests**, zero failures, errors or skips. This includes the
7 new regressions/controls, 26 drivebase authoring checks, 33 field interaction/transaction/transform
checks, 12 session checks, 3 workspace checks and 5 prior tuning session checks. Duration 1m37s
is a desktop build/test duration, not a robot loop measurement.

The existing generic FTC consumer roundtrip also passes: **1 outer integration test**, with
**32 nested TeamCode/simulator tests in each of two fresh verification builds**, no failures,
errors or skips. It exports/reopens authored documents, preserves USER-OWNED extensions, checks
generation determinism, and exercises generated code with simulated IO. Command: the same Gradle
invocation using `:app:consumerRoundtripTest --tests '*GenericStarterConsumerRoundtripIntegrationTest'`.
The retained operation-3/4 XML timestamps belong to this run, not an earlier checkpoint.

Source-policy passes, including guidance, Markdown links and the regenerated size inventory;
no size ceiling was relaxed. Existing app CI runs the new tests through `:app:test` and also
runs `:app:consumerRoundtripTest`. Changed-path classification selects Analytics app; the inventory
path conservatively selects shared/gateway too. No CI redesign, library rebuild or remote CI run
was needed. Unchanged full shared/gateway suites were not rerun locally.

Validation uses JDK 17, released ARESLib 19.1.4 and serialized Analytics Gradle outputs. From the
repository root:

```powershell
.\ARES-Analytics\gradlew.bat -p ARES-Analytics :app:test `
  --tests '*FieldEditorSessionOwnershipAuditTest' --tests '*DrivebaseSessionOwnershipAuditTest' `
  --tests '*FieldEditorTransactionsTest' --tests '*FieldEditorInteractionTest' `
  --tests '*FieldEditorTransformsTest' --tests '*DrivebaseAuthoringTest' `
  --tests '*ProjectSessionTest' --tests '*WorkspaceProjectSaveAuditTest' `
  --tests '*WorkspaceProjectContextAuditTest' --tests '*TuningPromotionContextAuditTest' `
  --no-parallel --console=plain
```

Coordinator evidence is retained under `build/agy-session-callers/`: baseline variants, final
JUnit/logs, GUI manifest/captures/hashes/shutdown proof, worker metrics, scoped ledger verification
and policy/CI classification. The initial and revised worker submissions remain in their worktrees;
do not copy them over the independently corrected coordinator tests.

## Rendered folder-switch verification

The prior checkpoint's native UI gap is now closed for the selected offline journey. Studio ran
from baseline `e93d927e8`, using an isolated desktop home and two disposable copies of the released
Lightbot 3.0.66 archive. Project B's fixture heading derivative gain was set to 0.12 before launch;
Project A retained 0.08. Through the visible Profile Settings form, the project folder changed from
A to B while retaining workspace ID `FTC-23247-Lightbot-2026`.

Exact-window captures show A's 0.08 value, the edited folder, Robot Studio's selected B path, and
B's 0.12 value plus changed profile fingerprint. The persisted workspace file confirms the same
ID with B's path. All 24 canonical fixture files remained byte-identical to their pre-interaction
hashes. Calibration remained disarmed and disconnected; no robot or simulator was linked.
Native WM_CLOSE exited owned Studio PID 42608 normally. Its wrapper, control port 49324 and
runtime snapshot were verified gone. Other processes were preserved.

This UI evidence validates the prior folder-switch fix. The new field/drivebase changes were not
present in that GUI run; their evidence is the real file/session regression suite above.

## Delegation efficiency and remaining scope

Three isolated AGY workers ran concurrently with `gemini-3.8-flash-high --effort high`. Each
completed an initial turn and a targeted correction. Codex owned all commands and integration.

| Worker | Successful reads / distinct paths | Tool errors | Reported cumulative duration |
| --- | ---: | ---: | ---: |
| Field session | 23 / 7 | 0 | 707 s |
| Drivebase session | 24 / 6 | 0 | 666 s |
| Remaining callers, read-only | 30 / 12 | 1 missing report read | 613 s |

Metrics deduplicate conversation/step IDs across logs. Final reported token counters, including
cache/thinking counters, are retained separately; they are not comparable billing units or a
measurement of Codex quota saved. Repeated reads include verification, but exploration exceeded
the advisory budgets. The useful improvement for subsequent briefs is to require baseline-compatible
regressions, exact model/DTO fixtures and cleanup on failure from the outset. Additional workers
should have distinct useful scopes; review and serialized compilation remain coordinator work.

The read-only worker initially classified fixed-path models and post-save refreshes as protected.
Codex rejected that inference: UI generation checks, immutable per-model paths and completed disk
writes do not prove ownership of a shared session. The revised report identifies static leads in
ProjectIdentity inspection/post-save refresh, PathPlanner/RoutinePersistence refresh, Subsystem
Generator async reload and Superstructure loading. These remain **unreproduced review leads**;
ControlsEditor and the SubsystemProjectPersistence helper need actual caller-context assessment.
The next bounded investigations are ProjectIdentity and PathPlanner/RoutinePersistence, not a
blanket rewrite of all callers or a new audit-every-file pass.

No loop-time, allocation or sensor-latency improvement is claimed. These are desktop checks,
not physical robot or target-controller timing evidence. Keep the autonomous goal active.
