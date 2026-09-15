# Static field geometry in the costmap

Pass 183, 2026-09-11. Validation completed for this pass; the repository-wide audit remains open.

## Confirmed fixes

The previous costmap treated all obstacles as unrotated rectangles. This omitted portions
of rotated rectangles, ignored absolute polygon vertices, and halved circle radii. Static
element boxes also ignored rotation, and round elements used an unrelated 0.15 m default
instead of the canonical diameter-or-width rule.

The new internal rasterizer uses four separating axes for rectangle/cell intersections,
nearest-point circle/cell distance, and polygon edge intersections plus even-odd containment.
It clips candidate cell ranges to the grid and includes closed edge/corner contacts on both
sides of exact cell boundaries. Polygon vertices remain absolute field coordinates; rectangle
rotations are CCW degrees; obstacle circle width is radius, while round element diameter
falls back to width. All schema-supported round aliases are accepted.

Rectangle trigonometry, scaling and separating-axis bounds are computed once per shape.
Exact quarter turns use exact axis values: a focused refinement test caught a residual
cosine dropping tangent cells on a long rectangle. The raster loops create no per-cell
geometry or iterator objects. Invalid primitives, unsupported shapes, duplicate element
type IDs and missing references fail explicitly. Non-blocking obstacles and movable
elements remain excluded. A list call can add earlier valid shapes before a later invalid
shape throws; it does not clear the previous raw occupancy layer.

## Evidence and remaining work

Five original geometry tests failed before the fix. Eleven new geometry tests now pass,
including 18,000 independent Java2D area/cell comparisons for rotated rectangles and a
concave polygon, thin edge crossings, circle corners, exact contacts, all round aliases,
invalid inputs, off-grid bounds and a warmed allocation check (at most 1,024 bytes across
3,000 rasterizations). Java2D is used only as a JVM test oracle, not in runtime code.
The final focused pathing/field/allocation run passed 273 tests. Baseline, quarter-turn
refinement and focused XML are in `ARESLib-Kotlin/build/audit-pass183-verified-evidence/`.

Candidate `17.0.7-rc.ab5d50599303` binds tree
`ab5d505993031912ace7569ae1b28d4351fbe372`. Full library/API/local publication was
completed: 2,230 library tests passed, as did API checks and local publication.
Serial consumers passed: FTC 156 tests and APK assembly, FRC 305 tests, FTC starter
14 tests and APK assembly, FRC starter 34 tests, Studio shared 31 and gateway 18 tests,
and Studio app 1,822 passed with six opt-in skips. All executed tests had zero failures
or errors. Repository policy and documentation-link checks passed.

Four uniquely versioned canonical archives were generated, their dependency manifests
inspected, and their hashes pinned in both resources and CI. Studio preflight and project
creation accepted them. Local versions are ARES/FTC/FRC 17.0.7, XRP/Lightbot 3.0.7 and
Studio 7.0.8. No push, release or physical validation.

The six Studio skips cover fresh generic starter builds, official archive integration,
representative generated starter simulation, native chooser, performance baseline and
physical dashboard validation. Source-product tests do not substitute for those opt-in
runs. Exact skipped names and artifact hashes are preserved in the evidence summary.

Open adjacent areas include continuous robot-footprint inflation, extreme world-to-cell
conversion, field-dimension selection, and simulator obstacle fidelity (minimum-size clamps
and concave polygon fallback). This pass verifies canonical planar shape occupancy; it does
not establish physical contact, follower swept-body safety or complete simulator parity.
