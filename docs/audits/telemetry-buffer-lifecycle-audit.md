# Telemetry buffer lifecycle and contract audit

Pass 192, 2026-09-12. Reviewed nine previously pending Studio telemetry files:
drive-frame validation and history recording, import batching, UI fan-out, lighting
decoding, topic constants/metadata, topic normalization and the decoder base contract.

## Confirmed fixes

The drive-history recorder's conflated channel remained open after its worker
stopped. Offers returned success even though no consumer remained. Completion now
cancels the channel and releases its pending snapshot. Offers also check worker
activity, covering an already-cancelled scope before its completion callback runs.
The recorder still cannot suspend the drive publisher, and analytics remain
best-effort. Three recorder cases cover conflation, normal cancellation and
construction with an already-cancelled scope.

`FrameBatcher` updated timestamp bounds before running the caller's key transform.
A rejected frame could therefore change the session's time range without changing
its accepted-frame count. Bounds now update after the transformed frame enters the
buffer. Zero and negative batch sizes are rejected. Four real-DuckDB tests cover
invalid sizes, rejected transforms, persistence across automatic/final flushes
and session isolation. Both existing tests were restored unchanged after detecting
that the initial regression-test edit had replaced them; only the two new failure
cases remain as additions.

Unchanged keys reuse their existing frame object. Flush no longer duplicates the
buffer's reference list: database ingestion consumes it before returning, and this
batcher requires sequential awaited calls. The database implementation was inspected
at this boundary; it does not retain the caller's list after insertion. This removes
two sources of redundant allocation without claiming a measured wall-time speedup.

## Contract review

UI fan-out documentation now distinguishes the best-effort source-rate telemetry bus
from the separate durable recording queue. Documentation also distinguishes frame-count limits from payload-byte limits and
describes the batcher as a sequential buffer, not a channel. Decoder implementations
must bound record payloads independently. `Nt4Topic` has read-only properties but
does not copy a caller's map, so its documentation no longer promises unconditional
immutability/thread safety.

`TelemetryTopicExtractor` delegates to the library normalizer, which only strips
leading slashes. Its stale claims about renaming motors, converting interior
separators and removing illegal characters were corrected. It still returns a frame
copy with the normalized key. No normalization behavior changed.

The drive validator's finite/safe-integer bounds, per-axis limits, neutral initial
session, sequence ordering, client-time monotonicity and explicit commit/reset
boundary were traced to the shared protocol and serialized outbound publisher.
Lighting parsing retains canonical hardware IDs and distinguishes indicator values
from Prism codes; existing parser/display tests passed. Topic constants were read
against FTC publications, the shared normalization convention and FRC selection
documentation. Complete FRC request/consumer compatibility remains open.

## Validation and remaining work

Six baseline cases produced four failures: both recorder shutdown cases, invalid
batch size and rejected-transform statistics. An initial fixture used timestamps
outside `TelemetryFrame`'s domain; it was corrected before obtaining this baseline.
A refinement run exposed the already-cancelled-scope completion timing before the
active-worker check was added. The initial focused run passed 45 tests. After restoring both original batcher
tests and removing the overlapping replacement boundary test, all 46 focused
telemetry/integration tests pass, with no failures or skips.

The interrupted full-suite attempt had no terminal result and is not counted.
Full Studio app validation on the restored test set passed: 1836 tests,
0 failures, 0 errors and 6 opt-in/environment skips (1830 executed
successfully). Skips cover the three opt-in generated-project integrations, native
file chooser, performance baseline and physical dashboard target. Evidence is under
`ARESLib-Kotlin/build/audit-pass192-verified-evidence/`.

`FrameBatcher` remains partially reviewed: Int-sized total counts and database
failure/retry behavior, especially mixed-session transaction boundaries, need
further work. The frame-count bound is not a guarantee against oversized records
or a caller continuing to append after repeated insertion failures.

UI fan-out's latest-per-topic coalescing, epoch reset and replay clearing were
reviewed with its two existing tests. Disposal/retained pending entries, topic
cardinality bounds and additional races remain open. The topic constants file
remains partial pending complete request routing verification. The larger publisher,
ingestion and database implementations were inspected only at relevant boundaries.

No library, archive, release identity or robot code changed. Tests use the existing
validated local candidate `17.0.10-rc.271e2518a412`. No physical robot, network target
or visible Studio window was exercised.
