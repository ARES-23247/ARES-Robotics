# Studio field eraser audit - pass 160

Reviewed the main gesture eraser branch and the field editor's deletion contract.
FieldEditorViewModel.deleteSelection preserves locked geometry, tags, game pieces and
named waypoints. The path-planner eraser previously checked none of these locks.
Its rectangle predicate used distance to the center minus half the larger dimension,
which represents a circle rather than the actual rectangle: a 10-by-1-meter rectangle
could be erased from 2.5 meters outside its side, while a large square could miss an
interior corner. Polygon proximity considered only vertices, leaving long edges without
the 0.5-meter erase reach available near vertices. These issues were established from
source and analytic counterexamples; no before-fix test execution is claimed this pass.

Extracted a typed field-item target resolver. It preserves path-waypoint priority in
the caller and game-piece, obstacle, tag, named-waypoint priority inside the resolver,
but skips locked items and continues to the next eligible target. The gesture handler
removes only the returned typed item through its existing callback. Screen-space path
waypoint radius remains 25 pixels; other point items retain strict 0.3-meter proximity.

Obstacle proximity now uses distance to the filled shape with a strict 0.5-meter reach.
Circle distance subtracts radius and clamps interior distance to zero. Rectangle distance
inverse-rotates into local axes and combines outside-axis distances with hypot. Polygon
distance uses even-odd interior containment, then nearest projected segment distance,
including the closing edge and repeated vertices. Work is linear in vertex count with
no intermediate geometry list. Nonfinite coordinates, incomplete polygons and nonpositive
primitive sizes do not become targets. This is field-scale geometry, not a guarantee for
extreme finite coordinates or a validator for self-intersecting/zero-area polygons.

Four new tests cover locked items in every category, fallback/category/list priority,
rotated rectangles, interior corners and diagonal outside distance, long-edge polygon
reach, concave notches, both winding orders/repeated closure, strict radius boundaries
and representative invalid geometry. The resolver, shape-distance helper and test file
are fully reviewed within that scope. The main gesture file remains partial: capture
updates, native slop/cancellation/release lifecycle, field-waypoint heading conventions,
invalid viewports, ID uniqueness and tool/control-mode policy still need review.
Unit target tests do not certify native pointer delivery or rendered behavior.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass160-verified-evidence/`.
Candidate remains `17.0.3-rc.100852e472fb`; no simulator, external service, rendered UI or
physical robot validation is claimed.

Validation: all four focused tests pass. The full app suite reports 1,804 tests: 1,798 passed and 6 opt-in skips, with no failures or errors. Unchanged shared/gateway suites were not rerun. Monorepo policy, documentation links and staged whitespace checks pass.
