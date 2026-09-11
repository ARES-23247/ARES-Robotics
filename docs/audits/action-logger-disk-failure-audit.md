# Action logger disk and shutdown failure audit

Pass 94 completes ActionLogger's remaining disk/finalization scope and reviews its
existing regression test file. Seven new tests cover write, flush, unexpected close,
rotation flush, finalization collision, initialization failure, queue saturation and
post-stop rejection. Three initial tests failed before the corresponding fixes.

## Findings and fixes

- A flush exception skipped close and the logger could rename an incomplete active file
  to completed. Close is now attempted in a finally block, and promotion requires both
  successful writing and successful flush/close.
- An unexpected close exception escaped the worker's cleanup before its completion latch
  was released, leaving stop waiting indefinitely. Nested cleanup now signals completion
  even when cleanup fails. Test-owned injected failures verify bounded host completion.
- A write exception allowed the stream to continue and ultimately become a completed file,
  even though an incomplete JSON record could remain. A writer/file failure now stops
  acceptance, discards and counts queued records, and retains the affected active file.
- The drop counter did not account for buffered records that failed at final flush. It now
  conservatively counts all records not committed to a completed file, including potentially
  recoverable bytes in an active file. Successfully finalized earlier mode files remain
  independent. This is record accounting, not a proof of physical disk durability.

Finalization continues to use a non-replacing move. A collision test creates an existing
destination and verifies that its bytes survive, the active file remains, and the uncommitted
record is counted. Initialization failure rejects later actions and permits repeated stop.
A stalled-writer test fills the bounded queue, verifies exactly two excess records are
rejected, and replays all 1001 accepted timestamps in order after drain.

The healthy writer adds a primitive per-file counter and a success flag. File operations
remain on the writer thread after construction; no per-record flush, retry loop, new
unbounded queue, or extra payload copy was added. Initial file creation and stop can still
block on real filesystem operations. A new logger is required to resume after a disk fault.

The existing logger tests now release stalled workers and stop loggers in finally blocks,
and restore the prior RobotClock mode/time instead of always forcing system mode.
Fault injection uses only test-owned writers, log directories, and threads. A before-fix
test cleanup seam releases its own stranded completion latch and then waits for its
executor to terminate; it never terminates unrelated processes.

## Limits and remaining owners

These tests simulate Java writer failures and local filename collisions. They do not
prove power-loss durability, filesystem recovery, Android storage permissions, or bounded
physical I/O latency. Retention/download policy and Studio ingestion are separate owners
and remain future file scopes. This pass does not claim that every recovered active file
is replayable or that an error counter alone supplies missing records.

## Final validation

The final source passed 40 focused logger/replay tests and all API checks before freezing. Seven new methods include three preserved failure-before regressions. Public signatures are unchanged.

Source `d61881c32184d976f052f06748455e7a47d0bab8`; library tree `2f08206d2a9398fedf6fd91dde5800a586938381`.
Local candidate `17.0.3-rc.2f08206d2a93`.

| Scope | Passed | Skipped |
| --- | ---: | ---: |
| library | 2121 | 0 |
| ftc | 111 | 0 |
| frc | 148 | 0 |
| ftc-starter | 14 | 0 |
| frc-starter | 34 | 0 |
| studio | 1790 | 6 |

All groups have zero failures/errors. Library API/Kover/local publication, generated-project verification, FTC assembly, Studio Kover/version/file-size gates and monorepo policy passed. Gradle reused valid unchanged outputs; counts do not imply every test was freshly executed. Conditional Studio skips remain recorded in XML.

Copied XML, hashes, logs and candidate BOM identity are under `ARESLib-Kotlin/build/audit-pass94-verified-evidence/summary.json`; focused XML is under `ARESLib-Kotlin/build/audit-pass94-focused-evidence/`. No physical disk durability, hardware timing or usable Studio-window result is claimed.
