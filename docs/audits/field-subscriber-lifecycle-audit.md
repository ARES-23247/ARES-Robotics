# Field subscriber lifecycle and cached pose audit

Pass 256 completes the field subscriber review following
[field-array delivery](field-array-delivery-integrity-audit.md).

Studio source tree: `afd4d21335452feeec70babcd6ff113267b1cf71`.
Unchanged ARESLib tree: `526a048d8dfb1092e89be95c8e906e9fbc516027`.
Validation candidate: `17.0.42-rc.526a048d8dfb`.

## Confirmed defects

Eight new regression methods failed before production changes:

- Late owner-dispatcher startup callbacks erased an already accepted packed pose or legacy parent.
- Text, nonfinite and incomplete first typed game-piece headers left legacy pieces displayed.
- Vision target flags disappeared for active or newly opened views when unrelated updates
  overflowed the raw telemetry queue.
- A false/true vision transition conflated before consumption revived scalar coordinates from
  the previous observation.

After correcting those paths, three additional regressions failed for scalar pose and vision
startup/overflow. The raw replay cache could retain recent noise while dropping unchanged x/y
or heading fields required to assemble a complete pose.

## Corrections and file review

[FieldTopicSubscriber](../../ARES-Analytics/app/src/main/kotlin/com/ares/analytics/viewmodel/field/FieldTopicSubscriber.kt)
now reconciles current source values under one reduction monitor. Connection, mode, target,
parent and lighting signals all run on the processing dispatcher and read current snapshots;
an obsolete callback payload cannot independently reset newer state. Startup and reset handling
share the same path. Parent identity and target epoch admission remain explicit.

Seventeen fixed scalar pose/vision topics use TelemetryStore's existing latest-notified
StateFlows. Observer objects are shared across recreated views. Initial/reset hydration restores
latched values, including unchanged headings; the raw stream excludes these subscribed topics
to avoid duplicate reduction. Cached vision coordinates are admitted only at or after the
current target-start timestamp. The shared topic sets also remove duplicated filtering lists.

A typed game-piece prefix claims source ownership even when its first header is rejected.
Until a complete valid typed frame arrives, legacy pieces cannot stand in for that source.
The existing ordered scalar frame decoder still rejects missing/out-of-order headers, invalid
counts, incomplete records, invalid identities/dimensions and consumed sequence buffers.
A malformed subsequent raw typed frame retains the last complete typed observation; complete
NT4 parents retain their separate rejected-parent empty-layer behavior.

[FieldArrayTelemetry](../../ARES-Analytics/app/src/main/kotlin/com/ares/analytics/service/FieldArrayTelemetry.kt)
now retains a vision target snapshot with a loss generation. Loss followed by reacquisition
remains observable even when intermediate flags are conflated. Its selected-target reset clears
the snapshot/generation, and replay-active recording suppresses live target publication.
[VisionPoseAccumulator](../../ARES-Analytics/app/src/main/kotlin/com/ares/analytics/viewmodel/field/VisionPoseAccumulator.kt)
uses that generation to clear prior scalar data and rejects coordinates older than reacquisition.

Replay masks live pose, game-piece and lighting layers. Returning live restores the last
consumer-visible snapshots and scalar cache, rather than silently recorded replay inputs.
These are latched observations, not a freshness assertion. An observed disconnect clears pose
and piece state and rejects its cached parents/scalar frames until new observations arrive.
The owner scope controls every collector; cancelling it stops view updates while leaving the
shared service available. Lighting maps are projected only when their shared snapshot changes,
and unchanged rendered state retains its identity.

The full subscriber file was reviewed: topic selection, ordered raw game-piece accumulator,
constructor/lifetime ownership, cached scalar initialization, source priority, target/mode/
connection handling, lighting, callback serialization and state reuse. No measured latency,
allocation-free loop or physical robot timing improvement is claimed.

## Validation

Focused validation passed 188 tests across nine suites, including all 27 new lifecycle
methods. The eight original baseline failures and three additional scalar-cache failures
pass in both focused and full validation. Every existing full suite preserves its method
names, result counts and skip states.

| Studio suite | Passed | Skipped |
| --- | ---: | ---: |
| Shared | 31 | 0 |
| Gateway | 18 | 0 |
| App | 2,602 | 6 |

Full Studio validation has 2,651 passing results, zero failures/errors and six unchanged
opt-in skips. Focused results are not counted twice. All 410 unchanged library candidate
file hashes were rechecked. Monorepo policy passed, including 418 current-document links
and 38 historical records. The test startup heap remains an evidence-local 32 MiB with one
worker; shared build settings, maximum heaps and existing assertions are unchanged.

The new test fixture uses an actual NT4 client with mock persistence, a controlled connection
StateFlow, separately held owner/processing dispatchers, and joined cancellation/disposal.
FIFO and reversed collector startup, 5,000-update overflow, distinct-topic eviction, replay,
disconnect/reconnect, target reset, raw typed recovery, old scalar admission, observer reuse,
lighting and state identity are exercised. Existing real DuckDB and local NT4 loopback tests
remain in the focused/full suites.

## Coverage and limits

The ledger accounts for 3,103 tracked files: 1,437 reviewed, 194 partially reviewed and
1,472 pending, with zero stale or orphaned records. There are 1,666 unfinished files.
FieldTopicSubscriber moves from partial to a complete file review. The shared field-array
and vision accumulator reviews remain complete with the new behavior verified. The new
regression file and report are fully reviewed; Nt4ClientService retains its precise partial
scope. These counts track file review completion, not executable line coverage or physical
validation.

This is a JVM state/reduction review, not proof of an actual rendered Studio window or hardware.
The subscriber monitor serializes consumer work; it does not make unrelated producer callbacks
or database transactions globally atomic. Scalar-only compatibility is latched and bounded.
Raw dynamic array fragments still require complete ordered delivery; they cannot reconstruct
unrecorded parent boundaries or every transition lost upstream. Complete NT4 parent snapshots
and retained target generations provide the stronger supported path.

The broader Nt4ClientService recording, connection/admission and source-clock versus receipt-clock
boundaries remain partial. No ARESLib implementation, version, robot controller, WPILib source,
rendered UI, physical simulator run, hardware/HIL, remote CI, push, merge or release was changed
or performed.
