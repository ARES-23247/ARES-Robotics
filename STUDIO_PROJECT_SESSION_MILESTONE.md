# Studio Project Session Milestone

## Outcome

ARES Robotics Studio now has one application-scoped `ProjectSession` for the selected robot project. It owns the canonical project root, target platform, immutable assembled project snapshot, content revision, loading/error state, revision-safe controller-document writes, reviewed removals, and authorization of generate, verify/build, simulate, and deploy commands.

This phase changes Studio ownership and coordination only. It does not change generated robot behavior, FTC/FRC runtime semantics, canonical document formats, or physical-validation claims.

## Ownership

- `ProjectSession` owns selection, stable content identity, cached raw/effective snapshots, reloads, stale-edit detection, and session state.
- Existing document repositories still own codecs, atomic replacement, immutable history, recovery copies, and per-document review hashes.
- `ProjectExecutionCoordinator` derives league and simulator product from the same effective project used by authoring before it delegates to `ProcessManagerService`.
- `ServiceRegistry` owns the single long-lived session and coordinator.
- Compose rendering remains state-plus-callback based at the reusable `RobotStudioWorkspace` boundary. Feature ViewModels remain adapters while their remaining repositories are migrated incrementally.

Run-scoped evidence, local state, verification results, history, recovery, drafts, and backups are excluded from canonical content identity. Recording a hardware review or verification result therefore cannot make an unchanged robot definition look stale.

## Migrated vertical slice

The controller editor is the representative complete mutation path:

1. It loads one session snapshot and retains its `ProjectSessionRevision`.
2. It edits immutable UI state.
3. Save submits typed controller-profile and control-scheme documents with the expected revision.
4. The session rejects stale revisions or external canonical byte changes before the first write.
5. Repository-owned atomic/history behavior remains intact.
6. The session reloads one effective project snapshot after the mutation.

Path Planner, Subsystem Builder, Superstructure Studio, and project readiness now consume the shared read snapshot in production. Successful subsystem and superstructure repository writes explicitly refresh the shared snapshot. Their existing per-document content-hash protections remain in force until their mutations move behind typed session commands.

All top-level Studio build, simulation, and deploy actions now pass through `ProjectExecutionCoordinator`. An architecture regression test rejects direct process launches from `MainScreen` and verifies that production authoring ViewModels receive the shared session.

## Verification evidence

- Focused session, controller, readiness, subsystem, superstructure, and architecture tests passed.
- Full Analytics `test` passed against ARES `10.0.0-rc.sim-product.1` from the isolated release-validation repository.
- Two real Compose Desktop launch cycles passed. Each reached the exact-HWND settled diagnostic, captured rendered Studio UI at 1424×861, posted `WM_CLOSE`, cleaned its isolated runtime classpath, and left no `com.ares.analytics.MainKt` process.
- Captures:
  - `ARES-Analytics/build/diagnostics/project-session-cycle-1.png`
  - `ARES-Analytics/build/diagnostics/project-session-cycle-2.png`
- Offline NT4 connection refusal and optional Google Drive sign-in warnings were observed as expected; neither affected the rendered window.
- No physical robot was used or claimed.

## Rollback

The work is isolated on `codex/studio-project-session-refactor` in the workspace root and Analytics repository. Reverting the signed milestone commits restores the prior independent-loader behavior without changing project documents or generated robot code.

## Remaining migration debt

This phase intentionally avoids a high-risk package move or a cosmetic UI rewrite. Remaining work is explicit:

1. Move the persistence/read implementation currently named under `viewmodel/project` into a service or domain-owned package without changing formats.
2. Add typed session mutations for routines/autonomous catalogs, subsystems, superstructures, drivebases, identity, fields, tuning profiles, and hardware-review evidence.
3. Replace feature-owned generation callbacks with structured project commands while preserving starter preview/apply workflows.
4. Split large feature ViewModels into use cases and immutable presentation adapters.
5. Reduce `MainScreen` to navigation and composition after command/status routing is fully session-owned.
6. Keep FTC and FRC simulator products, lifecycles, hardware adapters, and verification matrices separate while sharing only platform-neutral contracts.

The next refactor phase should migrate canonical persistence out of the ViewModel namespace and put every authoring mutation behind typed, revision-bound commands. That completes the ownership boundary before decomposing the largest feature ViewModels.
