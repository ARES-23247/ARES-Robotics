# Alert source identity and telemetry publication audit

Pass 30 follows alert occurrence transitions through telemetry publication and target reset.
It covers queued-frame identity, reset propagation, source chronology and callback ordering.
The engine and telemetry store remain partially reviewed; this does not close their remaining
window arithmetic, memory, failure, transport or configuration scope.

## Findings and changes

- The alert engine consumed untagged frames. A queued old-target frame could recreate an alarm
  after a reset, while cached motor signals, loop windows and chronology survived the reset.
  Every notified publication now carries the target epoch captured during indexing. The engine
  subscribes to tagged publications and epoch changes, clears its derived source state on reset,
  and rejects queued publications from earlier epochs. A reset clears the displayed engine
  records without fabricating historical resolution; persisted evidence stays in the database.
  A new target can start its clock below the prior target's high-water mark.
- Millisecond-only transition guards allowed an older microsecond sample to resolve a newer
  fault. Repeated copies of one source sample could also satisfy a three-overrun rule. Before
  caching/evaluation, the engine orders relevant topics by original timestampUs and sampleOrder;
  older and duplicate identities are ignored. Distinct sample orders at one timestamp remain
  separate evidence. Irrelevant topics no longer populate the engine's recent-value cache.
- Startup/restart must not reinterpret retained fan-out history as new fault evidence. The
  engine records retained publication identities and waits for new publications. This uses the
  publication object, so a later epoch may legitimately republish the same immutable frame
  object. Intermediate raw samples are retained for counting; a newest-value-only filter would
  incorrectly lose that evidence.
- Target-tagged and legacy raw subscriptions share one replay/overflow buffer. A small SharedFlow
  view unwraps frames without a forwarding coroutine or second queue. The live fan-out remains
  nonblocking with DROP_OLDEST, replay 100 and 4,096 extra slots. Each notified event adds an
  epoch envelope allocation; this is not a zero-allocation transport claim. The wrapper explicitly
  opts into coroutine SharedFlow inheritance and is isolated/tested for that API contract.
- Indexing, publication, observer creation, counter updates and clearing now use the same index
  lock. Merely holding the lock was insufficient: an unconfined subscriber can synchronously
  reenter clear/accept. Tests reproduced an old frame retained after a topic callback reset and
  epoch observers reading pre-reset counters. Reset clears indexes/replay/metrics before
  notification, advances an independently readable epoch, and preserves new-target values
  accepted from callbacks. Publication captures the epoch before callbacks and only updates a
  topic observer while the same frame remains current. A newer reentrant publication cannot
  be overwritten by its caller. Counter snapshots are coherent under the lock, allowing redundant
  atomic counter fields to become ordinary Long fields.
- Alert processing holds the transition mutex for the whole source frame; epoch resets and
  triage share it. Cancellation/source checks guard subsequent transitions and audible intent
  after persistence. Existing fixtures now feed the actual telemetry store, request available
  NT4 terminal cleanup, close databases and remove temporary files. The older fixture assertions
  still use wall-clock delays and remain a separate test-quality review scope.

## Validation

The original nine-method source suite reproduced eight failures in 28s; preserving intermediate
raw samples already passed. Evidence: `ARESLib-Kotlin/build/audit-pass30-before.log/xml`.

Intermediate compilation caught fixture integration mistakes: the NT4 API is disposeAndJoin,
and a nullable expected-list assertion needed an explicit element type. After correction,
73 methods ran: 71 passed and the two reset-callback cases failed as described above.
Evidence: `audit-pass30-publication-before-verified.log` and
`audit-pass30-publication-before.xml`. These are additional ordering defects, distinct from
the original source failures.

After the callback corrections, all 75 focused methods passed in 1m 56s
(`ARESLib-Kotlin/build/audit-pass30-focused-final.log`). The final publication-identity
refinement adds one more regression. New tests total 19 methods: 11 alert source cases and
eight publication/view/reset cases. Existing health, telemetry-store, alert-transition and
engine consumers are included in the focused selection. The real database occurrence test
now awaits observable stored rows; nonblocking fan-out acceptance is not a persistence barrier.
Invalid numeric fixtures use distinct sample identities to exercise each invalid input.

The final Studio gate passed in 3m 55s (`ARESLib-Kotlin/build/audit-pass30-studio.log`):

- 1,603 ordinary test methods passed, six opt-in methods skipped, plus 56 dashboard smoke
  methods and the performance-baseline method passed. Kover verification, release alignment
  and production Kotlin file-size checks passed.
- Publication/view source covered 7/7 executable lines (no counted branches); store source
  covered 92/92 lines and 33/42 branches; engine source covered 204/219 lines and 145/212
  branches. Default/nullable paths, input/lifecycle alternatives and portions of composite
  evaluation remain unexecuted. All-line execution does not close the store's remaining
  review scopes. Snapshot: `ARESLib-Kotlin/build/audit-pass30-kover.xml`.
- Dashboard load was 24.1193 ms, scrub p95 20.0128 ms and rapid-seek burst 6.1464 ms.
  The fixture persisted/restored 12,000 frames without drops. These are desktop fixture
  measurements, not physical timing or a throughput benchmark of the new publication view.
  Snapshot: `ARESLib-Kotlin/build/audit-pass30-dashboard-smoke.json`.
- Validation used the unchanged isolated candidate `17.0.3-rc.b81c0156add9` from the local
  release repository. No library or robot consumer rebuild was required for these Studio changes.
- Repository policy passed (`ARESLib-Kotlin/build/audit-pass30-policy.log`), including shared
  guidance and local links in 191 current documents.

The staged inventory contains 2,447 files: 145 fully reviewed, 59 partially reviewed and
2,243 pending, with no stale or orphaned records. Accounting is separate from line/branch
coverage and does not claim full monorepo or hardware validation.

## Limits and next work

Epoch identity proves which target owned a publication at ingestion. It does not authenticate
an upstream connection callback that arrives after reset; connection-generation ownership,
transport queues and replay-versus-live analysis policy still require review. A lossy UI fan-out
can drop samples under load, and an epoch envelope does not make it a durable recording stream.
Reset visibility follows coroutine scheduling and can wait for a current database operation;
this diagnostic path is not an actuator lease or safety controller.

Same-target cross-signal freshness, missing motor feedback, unit-sensitive power/velocity/CAN
rules, bounded current/loop windows, configured thresholds, session cache/history growth,
active-alert indexing and persistence-failure recovery remain open. Original sample identities
must be accurate: distinct physical samples with identical timestampUs and sampleOrder cannot
be distinguished. This pass preserves the existing millisecond-based composite windows.

Telemetry-store history ordering, explicit-observer retention, external latest-map access and
broader memory costs remain partial. Available test cleanup APIs are used, but process-lifetime
NT4 scope teardown and failure-path cleanup are not proven here. No visible Studio, live audio,
physical robot or electrical validation is claimed. Prior intermittent and opt-in checks remain
open. All changes are local; the monorepo audit goal remains active.
