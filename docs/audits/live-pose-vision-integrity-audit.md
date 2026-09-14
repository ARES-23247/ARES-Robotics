# Live pose assembly and vision validation audit

Pass 254 follows [replay pose and trace integrity](replay-pose-trace-integrity-audit.md)
with the live accumulator, its NT4 parent boundary, field subscriber and pose-card consumers.

Studio source tree: `8639c9d93567c7b16406c8570f6f352062cdc4ca`.
Unchanged ARESLib source tree: `526a048d8dfb1092e89be95c8e906e9fbc516027`.
Validation candidate: `17.0.42-rc.526a048d8dfb`.

## Reproduced findings

All 18 original regression methods failed:

- A packed sequence marker could commit missing coordinates, reuse earlier slots, or republish
  an already consumed buffer. Nonfinite values and invalid sequences were accepted.
- A single scalar truth coordinate marked an entire pose complete. The accumulator mixed
  estimated and Drive axes, and Drive could overwrite a complete estimator when truth was absent.
- Reset borrowed coordinates from the previous rendered state. Target clear retained estimator
  and odometry values; a held live publication could repopulate the pose after entering replay.
- Snapshot construction allocated a new LivePoseState even when nothing changed.
- A manually supplied invalid parent bypassed validation; the network decoder accepted JSON
  numeric strings and sequences beyond exact integer precision.
- Text scalar placeholders could become numeric truth, while vision accepted invalid target
  flags, nonfinite coordinates and negative/oversized sparse array indexes.

## Corrections and scope

[FieldPoseFrameAccumulator](../../ARES-Analytics/app/src/main/kotlin/com/ares/analytics/viewmodel/field/FieldPoseFrameAccumulator.kt)
now owns fixed primitive buffers separately for truth, estimator, Drive and odometry. Only
complete finite groups become poses. Scalar groups remain latched because NT4 may suppress
unchanged values; they are not asserted to be measurements from one physical instant.

Packed fallback requires a fresh contiguous ten-slot payload and an exact sequence in
0..2^53-1. It commits only once per accepted sequence. Invalid or incomplete new packed data
does not mutate the previous valid packed sample. Parent delivery still bypasses lossy scalar
fan-out and owns all pose sources. Snapshot returns the same rendered state object when
its pose fields are unchanged; this is not a claim of zero allocations throughout the loop.

NT4 packed parents carry the selected target epoch. The decoder rejects textual numeric
elements and unsafe sequences. Delayed consumers check the current parent identity, epoch and
replay mode. Receipt of a parent, including a rejected parent, suppresses scalar reconstruction
of its valid-looking prefix. This is necessary for malformed eleven-element arrays. A rejected
parent leaves the last valid parent intact without manufacturing a new accepted sample.

The field subscriber and pose card share current-publication admission and
[VisionPoseAccumulator](../../ARES-Analytics/app/src/main/kotlin/com/ares/analytics/viewmodel/field/VisionPoseAccumulator.kt).
Mode/target resets remove staged and displayed live pose/vision sources. Replay rendering uses
the immutable ReplayFrame path instead of manually injected live-bus replay scalars.

Vision requires an explicit numeric target flag, complete finite scalar triples, one array
topic family and complete same-microsecond array triples. Indexes stay within the 4,096-element
transport bound, preventing an arbitrary sparse index from driving the renderer's iteration
count. Canonical arrays take precedence over the alias; loss/reset clears both staging and
published observations. Older array samples cannot replace newer observations.

Pose-card age uses monotonic elapsed time. Invalid numeric/text values and invalid target flags
cannot refresh the activity badge. The badge measures accepted activity across pose sources,
not the age of every individual displayed coordinate.

## Validation

Focused validation passed 97 tests: 31 new live pose/vision cases, ten original field
subscriber cases, 27 game-piece integrity cases, 28 NT4 client cases and one actual local
NT4 loopback integration. All 18 baseline failures pass in both focused and full validation.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,543 | 6 |

Full Studio validation has 2,592 passing results, zero failures/errors and six unchanged
opt-in skips. Focused results are not counted twice. All 410 unchanged library candidate
file hashes were rechecked. Monorepo policy passed, including 416 current-document links
and 38 historical records. The test startup heap remains an evidence-local 32 MiB with one
worker; shared build settings, maximum heaps and existing assertions are unchanged.

All ten existing FieldTopicSubscriberTest methods remain. The rewind test now creates the
actual immutable ReplayFrame projection used by the field viewer and verifies that live
traffic does not repopulate its live layer. Its original truth/estimate/odometry and return-to-live
assertions remain; the removed raw-bus replay injection represented a retired rendering path.

The new fixtures use the real NT4 service with mock persistence, held consumer dispatchers,
owned coroutine scopes and joined teardown. A separate existing loopback test exercises actual
JVM-local NT4 transport. No window was launched or visually inspected.

## Coverage and limitations

The ledger accounts for 3,097 tracked files: 1,430 reviewed, 195 partially reviewed and
1,472 pending, with zero stale or orphaned records. There are 1,667 unfinished files.
The two extracted/shared accumulators, new regression file and report are fully reviewed.
The pose card moves from pending to partial; the two larger service/subscriber files retain
precise partial scopes. The unfinished total is unchanged because this pass fixes shared
behavior and adds reviewed support files while larger file boundaries remain open. File
review completion is not executable line coverage or physical validation.

FieldTopicSubscriber remains partial: its legacy game-piece decoder, some lighting/lifecycle
ordering and per-source freshness remain separate boundaries. PoseViewerCard's changed reduction
and value/status predicates are reviewed and tested through their shared helpers, but its full
Compose collector lifecycle and rendered behavior remain partial. Nt4ClientService retains
its other partial ingress, recording and connection boundaries.

The scalar vision array path does not carry the original parent length. Empty parent arrays
that produce no scalar update and arbitrary concurrent mode/target transitions need broader
transport/lifecycle review. Same-time complete triples prevent cross-instant coordinate mixing;
this is not a claim of atomic publication for every multi-pose vision array.

No robot controller, EKF equations, WPILib implementation, library version, physical simulation,
hardware/HIL, robot-loop benchmark, remote CI, push, merge or release was changed or performed.
