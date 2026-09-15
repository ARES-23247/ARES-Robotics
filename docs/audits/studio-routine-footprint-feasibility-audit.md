# Studio routine footprint feasibility audit - pass 161

Read AutoFieldBounds in full and traced its two direct consumers: editor pose clamping
and guided-plan defaults. Reviewed the guided and routine boundary-validation paths in
RoutineEditorModel. The projection math for a rotated rectangular footprint was correct,
but an impossible axis collapsed to the field center with no feasibility flag. Validation
then compared the pose with its clamped copy. A centered 0.2-by-2-meter robot therefore
passed on the 1.4224-meter-wide XRP practice field, despite protruding outside it.
The same equality check accepted nonfinite autonomous starting poses because clamping
returned invalid input unchanged. Three regressions failed before the correction.

AutoCenterBounds now records canFit separately from finite fallback coordinates.
Projection calculations share sine/cosine values, and league extents feed one bounds
calculation instead of constructing axis pairs. Impossible axes still center the editor
position, but guided and routine validation now explicitly require finite poses, a
feasible footprint and an in-range center. This applies to guided starts/goals,
autonomous starting poses and recursively visited drive targets. Clamping behavior for
editing is retained; it is no longer treated as a validation predicate.

New bounds tests independently rotate all four footprint corners for every league and
several headings, then compare extremal corner clearance to legal center limits. They
also cover exact fit, rotation changing feasibility, finite impossible-axis fallback,
dimension clamps/defaults and nonfinite heading fallback. Routine tests cover both
oversized start/goal validation and every nonfinite starting-pose component.

AutoFieldBounds and the two new test files are fully reviewed within the existing
normalized-dimension and fixed-league-field contract. This does not certify that those
defaults describe a physical robot or a custom field resource. Dimensions are still
normalized to 0.1-2.0 meters and invalid values use the existing FTC-size fallback;
callers must not treat this editor helper as hardware configuration validation.
RoutineEditorModel remains partial: its broader tree mutation, argument/catalog and
execution-order behavior was not closed by this bounds-focused pass. The small camera
state class inspected during discovery was not added to coverage without tests.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass161-verified-evidence/`,
including the three before-fix failures. Candidate remains `17.0.3-rc.100852e472fb`.
No rendered UI, external service, simulator operation or physical robot test is claimed.

Validation: all 14 focused tests pass. The full app suite reports 1,810 tests: 1,804 passed and 6 opt-in skips, with no failures or errors. Unchanged shared/gateway suites were not rerun. Monorepo policy, documentation links and staged whitespace checks pass.
