# Legacy game-piece and vision array delivery audit

Pass 255 follows [live pose and vision validation](live-pose-vision-integrity-audit.md)
with complete field-array delivery, legacy coordinate/count staging and recorded replay lengths.

Studio source tree: `28877227928a62de194d42354d05ce35f4612695`.
Unchanged ARESLib source tree: `526a048d8dfb1092e89be95c8e906e9fbc516027`.
Validation candidate: `17.0.42-rc.526a048d8dfb`.

## Confirmed defects and producer contract

All 18 baseline regression methods failed against the original implementation:

- Legacy pieces appeared before both x and y existed. Ignored z/quaternion updates also created
  objects and copied the display map. Negative and oversized indexes were accepted.
- Count constrained only the existing map, not subsequent arrivals. Fractional counts were
  truncated, invalid counts were ignored, and removed coordinates could reappear after growth.
  A text coordinate update left an earlier valid-looking piece displayed.
- Empty/shorter legacy parent arrays retained stale records. A truncated final record could
  expose a valid prefix and an invented partial piece.
- Empty vision parents did not clear observations. Shorter arrays at the same timestamp retained
  trailing poses; malformed lengths accepted a valid-looking prefix.
- Complete vision observations were lost when 5,000 unrelated publications displaced their
  flattened elements. A parent received before its same-time target flag was discarded.
  An empty canonical parent failed to suppress an earlier alias observation.

Read-only producer inspection confirmed the relevant contracts:
[topic constants](../../ARESLib-Kotlin/core/src/main/kotlin/com/areslib/telemetry/TelemetryTopicConstants.kt)
require honoring the legacy count. The simulator
[publisher](../../ARESLib-Kotlin/simulator/src/main/kotlin/com/areslib/sim/network/TelemetryPublisher.kt)
publishes count before the seven-value array, permits unused capacity after active records,
and emits an empty payload at zero. The
[vision publisher](../../ARESLib-Kotlin/core/src/main/kotlin/com/areslib/telemetry/ARESNetworkStatePublisher.kt)
emits x/y/CCW-radian triples and explicit empty arrays when observations disappear.

## Corrections and efficiency

[LegacyGamePieceAccumulator](../../ARES-Analytics/app/src/main/kotlin/com/ares/analytics/viewmodel/field/LegacyGamePieceAccumulator.kt)
uses bounded staging for actual x/y values. Counts are exact finite integers in 0..10,000,
apply to future arrivals and discard removed staging. Invalid coordinates remove affected
pieces. Only x/y changes touch the published map; unchanged coordinates and ignored attributes
reuse the previous map. Legacy labels are season-neutral.

[FieldArrayTelemetry](../../ARES-Analytics/app/src/main/kotlin/com/ares/analytics/service/FieldArrayTelemetry.kt)
decodes owned finite numeric arrays with the existing 4,096-element transport bound. Vision
requires complete triples. Legacy frames require complete active seven-value records; a valid
count permits spare unused capacity, including non-record trailing capacity. Without count,
the whole legacy payload must consist of complete records.

NT4 now preserves complete legacy and vision parent snapshots independently of the raw scalar
bus. Empty or malformed parents own an empty layer; their scalar prefixes cannot be reconstructed
as valid data. Typed v2 game pieces retain precedence. Legacy count snapshots survive scalar
overflow, remain authoritative for new views, and cannot revive records previously removed
from a parent. Canonical vision parents retain priority over the alias for the selected target.
Target loss clears vision observations; a subsequent target flag requires a current observation.

The service serializes its bounded field-array state changes with target reset. Consumers check
current parent identity, target epoch and replay mode. The extracted `FieldArrayTelemetryState`
owns this state and recorded lengths; unrelated topics return before acquiring its monitor.
This decomposition keeps Nt4ClientService within the repository's 1,000-line source limit.
The vision reducer accepts complete parent
maps, including a parent delivered before a same-time target flag, while keeping displayed arrays
empty when the flag is false.

Recording adds numeric `.../Length` metadata for legacy and vision arrays. Zero represents
empty/removal and -1 represents a rejected parent. Metadata and its coordinates share the
same receipt timestamp in live rewind storage. Legacy count reductions update the recorded
active length too. Replay validates length metadata and rejects text/nonfinite/incomplete
active records. Older logs without length metadata retain bounded scalar compatibility;
they cannot recover parent boundaries that were never recorded.

The common incoming-frame helper preserves session ownership, raw source timestamps for UI,
receipt timestamps for live rewind, pending-queue persistence and replay notification suppression.
It removes duplicated scalar/array recording code. No measured wall-time speedup or allocation-free
loop is claimed; the checked improvement is map/state reuse for unchanged or ignored input.

## Validation

Focused final-source validation passed 161 tests across eight suites, including 32 new
field-array cases. All 18 original baseline failures pass in both focused and full validation.
The full suite preserves every existing suite's method names, result counts and skip states.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,575 | 6 |

Full Studio validation has 2,624 passing results, zero failures/errors and six unchanged
opt-in skips. Focused results are not counted twice. All 410 unchanged library candidate
file hashes were rechecked. Monorepo policy passed, including 417 current-document links
and 38 historical records. The test startup heap remains an evidence-local 32 MiB with one
worker; shared build settings, maximum heaps and existing assertions are unchanged.

The initial full run passed but source policy rejected the service at 1,066 lines. Field-state
extraction reduced it to 994 lines. Focused and full suites were rerun successfully against
the final source identity above; initial-run evidence is retained separately and is not used
to attest the final source.

All original affected test methods remain. Fixtures use a real NT4 service with mock persistence
and held consumers, plus two tests with actual owned DuckDB databases. The latter verify
parent/metadata receipt identity, empty replay layers, rejected-parent lengths, active-count
capacity and metadata persistence during replay without live parent publication. Database,
client and coroutine cleanup is joined.

## Coverage and limits

The ledger accounts for 3,101 tracked files: 1,434 reviewed, 195 partially reviewed and
1,472 pending, with zero stale or orphaned records. There are 1,667 unfinished files.
The shared field-array decoders/state, legacy accumulator, new regression file and report
are fully reviewed. ReplayFieldSnapshot and VisionPoseAccumulator retain their full review
with the new behavior covered. The two larger service/subscriber files retain precise
partial scopes. The unfinished total is unchanged because this pass adds reviewed support
files and fixes shared behavior while the larger file boundaries remain open. File review
completion is not executable line coverage or physical validation.

FieldTopicSubscriber remains partial for remaining raw typed-fallback and broader lifecycle
ordering checks. Nt4ClientService still has larger recording, admission and connection boundaries.
The field-array lock does not establish ordering for every other service callback or source.
Scalar-only compatibility remains latched; no per-coordinate freshness guarantee is added.
Legacy seven-value records are displayed as planar positions; their z/quaternion fields are
validated when part of an active parent but are not used to invent planar orientation.

Recorded length metadata restores empty/shortened parent boundaries for new captures. It does
not reconstruct missing metadata in older logs or prove atomic replay reads during every concurrent
recording transaction. Live rewind uses receipt time; broader source-time versus arrival-time
ordering remains part of recording/replay review.

No ARESLib implementation, robot EKF/controller, WPILib implementation, version, rendered UI,
physical simulation run, hardware/HIL, robot-loop benchmark, remote CI, push, merge or release
was changed or performed.
