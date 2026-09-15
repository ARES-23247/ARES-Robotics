# Costmap footprint clearance

Pass 184, 2026-09-11. Validation complete for this pass; the repository-wide audit remains open.

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

## Candidate and consumer evidence

Candidate `17.0.8-rc.c5f40142a878` binds library tree
`c5f40142a878a9e306c5da007dad737b037f9668`. All 2,234 library tests, API
checks and local publication passed. Serial consumer validation passed: FTC 156
tests plus APK assembly, FRC 305, FTC starter 14 plus APK assembly, FRC starter
34, Studio shared 31, gateway 18 and app 1,822 passed with six opt-in skips.
Combined executed tests: 4,614 passed, zero failures or errors. Focused tests are
included in the library total, not counted a second time.

The six skips cover fresh generic starter builds, official archive integration,
representative generated starter simulation, native chooser, performance baseline
and physical dashboard validation. Exact names, XML and logs are preserved under
`ARESLib-Kotlin/build/audit-pass184-verified-evidence/`.

Canonical archives were regenerated under unique versions, dependency manifests
and SHA-256 pins verified, and Studio preflight/project creation passed. Local
versions are ARES/FTC/FRC 17.0.8, XRP/Lightbot 3.0.8 and Studio 7.0.9.
Repository policy and documentation links passed. No push, release or hardware
validation was performed. Source-product tests do not establish fresh exported
project build results.
