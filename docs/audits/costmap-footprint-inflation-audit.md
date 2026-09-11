# Costmap footprint clearance

Pass 184, 2026-09-11. Focused validation passed; full candidate validation pending.

Static inflation previously compared cell centers. A robot at (2.51, 2.51) with
radius 1 m was admitted beside a 0.01 m obstacle centered at (2, 2), although their
center distance is only about 0.721 m. The same problem exists at cell corners
for arbitrarily small positive inflation radii.

Inflation now compares closed occupied and target cell squares. Their separation
in each axis is max(abs(cell delta) - 1, 0) cells. A target is blocked when the
Euclidean separation is at most the bumper radius. Zero radius explicitly copies
raw occupancy. Positive inflation is conservative because the rasterized occupied
square can exceed the original physical shape. This can close narrow passages.

The loop clips work to the grid, reuses buffers, computes row distance once,
skips already blocked targets, and retains the grid-wide shortcut for huge radii.
The dynamic circle loop no longer carries an unused static-layer branch. Dynamic
obstacle radius semantics and map-edge footprint margins remain separate open work.

Three of four new tests failed before the fix. The four now pass, including a
direct physical collision counterexample, 3,087 cell comparisons against an
independent rectangle/corner distance oracle, reinflation and layer isolation,
and at most 1,024 allocated bytes across 10,000 warmed inflation calls. The existing
radius test now checks both the tangent cell and the first fully clear cell.
The focused pathing, field configuration and allocation suite passed 277 tests.

This establishes static grid clearance, not complete follower swept-body safety,
physical hardware validation or complete costmap coverage. World conversion,
dynamic footprints, field dimensions and simulator obstacle fidelity remain open.
