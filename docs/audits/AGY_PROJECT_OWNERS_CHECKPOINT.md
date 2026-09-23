# Local project-owner checkpoint

Date: 2026-09-23. Baseline: `484f29b295327134519b68800ba9abac0a0bae7b`.
Continues the [editor session checkpoint](AGY_SESSION_CALLERS_CHECKPOINT.md).
All changes remain local. No library, generator, schema, release version or archive changed.

## Confirmed behavior and accepted fixes

Delayed work in Project Identity, Path Planner and Subsystem Builder could restore an obsolete
shared ProjectSession selection after a successor project had loaded. This was reproduced on
unchanged baseline production with real project files and the real session behind a blocking spy.
The demonstrated consequence is incorrect selection/revision ownership, not a demonstrated write
to the wrong project's files. These are practical correctness defects, not measured performance bugs.

- Project Identity checks the active coroutine, model scope, generation and workspace inside the
  session selection lock. The same protection covers the delayed refresh after initial metadata
  creation. A completed write to the original project remains on disk when selection changes.
  Revision-bound saves and identity repair already have conflict protection; this batch does not
  claim that all repair paths were newly fixed.
- Path Planner supplies its generation/path check to RoutinePersistenceService. The service checks
  the actual IO coroutine and request inside the session lock, and checks activity before standalone
  document loading. Cancellation propagates without publishing a false load error.
- Subsystem Builder checks the scope, generation and individual asynchronous reload job inside
  the session lock. Both closing an old model and cancelling just its returned reload job prevent
  a delayed snapshot from retargeting the shared selection. Existing draft comparison and save
  protections remain intact; the saved-draft regression was already passing on baseline.

Public model constructors remain unchanged. These caller fixes use the existing guarded snapshot
contract; no robot enable, lease, estimator or output policy changes.

## Independent review and validation

Three AGY workers owned disjoint source/test paths. Codex reviewed the complete diffs and tests,
reproduced each accepted scenario on unchanged production, and corrected the submissions:

- Identity used a suspend coroutine-context property inside a synchronous callback. Codex captured
  the context in the enclosing suspend function before passing the guard.
- Planner's redundant job capture was removed, cancellation is rethrown, and a test's invalid team
  identifier was corrected to the real metadata format.
- The initial subsystem submission invented metadata/snapshot APIs and had inadequate barriers and
  cleanup. A targeted worker correction replaced those fixtures with actual DTOs, bounded waits,
  discriminating saves and failure cleanup, and covered cancellation of an individual reload.
  Codex supplied a missing coroutine import. No invalid-fixture failure is counted as a product bug.

The unchanged existing baseline passes **52 tests**. The final 11 new tests against unchanged
production produce **7 intended assertion failures and 4 control passes**, with no errors or skips.
The failures cover three identity scenarios, two planner scenarios and two subsystem scenarios.
Controls cover normal identity/footprint saves, absence of a false cancellation error and retention
of a saved subsystem draft. The latter two are preserved behavior, not newly discovered defects.

The reviewed combined suite passes **141 tests**, with zero failures, errors or skips. It covers
identity concurrency/repair/validation, routine and subsystem authoring, the shared session, and
prior field, drivebase, workspace and tuning ownership regressions. A final invocation-only cleanup
keeps PathPlannerViewModel at its existing 750-line ceiling; all **11 new regressions/controls pass
again** on that final source. The inventory has zero violations; no ceiling was relaxed.

Validation uses JDK 17 and released ARESLib 19.1.4, with serialized Gradle outputs:

```powershell
.\ARES-Analytics\gradlew.bat -p ARES-Analytics :app:test `
  --tests '*ProjectIdentitySessionOwnershipAuditTest' --tests '*PathPlannerSessionOwnershipAuditTest' `
  --tests '*SubsystemSessionOwnershipAuditTest' --tests '*ProjectIdentityConcurrencyAuditTest' `
  --tests '*ProjectIdentityRepairAuditTest' --tests '*ProjectIdentityValidationAuditTest' `
  --tests '*ProjectIdentityViewModelTest' --tests '*PathPlannerMetadataAuditTest' `
  --tests '*RoutineBuilderViewModelTest' --tests '*SubsystemGeneratorViewModelTest' `
  --tests '*ProjectSessionTest' --tests '*FieldEditorSessionOwnershipAuditTest' `
  --tests '*DrivebaseSessionOwnershipAuditTest' --tests '*TuningPromotionContextAuditTest' `
  --tests '*WorkspaceProjectContextAuditTest' --tests '*WorkspaceProjectSaveAuditTest' `
  --no-parallel --console=plain
```

Generated consumer evidence from `484f29b29` is reused: one generic FTC roundtrip and two fresh
nested builds with 32 TeamCode/simulated-IO tests each. This batch changes asynchronous editor
ownership, not generation or robot runtime code; those consumer builds were not repeated. The
141-test suite exercises the affected authoring/save callers. Existing app CI runs `:app:test`
and `:app:consumerRoundtripTest`; changed-path classification selects app, with the inventory
conservatively selecting shared/gateway too. Unchanged full shared/gateway suites were not rerun.
Source-policy passes, including guidance, links, inventory and release identity. No remote CI ran.

Evidence: coordinator `build/agy-project-owners/` contains existing/new baseline JUnit and logs,
combined fixed results, final guard results, worker metrics, remaining-caller notes, process
inventory, CI classification and scoped ledger verification. Worker prompts, streams and corrections
remain in their respective ignored `build/gemini-audit/` directories. Accepted source contains
coordinator corrections; do not overwrite it with worker files.

## Delegation efficiency and remaining work

All three workers ran concurrently using `gemini-3.8-flash-high --effort high`, with source tools
only. All completed substantive responses; none remains running. The coordinator owned commands,
validation and integration through normal permissions. No permission rules were broadened.

| Worker | Successful reads / distinct paths | Tool errors | Turns | Reported cumulative duration |
| --- | ---: | ---: | ---: | ---: |
| Identity | 18 / 7 | 0 | 1 | 238 s |
| Planner | 19 / 6 | 2 | 1 | 247 s |
| Subsystem | 16 / 6 | 6 | 2 | 373 s |

Counters deduplicate conversation/step IDs. Tool errors were missing-file reads or an unmatched
replacement, not shell escalations. Relative to the previous three-worker batch's 23/24/30 reads,
read counts and correction turns decreased, but tasks differ; this is not a controlled speedup
measurement. Reads still exceed the advisory exploration budget. Full reported usage counters,
including cache/thinking, are retained separately and do not establish Codex quota savings.
Keep exact path manifests and require baseline-compatible DTO fixtures and cleanup in the brief.
Do not fill worker slots with redundant assignments.

Next bounded reproduction leads are RobotProjectReadinessService/RobotStudioViewModel inspection
and SuperstructureStudioViewModel reload/post-save ownership. Shared-session injection and
asynchronous callers were verified, but no defect is yet claimed there. ControlsEditor reload is
synchronous in the inspected paths; SubsystemProjectPersistence.refresh has no production callers
found. Generation/execution snapshots are intentional authorization boundaries and need caller
evidence before applying passive-load policy. Cosmetic removal or blanket caller rewrites are deferred.

No new native Studio window or hardware was exercised in this batch. Prior rendered folder-switch
evidence remains bound to `e93d927e8`; it does not verify these new patches. No robot loop-time,
allocation or sensor-latency improvement is claimed. Build/worker durations are desktop activity,
not robot performance. Keep the autonomous goal active with local-only publication restrictions.
