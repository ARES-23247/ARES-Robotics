# Project metadata updates and catalog restoration

Pass 200, 2026-09-12. This pass covers metadata creation/update/repair boundaries
and redundant work in autonomous catalog restoration. It started from the clean
local pass-199 commit `d59631d0`, which was concrete progress toward the ongoing
monorepo audit. ARESLib, release pins and the isolated candidate are unchanged.

## Confirmed fixes

`ProjectMetadataRepository.save` previously replaced any current file without a
review hash, history checkpoint or shared document lock. It now initializes a
missing identity through the same locked path used by reviewed creation. Existing
valid or corrupt metadata is preserved; replacement requires `saveReviewed` or
`repairReviewed` and the corresponding expected hash. Two simultaneous initializers
now have one winner. This intentionally tightens the method's contract.

The live non-session caller was the autonomous editor's robot-footprint update.
It now submits the hash of the metadata that the editor loaded. A concurrent edit
or corruption fails before replacement, and a successful footprint change retains
the previous canonical geometry in project history. Failures update the editor's
save status instead of escaping the coroutine or installing a false success state.
Cancellation is rethrown. The existing ProjectSession update path is retained.

Reviewed metadata saves and repairs reuse their validated encoded text for writing
and hashing. The previous current document is encoded once for both its canonical
hash and its history checkpoint. This removes repeated JSON encoding without
changing the schema normalization or content-hash format. The metadata shape guard
and library geometry/runtime validators remain in place.

Autonomous catalog restoration now supplies reference validation to the singleton
store's selected-snapshot restore operation. The callback receives the same decoded
document that will be restored; validation finishes before current/history writes.
This removes a separate full history listing/sort, selected-file reread and second
history scan. Explicit routine membership validation is retained, including when
there are no known routines. The subsequent schema validator no longer repeats
those membership lookups. No reference or intrinsic validation was relaxed.

These are structural reductions in work. No latency benchmark, robot-loop timing
or physical hardware performance improvement is claimed.

## Validation

Eleven initial new metadata/editor tests produced five failures. Unreviewed creation
overrode an existing identity; both concurrent initializers succeeded; stale and
corrupt metadata were overwritten by footprint updates; and successful footprint
updates omitted history. Six cases already passed, covering malformed values,
geometry constraints, reviewed-save/hash behavior, repair evidence preservation,
linked paths and invalid drafts. Those passing checks were retained.

The eight metadata tests exercise current/absent/corrupt files, concurrent creation,
canonical schema/hash normalization, no-op/stale/collision handling, exact binary
repair bytes, changed/missing/valid repair targets and outside file links. The
malformed-input matrix includes root shapes, fractional/overflow/string schema
values, nested objects/nulls, wrong booleans, zero/negative/oversized geometry and
an overflowing numeric exponent. Expected failures are ordinary validation errors,
not Kotlin null crashes. The library already enforced these numeric constraints;
this pass does not report them as newly found math defects.

Three actual PathPlannerViewModel tests use owned coroutine scopes and real disk
I/O to exercise stale metadata, corrupted metadata and successful history retention.
Each waits for loaded state and an explicit save outcome, checks persisted bytes
and UI state, observes uncaught coroutine failures, and joins its cancelled owner.

Eight catalog/restore tests cover normal entry/revision preservation, no-ops,
missing references with an empty routine set, corrupt routines/current/history,
repairing invalid current references from valid history, validation rejection
before writes and a historical file changed after validation. The snapshot callback
runs once and restores the exact content it validated. These are behavior tests;
no file-read count or benchmark is inferred from a test timer.

All 91 focused tests passed, including the 19 new tests, existing project identity,
project-session, routine-builder, repository and shared-history regressions.
Full Studio validation passed with 1,949 tests: 1,943 successful executions, zero
failures/errors and six opt-in/environment skips (three generated-project
integrations, native file chooser, performance baseline and physical dashboard).
Shared agent guidance and monorepo policy checks passed. Local Markdown links
were verified in 361 current documents; 38 explicitly historical records were skipped.

Evidence is under `ARESLib-Kotlin/build/audit-pass200-verified-evidence/`, including
baseline XML and copied focused/full result XML. Gradle used the unchanged local
`17.0.10-rc.271e2518a412` candidate.

## Scope and remaining work

The metadata repository, autonomous catalog repository, routine repository facade
and all three new test files were read fully. The library metadata and catalog
codec/validation implementations and the existing identity tests were inspected
to establish the contracts; no library source changed. The footprint-update branch
and connected refresh/identity call sites were inspected, not the entire large
PathPlannerViewModel.

The routine and autonomous repository facades are accounted for under a stable
project-filesystem contract. They do not provide a transaction spanning concurrent
changes to every referenced routine. The shared stores retain their partial status
for external writers, path replacement and interrupted publication/recovery.

Metadata read/shape/hash snapshot consistency, retired-format repair eligibility
and stricter raw-text edge cases remain open. In particular, the identity editor's
retired-format classification uses error-message text and deserves a separate
current-versus-retired corruption audit. Broader editor lifecycle, project-switch
and competing-intent behavior also remain open. No visible Studio window, physical
robot, release or deployment was exercised; the repository-wide goal remains active.

The file ledger now accounts for 2,892 tracked files: 1,067 reviewed, 153 partially
reviewed and 1,672 pending, with no stale fingerprints or orphaned records. These
are review accounting counts, not line or branch test coverage.
