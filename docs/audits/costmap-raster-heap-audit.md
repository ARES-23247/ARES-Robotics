# Costmap raster and pathfinding heap

Pass 181, 2026-09-11. Focused verification passed; full candidate validation is in progress.

## Fixes and evidence

- Inflation now rejects nonfinite/negative radii before changing the previous collision
  layer. A zero radius copies raw occupancy instead of adding a one-cell halo.
- Static inflation, dynamic insertion and dynamic expiry share a grid-clipped circle
  rasterizer. Work no longer scales with an unbounded off-grid radius square. Static
  circles covering the entire map use one occupancy scan and fill, avoiding repeated
  full-grid writes per obstacle.
- Radius and coordinate differences use Long arithmetic. Comparing X squared with
  radius squared minus Y squared avoids both Int multiplication overflow and a summed
  squared-distance overflow. Insertion and expiry use the identical cell mask; overlapping
  reference counts and the separate static layer are preserved.
- Expiry compares timestamp ordering before interpreting elapsed time. Positive elapsed
  intervals exceeding Long.MAX_VALUE expire correctly, while future timestamps are retained.
- LongHeap grows from zero capacity and rejects empty extraction before mutating size.
  Growth arithmetic and child-index bounds avoid Int overflow. Documentation now limits
  the allocation guarantee to operations within existing capacity.

Three costmap tests failed before the fix (zero radius, invalid-radius state replacement,
elapsed-time overflow), as did two heap tests (zero capacity and empty extraction).
Final focused validation passed 246 tests, including independent distance masks, partial
off-grid circles, overlapping expiries, exact extreme tangent cells, huge-radius bounded
execution, warmed allocation checks, and reference-sorted heap growth/reuse. Huge-radius
tests were added after removing the unbounded loops; the old implementation was not left
running on those inputs. Evidence lives in
`ARESLib-Kotlin/build/audit-pass181-verified-evidence/`.

## Remaining validation and scope

Candidate `17.0.5-rc.fec5be5c4f2c` binds library tree
`fec5be5c4f2cc0fd245f02883e131057a9dc786a`. Full library/API/local publication was
started. Serial consumer tests, refreshed immutable Studio bundles, final policy checks
and ledger updates remain required. Planned local versions are ARES/FTC/FRC 17.0.5,
XRP/Lightbot 3.0.5 and Studio 7.0.6. No push, release or physical validation.

Costmap remains partial: world-to-cell conversion still saturates to Int at extreme
coordinates/radii, and static element rotation/shape rasterization and invalid world
coordinates need separate review. Correct masks for quantized circles do not establish
arbitrary physical-geometry correctness. PlannerState in the heap's source file also
remains partial; initial generation and closed-parent sentinel behavior need direct tests.
