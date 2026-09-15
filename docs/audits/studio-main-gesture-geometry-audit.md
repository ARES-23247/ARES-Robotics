# Studio main gesture geometry audit - pass 159

Read the complete main field gesture handler. Its obstacle search chose the closest
candidate before applying containment, so a nearby small rectangle that missed the
pointer could hide a larger rectangle that actually contained it. Its polygon distance
calculation also threw for an empty vertex list. Extracted the existing algorithm and
ran regressions: the containing-rectangle assertion failed and the empty-polygon case
threw NoSuchElementException before the fixes.

The search now ranks only actual hits in a single loop without a filtered list. It
preserves the previous shape-specific distance priority and first-hit tie ordering.
Primary and secondary selection share circle, rotated-rectangle and polygon containment;
polygons accept their filled interior as well as the existing vertex grab tolerance.
Empty polygons cannot abort the search. Shared point distance uses hypot to avoid the
unnecessary squared intermediate overflow of the previous distance implementation.

Inspected every inverse conversion in the main handler: press-based selection, all
placement tools, erasing, waypoint position/heading dragging, field-waypoint heading
dragging and accumulated field-object drag deltas omitted view rotation. They now pass
it explicitly. Press conversion is centralized, waypoint position dragging reuses its
already converted point, and eraser waypoint lookup computes the rendered position once
instead of twice. Polygon erasing now includes the filled interior while retaining the
existing 0.5-meter vertex tolerance. No measured speedup is claimed.

New tests cover the nearer-miss case in both list orders, empty polygons, polygon interior
and exterior, overlapping shape priority, stable ties and an empty obstacle list. Existing
math and hit tests validate all-league pan/zoom/rotation inverses and drag-delta agreement,
concave polygon geometry and all secondary item kinds. The main call-site wiring was
reviewed directly; these headless tests do not simulate native drag or release events.

The handler remains partial. Pointer capture/update lifecycle, slop/cancellation and
release semantics, field-waypoint heading render/interaction conventions, invalid
viewports, generated ID uniqueness, and eraser lock/proximity semantics remain open.
The rectangle eraser still uses a center-distance approximation. This pass does not
certify those behaviors, live UI interaction, extreme/invalid obstacle dimensions or
physical robot behavior. The shared selection helper is reviewed for ordinary finite
field geometry; upstream geometry validation remains a separate boundary.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass159-verified-evidence/`,
including the two before-fix failures. Candidate remains `17.0.3-rc.100852e472fb`.

Validation: all 11 focused tests pass. The full app suite reports 1,800 tests: 1,794 passed and 6 opt-in skips, with no failures or errors. Unchanged shared/gateway suites were not rerun. Monorepo policy, documentation links and staged whitespace checks pass.
