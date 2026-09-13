# Tuning live request and acknowledgement audit

Pass 238 follows promotion lifecycle pass 237 into Studio live requests, NT4 publication,
robot results and telemetry sampling. The preceding pass finished clean at
`0f4a9261866c7f39ced5089f513bc2601c78f3b0`. Source checkpoint
`701a4482a8d7b2b6e6aa329a24aab8a8e0c79660` binds library tree
`526a048d8dfb1092e89be95c8e906e9fbc516027` to local candidate
`17.0.42-rc.526a048d8dfb`. Final Studio tree: `3d59d0221c438f5e6181a8eef3d11a9dace5ca18`.

## Findings and fixes

1. **Queued requests outlived the draft, project or connection that created them.** Live
   requests now capture the intent's project/load revision, profile, proposal and provenance
   before coroutine scheduling, then recheck them under the request mutex. The connection has
   a distinct ready-session identity, so even a conflated disconnect/reconnect invalidates a
   queued request. Late acknowledgements cannot overwrite another project's status. Offline
   requests do not reach the publisher.
2. **Nonce reuse could accept an old result.** Reservation now happens only under the request
   mutex and advances past the local counter, observed requested/processed nonces and the
   decoded atomic acknowledgement. It rejects exhaustion before sending. Malformed or
   inexact numeric counters cannot silently become valid request identifiers.
3. **Separate result topics could falsely report an apply.** A server can deliver a new
   `ProcessedNonce` with an old `LastResult=APPLIED`, irrespective of the robot's write order.
   The robot now publishes one versioned `Acknowledgement` string containing nonce and result;
   Studio requires a valid payload with the exact request nonce on its original connection.
   The shared codec rejects noncanonical integers, unsupported formats, extra fields,
   oversized input and malformed result identifiers. Metadata refresh preserves the last
   acknowledgement. Legacy scalar topics remain diagnostic; scalar-only robot software
   leaves the live result unknown until robot code is updated.
4. **Value and nonce publication had separate acceptance and connection boundaries.** The
   tuning publisher now encodes the typed Requested update followed by RequestNonce in one
   binary frame, using the existing shared NT4 encoder. It captures the session and rechecks
   its identity after publisher registration. A full outgoing queue accepts neither field;
   a reconnect cannot move the request to a replacement session. Both tuples use server
   receipt timestamps, including while simulator time is paused. Existing generic publishers
   retain their separate contracts.
5. **Rejections and malformed telemetry produced misleading state.** Explicit robot rejection
   is reported separately from an unknown acknowledgement outcome. Failed enqueue and nonce
   exhaustion are known unsent requests. Cancellation propagates and releases serialization.
   Non-finite consumer-support samples cannot authorize requests; malformed explicit Boolean
   text does not fall back to an older numeric value. Valid typed Boolean/text/enum values
   keep their NT4 types. Live testing never writes canonical profiles.

The original nine-method view-model baseline failed all nine intended behavior assertions.
A separate reordered-acknowledgement regression failed against the scalar-only design,
justifying the shared producer/consumer contract change. Both failing baselines are preserved.

## Efficiency and focused evidence

Bulk live testing uses one coroutine per batch while preserving one-request-at-a-time
acknowledgements. The five-type test asserts one active batch job, unique successive nonces,
correct values and unchanged canonical bytes. Telemetry sampling caches topic paths for each
immutable catalog and replaces intermediate collections with one pass building the three
required maps. A load-generation/catalog guard prevents an old sample batch from replacing
new-project observations. No UI frame-time or whole-robot-loop improvement is claimed.

There are 32 new methods: 19 live-request cases, seven wire/loopback cases, four codec cases
and two robot acknowledgement cases. The final focused runs passed 63 Studio and 55 library
tests. They cover queued context changes, reconnect, stale/reordered acknowledgements,
rejection, timeout and cancellation retries, exact nonce boundaries, all five parameter types,
bounded enqueue, type conflicts, malformed input, robot arm/consumer outcomes and preservation
of accepted controller state when acknowledgement publication fails.

The loopback case uses the real public client API, an owned NT4 server on an OS-assigned port,
and all five parameter types. Fixture development corrected the mock's suspended send path,
repeated server flushing while delivery was pending, and readiness polling for positive ports
(the server can report -1 before binding). These were test-fixture corrections, not relaxed
production checks. The fixture closes its client, database, server and temporary directory.
Idle polling remains free of acknowledgement encoding/publication work.

## Candidate validation

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,884 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,116 | 6 |

There are 5,558 passing results, zero final failures/errors and six unchanged Studio opt-in
skips. This includes 2,884 library and 2,165 Studio passing results. Gradle suite evidence
includes executed, up-to-date and cached results; focused tests are not counted twice.
API checks, generated-project checks and FTC APK assembly passed. All 410 candidate file
hashes were reverified after consumer validation. The four normalized starter archives differ
only in release version properties. Monorepo policy passed, including 400 current-document
link checks and 38 historical skips. Both full-library 20,000-poll allocation windows remain
zero, matching the focused run. Unchanged MicroPython, repository tooling and browser suites
were not rerun in this pass.

## Coverage and limits

The ledger accounts for 3,043 tracked files: 1,360 reviewed, 189 partially reviewed and
1,494 pending, with zero stale or orphaned records. These are scoped file-review and
appropriate-validation counts, not universal executable test coverage.

TuningViewModel remains partial for exhaustive load/stage/pull interleavings. The general NT4
client lifecycle, generic outbound registration/retry paths, connected-robot project identity
binding and observation-cache freshness across reconnection remain separate boundaries.
TuningManager retains its serialized-owner contract; concurrent/reentrant consumers remain
partial. The API ledger records the generated addition and API check, not an audit of every
pre-existing public contract. The tuning guide's new transport section is verified; its other
ownership/persistence claims retain partial review status.

No physical robot, HIL, hardware timing, rendered Studio window, remote CI, push, merge,
release or deployment was performed. The owned loopback test is software integration evidence.
The overall monorepo audit remains active.

Machine-local evidence: `ARESLib-Kotlin/build/audit-pass238-verified-evidence/`, including both
failing baselines, focused/full XML and logs, candidate identities/hashes, archive comparisons,
policy output and the final summary.
