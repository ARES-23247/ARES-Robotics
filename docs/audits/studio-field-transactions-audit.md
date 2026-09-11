# Studio field transactions audit - pass 153

Read all of `FieldEditorTransactions` and its existing tests: bounded undo/redo history,
edit grouping, clipboard selection, identity assignment, per-shape cloning and snapshot
application. No undo/redo correctness defect was established in the exercised contracts.

Copying multiple AprilTags previously created a fresh sequence and searched from ID 1
for every copied tag. The search now carries its next candidate forward. Previously
examined IDs need not be revisited, and the per-tag sequence object is removed. The
lowest available positive ID rule and existing-tag protection are preserved. No measured
speedup or allocation-free clipboard claim is made.

Three added tests cover redo round trips and branch invalidation, reset/empty history,
invalid history capacity, sparse occupied tag IDs with multiple copies, duplicate versus
clipboard ownership, clipboard reset, circle/polygon translation, game-piece type and
rotation preservation, waypoint heading preservation, unlocking copies, new selections,
append semantics and restoring authored snapshots without overwriting grid settings.
Existing tests cover bounded eviction, coalesced edits and rectangle/tag cloning.

The production file remains partial for invalid/nonfinite grid offsets, cross-category
identity assumptions, mutable-list ownership and concurrent/cross-project clipboard use.
These helpers are not independently thread-safe; this pass does not certify their caller
serialization or persistence transaction boundaries. No before-fix failure is claimed
for the tag-allocation optimization; source inspection establishes the redundant search.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass153-verified-evidence/`.
Candidate remains `17.0.3-rc.100852e472fb`. All changes remain local; no rendered UI,
external service, live simulator or physical robot operation was performed.

Validation: full app suite reports 1,783 tests: 1,777 passed and six opt-in skips,
with no failures or errors. Unchanged shared/gateway suites were not rerun. Policy,
documentation links and staged whitespace checks pass.
