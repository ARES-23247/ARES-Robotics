# Studio routine tree editing audit - pass 162

Reviewed the tree-edit and route traversal functions in RoutineEditorModel, their
PathPlannerViewModel move call site, and the complete RoutineEditorModelTest file.
Move operations recursively searched children and else-children but copied the deadline
reference without visiting it. Thus siblings inside a deadline's group could not move.
The existing algorithm also copied lists and ancestor nodes for missing IDs, boundary
moves and zero moves, despite no logical change. Regression tests reproduced both issues.

Moves now visit deadline descendants while keeping the deadline node itself in its
single-node lane. Lists are copied only when a descendant actually changes, and parent
nodes retain their object identity when all children are unchanged. Zero-direction moves
return immediately. Destination arithmetic uses Long before range validation. Existing
step IDs and valid sibling-relative movement remain unchanged.

Route collection now appends into one result builder with a recursive visitor, avoiding
one result list per subtree and singleton wrappers for deadline visits. Order remains
own drive, deadline, children, else-children. This is the editor's structural route order;
it does not predict which runtime branch wins or serialize parallel execution. The new
mixed-tree test checks collection order, final target and replacement waypoint order
through deadline groups and both branch lanes. No timing benchmark is claimed.

The initial before run had three failures: the two targeted regressions plus an overly
strict test comparison of degree/radian round-trip values (3.0 vs 3.0000000000000004).
That test assertion was corrected to a 1e-12 tolerance; it was not a production defect.
All original test behavior remains covered, including XRP bounds, supported node defaults,
clamping, deadline route order, stable edits/removal and catalog argument/action checks.

The test file is fully reviewed. RoutineEditorModel remains partial: broader catalog and
argument edge cases, arbitrary invalid/deep trees and identity uniqueness are not closed
by this pass. The editor expects canonical unique step IDs; recursive traversals are not
claimed safe for unbounded nesting. No rendered UI, simulator or physical robot execution
was used to validate this change.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass162-verified-evidence/`.
Candidate remains `17.0.3-rc.100852e472fb`; changes remain local.

Validation: all 11 focused tests pass. The full app suite reports 1,813 tests: 1,807 passed and 6 opt-in skips, with no failures or errors. Unchanged shared/gateway suites were not rerun. Monorepo policy, documentation links and staged whitespace checks pass.
