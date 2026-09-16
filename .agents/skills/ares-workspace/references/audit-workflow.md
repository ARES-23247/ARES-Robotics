# Scoped audit workflow

Use this workflow for a requested audit. It does not start or resume the historical all-files
goal, authorize subagents, or authorize publication. Keep the user's current scope and stop criteria.
Use the existing [audit records and ledger contract](../../../../docs/audits/README.md);
historical reports describe their recorded source, not necessarily the current checkout.

## Establish the checkpoint

- Record the base commit, branch, dirty changes, selected products/files, and completion criteria.
  Inspect current protected main when choosing a new baseline; preserve any user-specified base.
  Isolate unrelated work and preserve running processes. Review pending changes before expanding scope.
- For a file-by-file campaign, freeze a tracked-file inventory with explicit dispositions for
  owned source, tests, configuration, documentation, generated files, upstream code, and resources.
  Account for additions, deletions, and changed dependencies during the campaign. Do not rewrite
  upstream code merely to meet local style or file-size preferences.
- Group related behavior into batches with integration checkpoints. File counts support accounting;
  they do not replace behavioral exit criteria. A file-by-file commitment still requires a
  disposition for every selected file and substantive review of every selected owned file.

## Coordinate authorized workers

- If the user authorized parallel agent work, assign bounded tasks and exact, disjoint writable
  paths before starting workers. Tell each worker that others share the repository and that they
  must preserve their changes. Workers may read dependencies outside their ownership; they report
  needed changes there to the coordinator instead of editing those files.
- Prefer separate worktrees for independent changes. If isolation or file ownership cannot be
  maintained, use parallel read-only reviews with one writer. The coordinator owns shared ledgers,
  shared contracts, CI, release manifests, and integration unless ownership is explicitly transferred.
- Keep concurrency within available capacity; do not recursively delegate without an agreed plan.
  Separate worktrees can still share publication repositories or processes. Serialize builds that
  write shared outputs, and schedule expensive suites centrally. Never stop another task's processes.
- Workers return reviewed paths and content identities, findings, edits, actual commands/results,
  and unresolved dependencies. The coordinator reviews the combined diff and validates integration;
  individual green worker runs do not establish that the combined result works.

## Review behavior and prioritize findings

- Inspect the entire file before claiming full review, including callers, units, frames, failure
  paths, resource ownership, and affected consumers. For asynchronous code, examine immediate
  completion, replacement, cancellation, and delayed cleanup: an old operation must not overwrite
  or close resources owned by its successor. Use controlled scheduling to reproduce relevant races.
- High impact: realistic incorrect behavior, unsafe outputs, ignored freshness/enable conditions,
  lost commands, or failed shutdown. Reproduce and fix these within scope; record whether evidence
  establishes a pre-existing defect, an audit regression, or unknown origin.
- Performance: optimize demonstrated timing, allocation, or resource problems against an explicit
  budget. Follow [measurement and failure diagnosis](../../ares-build-release/references/failure-modes.md).
- Lower priority: record speculative improvements, unrealistic extreme inputs, and cosmetic work
  for deferral. Avoid expanding the audit through opportunistic refactors.
- Validate math against independent reference results and known trajectories in realistic ranges;
  round trips or tests that reproduce the implementation can share the same error. Trace important
  startup, enable/disable, simultaneous commands, autonomous cancellation, stale/missing feedback,
  and partial preparation/cleanup failures through actual generated code and simulated IO as applicable.
- For changed bundled projects, follow the [authoring checks](../../ares-subsystem-authoring/references/review-checklist.md).
  Runtime tests and rendered Studio behavior establish different parts of the user journey.

## Preserve evidence and stop

- Use `docs/audits/file-reviews.json` and `scripts/audit_inventory.py` for file-review evidence.
  The maintainability ledger produced by `scripts/generate_codebase_ledger.py` measures file sizes;
  dirty paths, line counts, stored PASS labels, and green suites never prove that a file was reviewed.
- Keep review status, validation status, and finding resolution distinct. Follow the existing ledger
  schema; put reviewer/batch identity, finding status, commands, source/candidate identities, skips,
  results, and limitations in linked reports. Mark partial review with its actual boundaries.
- Reuse prior evidence only when its depth, scope, content identity, and dependencies still apply.
  Changed dependencies can invalidate validation of an unchanged file. Update the central ledger
  against stable integrated contents; refresh its self-fingerprint only after reviewing ledger edits.
- At integration checkpoints, run relevant library and consumer checks in dependency order. Preserve
  regressions in existing changed-part CI scopes; shared runtime, schema, or generator changes must
  select affected consumer checks. Reuse valid evidence instead of repeatedly running unrelated suites.
- Finish when the selected reviews/scenarios and applicable checks pass, no known high-impact defect
  remains in scope, performance evidence is assessed against budgets, and deferrals/unavailable
  hardware checks are explicit. State unmet criteria plainly; do not mark a failed check complete.
  Save exact source/worktree identity, completed batches, evidence locations, and remaining actions
  when pausing. Do not automatically start another audit pass.
