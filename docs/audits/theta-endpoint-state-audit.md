# Theta path endpoints and reusable search state

Pass 182, 2026-09-11. Validation completed for this pass; the repository-wide audit remains open.

## Confirmed fixes

- Replacing cell-center path endpoints with arbitrary physical coordinates invalidated
  the already-checked segments. An actual returned shortcut from `(0.51, 1.49)` to
  `(3.49, 2.49)` crossed occupied cell `(1, 2)`. Reconstruction now retains the checked
  cell centers and adds in-cell endpoint connectors only when needed. This adds at most
  two waypoints; centered endpoints retain the original count. Convex free endpoint
  cells make the connectors safe under the grid model without extra ray casts.
- Endpoints touching occupied cell edges/corners are rejected, including the same-cell
  shortcut. Nonfinite normalized coordinates return no path before integer conversion.
- Fresh planner nodes no longer appear to have zero cost. Either parent or cost setter
  initializes the current epoch without reviving an old parent/closed flag.
- The missing-parent sentinel now has a distinct closed encoding; closing a node twice
  is idempotent. Unseen-node closure and invalid parents/capacities fail before mutation.
- Generation rollover clears old stamps before reuse. Capacity growth prepares the heap
  outside expansion and no longer copies the previous search's stale reconstructed path.

Three endpoint tests and three planner-state tests failed before their respective fixes.
Final focused validation passed 254 tests. New coverage includes independent segment/square
intersection checks in both directions and an 81-case fractional endpoint matrix, edge/corner
contacts, invalid normalized coordinates, setter ordering, stale epochs, missing parents,
idempotent closure, generation wrap, invalid capacities and reuse of existing buffers.
Baseline and focused XML are under `ARESLib-Kotlin/build/audit-pass182-verified-evidence/`.

## Validation and scope

Candidate `17.0.6-rc.ba468110f1bb` binds library tree
`ba468110f1bb6aa3fe08bd98c26853a08b540745`. Full library tests, API checks and local
publication passed: 2,219 library tests. Serial consumers passed: FTC 156 tests and APK
assembly, FRC 305 tests, FTC starter 14 tests and APK assembly, FRC starter 34 tests,
Studio shared 31 and gateway 18 tests, and Studio app 1,822 passed with six opt-in skips.
All executed tests had zero failures/errors. Policy and documentation-link checks passed.

Four uniquely versioned canonical template archives were generated, their dependency
manifests inspected, and hashes pinned in resources and CI. Studio preflight and project
creation accepted the new bundles. Local versions are ARES/FTC/FRC 17.0.6, XRP/Lightbot
3.0.6 and Studio 7.0.7. Nothing was pushed, released or tested physically.

The six skips cover fresh generic starter builds, official archive integration,
representative generated starter simulation, native chooser, performance baseline and
physical dashboard validation. Source-product builds do not substitute for those opt-in
runs. Exact test names and hashes are preserved in the evidence summary.

This establishes safety of the returned polyline relative to the inflated cell grid,
not swept-body/follower behavior or the fidelity of physical obstacle rasterization.
Planner heuristic/path optimality and Costmap shape/rotation and extreme world-coordinate
conversion remain open. Raw public scratch arrays require exclusive ownership and must
not be mutated behind the accessors during a search.
