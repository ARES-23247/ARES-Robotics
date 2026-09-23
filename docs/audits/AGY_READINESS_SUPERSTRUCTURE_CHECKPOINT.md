# Local readiness and superstructure checkpoint

Date: 2026-09-23. Baseline: `fdf2c7e682de242912187b324add2de3b9cef4ef`.
Continues the [project-owner checkpoint](AGY_PROJECT_OWNERS_CHECKPOINT.md).
All work remains local; library, generator, schema, release versions and archives are unchanged.

## Confirmed defects and accepted behavior

Robot Studio readiness inspection could restore an obsolete shared ProjectSession selection after
a successor inspection completed. Runtime events could also republish cached readiness during a
new inspection, prematurely clear loading, restore the prior project path, or erase a later
inspection error. The same-project refresh path is used by MainScreen on canonical-content and
build changes; runtime updates arrive independently for build/deploy/link/simulation state.
Workspace replacement cancels old model scopes but cannot interrupt synchronous snapshot work
already entering the session. Both caller relationships were checked in current production.

Readiness now checks coroutine/request ownership inside the existing session lock, propagates
cancellation, clears cached evidence at each refresh, and publishes runtime updates only with
current completed evidence. Generation/configuration fields read on IO have explicit visibility.
Normal invalid-project diagnostics remain intact. No robot execution gate was weakened.

Superstructure Editor had several related defects: cancelled old loads could restore shared
selection, delayed reloads could replace a completed save, documents could adopt a revision from
a different snapshot, and delayed save results could erase newer edits or replace a newer reload.
Forced reload also erased edits made after the discard/reload request. Its screen intentionally
retains the model during navigation, and editor operations remain available while IO is pending.

Loads and saves now retain the revision returned with their actual result. Request generations
guard selection and result publication. Accepted saves invalidate prior reload delivery; newer
reloads own subsequent UI publication. Reload preserves later drafts, selections, review state and
input-validation errors. Saves preserve newer edits and completed disk/history writes. Explicit
platform selection and standalone mode remain unchanged. The reproduced revision mismatch is not
claimed as an observed wrong-project disk write; the corrected stale-save rejection is checked.

These production defects were reproduced on unchanged baseline source. Separately, an existing
architecture test still searched for `remember(config.id)` after audit commit `e93d927e8` introduced
the project context key. This is an audit-introduced stale test assertion, not a runtime regression.
It now checks every affected constructor's project key and shared-session injection. The existing
Compose test verifies actual same-ID folder cancellation and preference continuity.

## Independent review and reproduction

Codex reviewed both production patches and every new regression. The first submissions required
substantial correction before acceptance:

- Readiness tests joined the permanent `stateIn` collector and would never finish. They now join
  only request work while the model is active, and cancel/join the entire scope during cleanup.
  The worker's replacement used `Sequence.joinAll`; Codex corrected it to a collection. A proposed
  failure fixture threw from snapshot, which the service converts into diagnostics; Codex moved
  it to the actual propagating run-database boundary. No fixture timeout is counted as a defect.
- Readiness cancellation handling was narrowed, an unnecessary Java overload was removed, and
  same-project pending/failed refresh cases were added. Worker claims about measured wasted work
  and database exception wrapping were rejected.
- Superstructure tests used a nonexistent field-config package and incorrect positional DTO
  constructor. Codex corrected these and moved session cleanup after cancellation/join. Worker
  borrowing of another selection's target platform was rejected, as was an unused helper.
- Codex strengthened superstructure generations beyond the revised worker patch: old save results
  cannot combine older documents with a newer revision, and forced reload cannot clear validation
  errors for later edits. Two additional regressions exercise those boundaries. A missing coroutine
  import and nullable callback inference were corrected; the initial compile failure is retained.

Existing baseline: **61 tests, 60 passes and the stale architecture assertion failure**. The
architecture correction plus existing Compose workspace check passes **17 tests**.
New baseline reproduction: 12 tests produce **10 intended assertion failures and 2 normal controls**.
The additional delayed-save test and strengthened forced-reload test both fail at their intended
assertions. Across the final **13 distinct new tests**, this establishes **11 failing scenarios and
2 normal controls** on unchanged production, with no errors or skips. Scenario counts are not
counts of independent root causes or complete-file reviews.

The final combined suite passes **121 tests**, zero failures, errors or skips. It covers the 13
new scenarios, readiness evaluation, superstructure persistence/preview/authoring, project
architecture/session ownership, controls and prior field/drivebase/identity/planner/subsystem/
tuning/workspace regressions. JDK 17 and released ARESLib 19.1.4 were used; Gradle outputs were
serialized. From the repository root:

```powershell
.\ARES-Analytics\gradlew.bat -p ARES-Analytics :app:test `
  --tests '*RobotStudioSessionOwnershipAuditTest' --tests '*SuperstructureSessionOwnershipAuditTest' `
  --tests '*RobotProjectReadinessServiceTest' --tests '*RobotStudioModelTest' `
  --tests '*SuperstructureProjectRepositoryTest' --tests '*SuperstructurePreviewSessionTest' `
  --tests '*SuperstructureInterlockDescriptionTest' --tests '*SuperstructureStudioScreenTest' `
  --tests '*ProjectModelArchitectureTest' --tests '*ProjectSessionTest' `
  --tests '*ControlsEditorViewModelTest' --tests '*FieldEditorSessionOwnershipAuditTest' `
  --tests '*DrivebaseSessionOwnershipAuditTest' --tests '*ProjectIdentitySessionOwnershipAuditTest' `
  --tests '*PathPlannerSessionOwnershipAuditTest' --tests '*SubsystemSessionOwnershipAuditTest' `
  --tests '*TuningPromotionContextAuditTest' --tests '*WorkspaceProjectContextAuditTest' `
  --tests '*WorkspaceProjectSaveAuditTest' --no-parallel --console=plain
```

The generic FTC consumer roundtrip passes **one outer test and 32 nested TeamCode/simulator tests
in each of two fresh builds**, with no failures, errors or skips. It checks real export/reopen,
deterministic generated code, USER-OWNED source preservation and robot feedback through simulated
IO. This is the existing consumer integration scenario, not a rendered superstructure UI test.
Command: the same Gradle invocation with `:app:consumerRoundtripTest
--tests '*GenericStarterConsumerRoundtripIntegrationTest' --no-parallel --console=plain`.
All retained nested XML timestamps were checked against this run's start time.

Source-policy passes, including guidance, local links, source-size inventory and release identity.
No source-size ceiling was relaxed. Existing changed-path CI selects app tests and consumer
roundtrip; the inventory path conservatively also selects shared/gateway. Those unchanged full
suites were not rerun locally, and no remote CI ran. No CI redesign was needed.

Coordinator `build/agy-readiness-superstructure/` retains baseline/architecture/final JUnit and
logs, the initial candidate compilation failure, fresh consumer XML, caller disposition, worker
metrics and scoped ledger/CI/policy verification. Complete prior ledger records are preserved;
only reviewed records changed. Original assignments, streams and corrections remain in each
worker's ignored `build/gemini-audit/`. All worker and test handles reached terminal results.

## Remaining entrypoint review

The third worker performed a read-only review of ControlsEditor reload, SessionProjectGenerator,
ProjectExecutionCoordinator and the unused SubsystemProjectPersistence.refresh helper. Current
inspected UI entrypoints execute synchronously or intentionally select a project for a command;
no obsolete asynchronous result or other defect was reproduced there. No patch was warranted.

Two worker claims were rejected. SubsystemBuilderPreviewPlanner directly invokes codegen and the
starter reconciler; it does not call SessionProjectGenerator.previewSubsystemStarters. Execution
snapshot acquisition is under the session lock, but subsequent validation and process dispatch
are outside it. This review therefore does not prove atomic authorization/dispatch or universal
thread safety. Exact caller notes and the original report are retained separately. Unused helper
removal and speculative lock/latency changes remain deferred.

## Delegation and limits

Three isolated workers used `gemini-3.8-flash-high --effort high` with source-file tools only.
All completed substantive responses. Codex owned commands, integration and acceptance through
normal permissions; no personal permission rules were broadened.

| Worker | Successful reads / distinct paths | Tool errors | Turns | Reported cumulative duration |
| --- | ---: | ---: | ---: | ---: |
| Readiness | 22 / 10 | 1 | 2 | 444 s |
| Superstructure | 50 / 10 | 4 | 2 | 619 s |
| Entrypoint review | 32 / 14 | 2 | 1 | 201 s |

Metrics deduplicate conversation/step IDs. Errors and repeated reads are retained in the local
worker metrics; the read budget was exceeded, especially for superstructure. This batch does not
support a claim of improved delegation efficiency or a Codex quota saving. Parallel source work
still required correction rounds and serialized compilation. Prefer tighter follow-up excerpts
and explicit test lifecycle examples; do not add redundant workers merely to fill slots.

No native Studio window or hardware was exercised in this batch. Prior rendered folder-switch
evidence remains bound to `e93d927e8`. These checks establish desktop model, persistence and
generated simulated-IO behavior, not physical safety or target-controller timing. No loop-time,
allocation or sensor-latency gain is claimed.

After the local checkpoint, the next bounded action is a rendered operator check of current
readiness refresh and superstructure save/reload behavior. Then reassess the documented practical
backlog; do not restart an audit-every-file campaign or blindly rewrite remaining snapshot callers.
