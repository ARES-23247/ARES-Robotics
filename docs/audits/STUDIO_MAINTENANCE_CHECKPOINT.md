# Studio maintenance checkpoint — 2026-09-23

Base: `3033daf0a3a6ace7d583fc3315090f4e2a8436c7`. Accepted source is the commit containing
this report. This user-authorized maintenance batch follows the completed AGY campaign; it does
not reopen that goal. Changes remain local. No library, robot runtime, schema, generator,
release version, packaged resource or CI workflow changed.

## Accepted cleanup

- Extracted PathPlanner's project-refresh selection and state projection into
  `RoutineProjectRefreshProjection.kt`. The ViewModel is 703 lines instead of 750; the helper
  is 76 lines. Coroutine lifetime, path ownership and generation guards remain in the ViewModel.
  Project paths still bind before loading-complete publication, and preview recalculation follows
  publication. The helper preserves the later dimension read and state-update retry behavior.
- Removed the unused internal `SubsystemProjectPersistence.refresh` method and unused imports.
  Its two production consumers do not call it. Save/removal/recovery behavior remains intact.
- Consolidated canonical Drivebase project creation, metadata and bounded scope cleanup across
  three regression suites. All original 16 test names and 109 scenario assertions remain;
  race barriers, repository-only cases, dispatchers and tuning-profile setup stay scenario-specific.
  Shared setup now cleans up if fixture creation fails before returning to the caller.
- Added two focused fixture-lifecycle regressions: joining waits for an outstanding owned child,
  and cancellation completes the owned child's cleanup and returns within its deadline.

## Independent correction and validation

Two isolated external workers used `gemini-3.8-flash-high`, effort `high`, and source-file tools:

| Scope | Conversation | Successful reads / distinct paths |
| --- | --- | --- |
| PathPlanner extraction | `979c90d5-a4ba-4957-9735-9e68114aed1b` | 17 / 10 |
| Drivebase test fixtures | `c3458a29-ab64-4504-8e0d-ac2605c3d771` | 16 / 7 |

Codex simplified the extraction's intermediate data object and restored validation inside the
state update and the original timing of the fallback dimension read. The fixture draft used a
nonexistent DTO name, duplicated setup factories and introduced a coroutine receiver error:
inside `withTimeout`, unqualified `coroutineContext` refers to the timeout's job. This made cleanup
join itself and the child-join helper inspect the wrong children. Capturing the fixture job before
entering the timeout restores the intended ownership. These were maintenance regressions, not
newly discovered production defects. Worker claims of zero timing variance were not accepted.

- Initial combined run: 230 tests, 16 failures in fixture cleanup, zero errors/skips. Raw XML and
  the initial helper are retained. The original test assertions are not treated as a passing suite.
- Corrected combined run: **232 passed, zero failures/errors/skips**, including both new lifecycle
  checks. JDK 17, released ARESLib 19.1.4, Studio 7.0.64 dependency mode:

  ```powershell
  ./ARES-Analytics/gradlew.bat -p ARES-Analytics :app:test --tests '*Drivebase*Test' --tests '*PathPlanner*Test' --tests '*Routine*Test' --tests '*Subsystem*Test' --tests '*ProjectSessionTest' --no-parallel --console=plain
  ```

- Independent source comparison confirms identical selection/publication expressions after parameter
  substitution, the original binding order, and retained scenario assertions. It supplements execution;
  it is not a whole-file correctness claim. Source-size inventory and policy checks are recorded with
  the final checkpoint. Source-size limits were not changed.
- Existing changed-part CI continues to select Studio tests and generated-consumer checks. The size
  inventory path conservatively also selects shared/gateway scopes. Their unchanged full suites and
  generated consumers were not rerun locally for this editor/test-only change. No remote CI ran.

Evidence is in coordinator `.codex-validation/agy-audit-coordination/build/studio-maintenance/`:
initial/final JUnit and summaries, command logs, preservation checks, worker results, source identities,
source policy, scoped ledger changes and local integration. Worker prompts, drafts and logs remain in
`gemini-cleanup-planner` and `gemini-cleanup-fixtures` under `.codex-validation/`.

## Retired review worktrees and restoration

Nine completed worktrees had no tracked changes or unmerged commits. Their **61 untracked/ignored
reports and logs** were archived with per-file SHA-256 verification before removal. Fresh checks
confirmed unchanged source, unchanged evidence and no process referencing each target. Their
branches remain intact. Archives are local at `.codex-validation/archived-agy-reviews/<name>.zip`:

- `gemini-completion-editors`
- `gemini-completion-tuning`
- `gemini-delegation-efficiency`
- `gemini-drivebase-save-storage`
- `gemini-drivebase-save-ui`
- `gemini-readiness-recovery-review`
- `gemini-readiness-ui-review`
- `gemini-session-caller-review`
- `gemini-session-entrypoint-review`

Each archive contains `ARCHIVE-MANIFEST.json` with its original HEAD, branch, file list and hashes.
Restore by recreating a worktree at that retained branch/HEAD and extracting the evidence files
into it; the manifest itself is archival metadata. Coordinator `archived-reviews.json` also binds
the zip hashes; `retired-worktrees.json` records successful removals. Historical reports' references
to these original worktree paths now resolve through these archives. Source-modified worktrees,
the untracked prototype-test worktree and unrelated worktrees were preserved. The two new worker
checkouts were also retained, leaving 45 registered worktrees from 52 before this batch.

## Bounded follow-up

This completes the selected cleanup. Other large editors can be extracted when their next behavior
change warrants it. A generic cross-editor operation framework remains deferred: dirty drafts,
save serialization, passive reloads and reviewed mutations have distinct contracts. Existing
guards remain explicit. There is no new runtime-speed, quota-saving, native-rendering or hardware
validation claim, and this batch does not start another audit or publication.
