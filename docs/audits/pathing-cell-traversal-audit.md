# Pathing cell traversal

Pass 180, 2026-09-11. Validation completed for this pass; the repository-wide audit remains open.

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
API checks and local candidate publication passed (2,201 library tests). FTC passed
156 tests and APK assembly; FRC passed 305 tests; FTC starter passed 14 tests and APK
assembly; FRC starter passed 34 tests. Studio shared and gateway passed 31 and 18 tests;
app passed 1,822 with six opt-in skips. All executed tests had zero failures/errors.
Repository policy and link checks passed. No push, release or hardware test.

Studio preflight caught the old workflow library pin; template creation then caught
bundled archives still declaring 17.0.3. The four bundles were rebuilt from canonical
sources with new immutable identities: FTC/FRC 17.0.4, XRP/Lightbot 3.0.4, Studio 7.0.5.
Manifest and workflow hashes match the new bundled files. A second independent archive
build reproduced all four hashes, and focused project-creation tests plus the full
Studio suite passed. Previous archives were replaced in resources, not overwritten
under their old version names. These local identities do not imply public availability.

The six Studio skips remain fresh generic starter builds, official-template integration,
representative generated starter simulation, native chooser interaction, performance
baseline and physical dashboard validation. Source-product tests and archive creation
checks do not substitute for those opt-in integration runs. XML, command logs, archive
hashes and exact skipped test names are preserved in the evidence summary.

## Follow-up findings from adjacent source inspection

- Costmap static inflation scans an unbounded radius square before checking map bounds;
  dynamic insertion and expiry duplicate that scan and square integer radii, risking
  overflow and excessive work. These paths need bounded rasterization and regression
  tests. They are not fixed or counted as fully reviewed by this pass.
- Planner scratchpad/heap initial-state and zero-capacity boundaries need direct tests.
  They are not counted as reviewed from reading their call sites alone.
- Cell-center line-of-sight correctness does not establish safety for the planner's
  later replacement of endpoint centers with arbitrary physical coordinates.
