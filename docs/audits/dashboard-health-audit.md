# Dashboard health calculations - pass 26

This pass covers the dashboard health sampler, logging metric conversion and the rate
calculation it owns. It traces replay cache counter updates to establish the correct ratio.
It does not extend full-review credit to the telemetry store, replay engine or robot logger.

## Findings and changes

- Startup used zero as the previous lifetime counter, counting pre-existing telemetry and
  log bytes as traffic in the first observed second. Missing logging counters similarly
  produced a burst when an already-running robot first supplied them. Rates now establish
  an observed baseline before reporting differences.
- A target switch could produce a false rate when the new target's counters exceeded the
  previous target's. Rate baselines now use the telemetry store's target epoch. A decreasing
  counter also establishes a new baseline without claiming a measured rate across a reset.
- Zero or backward elapsed time was clamped to one millisecond while consuming the count
  delta, producing 3,000 frames/s for three frames with no elapsed time. Invalid intervals
  now report zero and retain pending progress until time advances beyond the last baseline.
  Positive elapsed intervals retain their actual duration, including submillisecond inputs.
- Robot byte counters can individually saturate at Long.MAX_VALUE. Their combined increase
  could overflow signed arithmetic and report zero. Unsigned primitive totals/differences
  retain both counters and small increments before conversion to a floating point rate.
- Prefetch hits are already included in installed window loads by applyWindowLocked.
  Dividing hits by loads plus hits counted them twice: three hits among four loads displayed
  43% instead of 75%. The ratio now divides hits by loads and bounds the result to [0, 1].
- The dashboard drop count was hard-coded to zero despite unmeasured lossy UI fan-out.
  An unavailable nullable count now displays N/A; the separate measured robot log drop count
  is preserved. This does not add a fan-out loss counter.
- Disposal no longer tracks and cancels the same sampler job separately: cancelling its
  owning scope already cancels the job. Idempotent disposal remains covered.

The sampler has an injectable dispatcher with the existing production default. Tests use
virtual scheduler time and a separate controlled monotonic clock; no timing sleeps, network
connection, physical robot or visible Studio window is needed. Existing status thresholds,
rolling database p95 semantics and distinct robot drop counts remain intact.

## Validation

Baseline evidence is retained under `ARESLib-Kotlin/build/audit-pass26-*.log/xml`. The initial 16-method selection reproduced six failures:
startup history, first logging observation, target changes, cache ratio, duplicate time and
backward time. The separate two-method boundary run reproduced signed byte-delta overflow
and the submillisecond clamp (`audit-pass26-boundaries-before.log/xml`). Both tests failed
against the old arithmetic. The small-increment case near a signed boundary already passed
and protects against a less precise floating-point-total replacement.

The first focused rerun failed only an exact floating-point assertion: 999,999,999.9999999
rather than 1,000,000,000 frames/s. The test now allows 0.000001 frames/s absolute error at
that scale; the original 1,000 frames/s regression remains decisively outside it. Evidence
is in `audit-pass26-focused.log` and `audit-pass26-focused-rounding.xml`. No production
threshold or timeout was relaxed.

Eighteen new virtual-time methods cover baselines, elapsed intervals, signed nanoTime wrap,
epoch/counter resets, logging availability, large totals/small differences, rotation,
prefetch ratios, threshold/status precedence, source-field units and idempotent disposal.
The existing robot logging mapping test and replay window/cache suites also ran. The first
full gate passed (`audit-pass26-studio.log`, 2m 56s); the final gate additionally includes the
nullable dashboard-drop field and N/A display.

Final validation passed:

- 1,503 ordinary Studio methods passed, six opt-in methods skipped; 56 dashboard smoke
  methods and the performance-baseline method passed. Kover verification, release alignment
  and production file-size checks passed in 2m 54s (`audit-pass26-studio-final.log`).
- Rate helper: 14/14 lines and 8/8 branches. Health sampler/models: 105/106 lines and 51/56
  branches. Unexecuted paths are the default dispatcher parameter, some nullable/finite
  logging-input combinations and the loop's inactive-condition exit (tests cancel delay).
  These counters do not establish every source/concurrency or rendering scenario.
- Dashboard replay load 17.2181 ms, scrub p95 20.3042 ms and rapid-seek burst 3.2851 ms;
  12,000 frames persisted/restored without drops in this fixture. Snapshot:
  `ARESLib-Kotlin/build/audit-pass26-dashboard-smoke.json`. No rendered-window claim is made.
- The unchanged library candidate `17.0.3-rc.b81c0156add9` came from the isolated local release
  repository. Studio-only changes do not require rebuilding the unchanged robot consumers.

Repository policy passed, including guidance and links in 187 current documents. The staged
inventory accounts for 2,426 files: 114 fully reviewed, 52 partially reviewed and 2,260 pending,
with no stale or orphaned records. Review accounting is not universal test/branch coverage.

## Limits and remaining scope

Rates are estimates between dashboard observations. Logging topics arrive separately, so
file rotation or reconnection can expose a temporarily mixed pair; this pass does not add
an atomic robot logging snapshot or a freshness protocol. A counter reset cannot reconstruct
unobserved traffic. The target epoch handles explicit store clears; reconnects that do not
change epoch or decrease counters cannot reliably identify a new counter lifetime.

Signed nanoTime subtraction supports ordinary wraparound intervals shorter than 2^63
nanoseconds. Negative elapsed differences are ignored; multi-century gaps are unsupported.
Telemetry values arrive as Double and may already have lost integer precision above 2^53;
unsigned arithmetic preserves the decoded integers, not precision absent from the source.
The nullable UI drop field expresses unavailable accounting; it does not prove lossless
fan-out. Existing threshold alerts include lifetime counters and can remain latched
until their source resets. Topic history sampling still visits retained topic buffers once
per interval. Producer snapshot coherence, recording memory, stale metrics, diagnostics and
schema/backup behavior remain separate scopes. Prior intermittent checks, opt-in cases and
physical hardware validation remain open. The system health card's other live/replay source
selection, lifetime/stale display values and missing robot-counter presentation still need
review. Ingest includes every accepted store frame, including replay/silent updates, rather
than measuring network transport exclusively. All changes stay local.
