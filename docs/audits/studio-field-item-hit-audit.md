# Studio field item hit-testing audit - pass 149

Read `FieldCanvasItemGestures` and `FieldCanvasDragTarget` in full, together with the
existing drag-target tests. Inspected the main gesture handler and field validator for
context; those broader files are not closed by this pass.

## Confirmed rotation omission

Double-click and non-path-waypoint context-menu hit testing converted screen coordinates
without supplying view rotation. The canvas and path-waypoint context-menu test already
used that rotation, so field-item selection could miss the item drawn under the pointer.

Consolidated the two field-item lookup paths into `findFieldItemAtScreen` and supplied
view rotation to the inverse transform. The required rotation argument has no default
in the new helper. Double-click retains its existing obstacle/AprilTag scope; context
menus retain obstacle, AprilTag, game-piece and named-waypoint priority. Path waypoints
still take priority in their existing screen-distance check.

The new regression fails after extraction with the original omitted-rotation behavior,
then passes with the correction. It tests every supported item kind (circle/rotated
rectangle obstacles included) under zoom/pan at zero, quarter-turn and oblique view
angles in all three leagues. A second test checks priority and an empty-space miss.
No native pointer event or rendered UI interaction is claimed.

## Drag target boundaries

The selection resolver checks waypoint bounds before indexing, preserves heading flags,
and suppresses field-object dragging when field controls are disabled. Path waypoints
remain available in that mode. Existing tests cover typed targets and stale-index
fallback; added coverage exercises negative/empty/exclusive-upper/large index bounds,
previous-heading propagation and named-waypoint heading selection. This closes the
small resolver and its test file, not the complete gesture handler.

Item gestures remain partial. `pointerInput(Unit)` capture/update behavior requires
dedicated integration validation; polygon lookup currently checks vertex proximity
rather than the full interior. Gesture cancellation, invalid viewport inputs and live
interaction also remain open. No changes in this pass certify those behaviors.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass149-verified-evidence/`.
The ARES candidate remains `17.0.3-rc.100852e472fb`. Changes are local; no UI launch,
external service, simulator operation or physical hardware validation occurred.

Validation: full app suite reports 1,772 tests: 1,766 passed and six opt-in skips,
with no failures or errors. Unchanged shared/gateway suites were not rerun. Monorepo
policy, documentation links and staged whitespace checks pass.
