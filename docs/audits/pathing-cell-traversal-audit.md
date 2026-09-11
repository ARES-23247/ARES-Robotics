# Pathing cell traversal

Pass 180, 2026-09-11. Validation in progress.

The old Bresenham traversal checked both orthogonal neighbors for every diagonal
step, even when the segment did not cross their shared corner. This rejected
collision-free shortcuts and could give different results for reversed endpoints.
An independent closed-square slab intersection test reproduced a false rejection:
segment `(2,0)` to `(0,1)` was rejected with only `(0,0)` blocked.

The replacement compares exact integer boundary-crossing times. It visits all touched
cells and checks both side cells only at an exact corner crossing. Out-of-bounds
endpoints are rejected before subtraction. It uses primitive scalars and no scratch
collections. Complexity is linear in the number of crossed rows and columns.

The geometric oracle exhaustively checks 15,625 combinations of endpoints and one
blocked cell on a 5 by 5 grid, including reverse directions, corner contacts and
zero-length segments. The fixture sets the private inflated grid to isolate traversal
from costmap rasterization. Extreme integer endpoint tests and a warmed 10,000-query
thread-allocation measurement also pass, together with existing pathing and zero-GC
tests. Baseline failure and focused XML are saved under
`ARESLib-Kotlin/build/audit-pass180-verified-evidence/`.

Shared source identity is `5b8edabe1731ad74332c15d408a4586050b92334`, version
`17.0.4`, local validation candidate `17.0.4-rc.5b8edabe1731`. Full library tests,
API checks and local candidate publication were started; dependency-ordered consumer
validation and final ledger updates remain required. No push, release or hardware test.

## Follow-up findings from adjacent source inspection

- Costmap static inflation scans an unbounded radius square before checking map bounds;
  dynamic insertion and expiry duplicate that scan and square integer radii, risking
  overflow and excessive work. These paths need bounded rasterization and regression
  tests. They are not fixed or counted as fully reviewed by this pass.
- Planner scratchpad/heap initial-state and zero-capacity boundaries need direct tests.
  They are not counted as reviewed from reading their call sites alone.
- Cell-center line-of-sight correctness does not establish safety for the planner's
  later replacement of endpoint centers with arbitrary physical coordinates.
