# Studio field canvas math audit - pass 148

Read all of `FieldCanvasUtils.kt` and the existing XRP coordinate tests. Traced the
canvas draw order: rotation about the canvas center wraps pan and origin-based zoom.
The forward helper applies those operations in that order; its inverse reverses them.

Removed a duplicate inverse-transform implementation from `getRobotCoordFromScreen`;
it now uses `getBaseCanvasFromScreen`, as hit testing does. Axis labels were measured
explicitly for placement, then sent to the text-measuring draw overload a second time.
They now draw the existing layout results. This removes duplicate measurement calls;
no benchmark or whole-renderer allocation guarantee is claimed. Reworded the cache
comment to describe reduced allocations rather than zero-allocation rendering.

Added three mathematical tests covering:

- FTC axis exchange, FRC corner origin and XRP center origin on a rectangular canvas.
- An explicit quarter-turn result after pan/zoom; forward/inverse and drag-delta
  consistency at four view angles in all leagues, including the hit-test inverse.
- Cubic Hermite endpoints, exact linear motion and a quadratic with opposing tangents.

The existing two XRP tests independently assert numeric positions, inverse coordinates,
canvas-center mapping and unrotated drag scaling. No correctness defect was established
for valid finite inputs with positive canvas/field dimensions and zoom.

`FieldCanvasUtils` remains partial: degenerate dimensions/zoom, full path-cache ownership,
draw-time concurrency, rendered text/axes and complete gesture integration are not
certified. The camera gesture state holder was read for context but is not closed by
these tests. No UI launch, simulator operation or physical robot validation occurred.

Evidence is retained under `ARESLib-Kotlin/build/audit-pass148-verified-evidence/`.
The ARES library candidate remains `17.0.3-rc.100852e472fb`; all changes remain local.

Validation: 1,769 app tests: 1,763 passed, six opt-in skips, no failures or errors.
Unchanged shared/gateway tests were not rerun. Monorepo policy, document links and
staged whitespace checks pass. No before-fix failure is claimed for this redundancy
removal; source comparison establishes the duplicated work.
