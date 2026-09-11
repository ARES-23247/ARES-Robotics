# Studio polygon selection audit - pass 158

Closed the polygon-interior issue left open in pass 149. Double-click and context-menu
lookup previously selected a polygon only within 0.3 meters of a vertex. A regression
using an L-shaped polygon failed on a point well inside its filled area.

Extracted the field editor's existing even-odd containment routine into a shared app
helper and used it in secondary item lookup. The existing vertex grab tolerance remains
available outside the polygon. The helper accepts either winding, includes segment
boundaries, rejects incomplete/nonfinite geometry and avoids temporary geometry lists.
Its work remains linear in vertex count. No timing or allocation benchmark is claimed.
The field editor now uses this same helper instead of a private duplicate; boundary
points are consistently included instead of depending on the direction of the edge.

Regression coverage exercises both arms and the empty notch of a concave polygon,
both winding orders, every league, zoom/pan and zero/quarter-turn/oblique view rotations.
Direct geometry checks cover vertices, horizontal/vertical/diagonal edges, just-outside
points, a repeated closing vertex, fewer than three vertices and nonfinite coordinates.
The helper uses ordinary double arithmetic for field-scale geometry; this pass does not
certify extreme finite magnitudes, numerically exact predicates or pixel-edge rounding.

The new helper and tests are fully reviewed within that scope. Item gestures remain
partial because pointer capture/update lifecycle, cancellation and invalid viewport
handling are still open. FieldLayoutCanvas is partial: only the containment extraction
and its call site were reviewed. Its rendering, manipulation and native pointer lifecycle
are not certified by these tests. Main drag/eraser gesture code also repeats vertex-only
tests and omits view rotation in several inverse conversions; those paths remain queued
for a separate coherent gesture audit, including obstacle candidate selection ordering.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass158-verified-evidence/`,
including the before-fix failure. Candidate remains `17.0.3-rc.100852e472fb`.
No rendered UI, simulator operation, external service or physical validation is claimed.

Validation: five focused tests pass. The full app suite reports 1,797 tests: 1,791 passed and 6 opt-in skips, with no failures or errors. Unchanged shared/gateway suites were not rerun. Monorepo policy, documentation links and staged whitespace checks pass.
