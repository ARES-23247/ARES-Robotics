# CTRE swerve reader acquisition and freshness

Pass 50, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and changes

- A refresh exception could retain prior true validity flags and expose a partially updated
  frame. Refresh now revokes both signal and motion availability before any IO and publishes
  availability only after acquisition completes. Exceptions propagate unchanged. Unavailable
  numeric getters return NaN, including the pose/motion DTO, instead of an apparently healthy
  origin or partial data.
- Successful vendor status was treated as sufficient freshness. The reader now checks timestamp
  validity, finite values, finite/nonnegative age, and explicit age limits: 100 ms for current,
  absolute encoder, IMU and motion data; 750 ms for the 4 Hz diagnostics. RobotClock elapsed time
  since acquisition started conservatively ages the cache even without another refresh. Slow
  acquisition, rewind and signed overflow cannot renew authority.
- Wheel speed getters fetched vendor state four times, followed by another fetch in read().
  Refresh now takes one owning vendor state copy and captures one vendor-clock reading afterward.
  Getters perform neither vendor state fetches nor signal refreshes. Primitive snapshots isolate
  them from later vendor mutations. Unchanged immutable ARES pose/motion objects are reused.
- Status signals are cloned into reader-owned caches. Configuration failures now reject
  construction. Unused steer-current frequency requests were removed; odometry-owned yaw/rate
  frequencies remain unchanged. Each configured signal still refreshes once per acquisition.
- Short output buffers now reject before mutation, and every four-module getter preserves
  trailing storage. Unknown fault feedback uses bit 6 (0x40), distinct from healthy zero; the
  six existing drive/steer hardware, brownout and temperature bits retain their mapping.

## Vendor boundary and allocation tradeoff

The cached Phoenix 26.1.1 source JAR was inspected directly. Its getState implementation performs
a native fetch and updates shared callback state. getStateCopy provides the owning copy documented
for thread-safe consumption. Timestamp latency is vendor-current-time minus the stored timestamp;
the adapter computes all ages from one vendor clock read after acquisition. The original shared
state access and zero-GC read claim were not supported by that source or by the writer-only
allocation test. The cached source excerpts are under `ARESLib-Kotlin/build/audit-pass50-sdk`.

CTRE's [status-signal documentation](https://v6.docs.ctr-electronics.com/en/stable/docs/api-reference/api-usage/status-signals.html)
also distinguishes cached values from refresh operations. Pinned source, rather than changing
latest API pages, is the implementation reference for this pass.

The safe vendor copy allocates, and changed immutable ARES snapshots allocate. This is an explicit
tradeoff for owned, coherent data; the bridge documentation now says so. The source-call reduction
is not a measured whole-loop timing improvement. Cached getters remain allocation-free in the
host fixture test. Native CAN timing, contention, bandwidth and physical response are unmeasured.

## Evidence

An internal acquisition seam allowed regression tests without creating or commanding devices.
After that extraction, all three pre-cache regression methods failed in 8s: refresh failure,
stale/nonfinite acceptance and getter-owned acquisition. Baseline XML/log are preserved under
`audit-pass50-before*`. The seam extraction itself also changed native ownership/configuration;
these are not claimed as unchanged-binary hardware regressions.

The final focused gate passed 16 methods, API checks and Kover. Tests cover all 36 status and
timestamp-validity positions, malformed ages, each nonfinite value, non-Boolean fault values,
initial unavailable state, configuration rejection, both age limits, local expiry without refresh,
slow acquisition, clock rewind/overflow, ordinary/fatal failures, recovery, all 24 fault positions,
buffer lengths/tails, motion age/shape validity, snapshot ownership and once-per-frame acquisition.

Two 10,000-iteration windows after 50,000 warmup iterations measured zero allocated bytes for
cached reads. Counts remained at two state acquisitions and two vendor-time captures across two
refreshes, with no further vendor reads during 70,000 getter iterations. Immutable returned pose
snapshots remain unchanged when the source or later frames change.

Tests inject the acquisition source. They do not execute the native source constructor, signal
cloning/frequency JNI operations or real state-copy implementation. Those bindings were traced
against the pinned SDK and compiled; native/hardware integration remains an explicit coverage gap.
The new source adapter receives a partial ledger record. The reader/cache and three test files
are reviewed in full. FRCSwerveHardwareIO receives only scoped documentation/reader integration
credit; its remaining output, close, vision and estimator lifecycle behavior remains open.

## Contracts and limits

The signal group remains invalid if any of its configured signals fails validation, preserving
the previous conservative group gating. Motion freshness is checked separately; a diagnostic
fault need not discard independently fresh finite vendor odometry. An acquisition exception
revokes both groups. The reader does not neutralize actuators, establish enable/arm, or implement
an independent watchdog. The owning robot lifecycle must handle failures and unavailable input.

Phoenix timestamps are converted using the Phoenix timebase; cache residence uses RobotClock.
The combined age is conservative and can reject near-boundary samples early. Age bounds require
physical validation for the configured bus/device population. Finite data are not proof of
physical plausibility. Native state-copy allocation and the source adapter's hardware integration
must not be described as tested zero-GC behavior.

## Validation checkpoint

Source commits `fb5dfc20` and `ff23e2ec` bind final candidate `17.0.3-rc.b847a3d66a66` to library
tree `b847a3d66a66eca4f72e95cd236d16135fd14153`. The initial candidate
`17.0.3-rc.0738d63fd572` passed its full gate in 19s, then was superseded by the one-clock refinement;
its version was not reused for revised bytes.

The final full library/API/Kover/local-publication gate passed in 18s: 1,365 methods, zero
failures/errors/skips. The FRC module tests reran; unchanged modules include explicit cache/
up-to-date reuse. Full Kover reports 96/97 reader lines and 129/144 branches. The native source
adapter remains 0/34 lines; it is not granted full-file completion credit.

FTC, FRC and their starters passed 109, 134, 14 and 34 methods, plus generated-project checks
and FTC debug assembly. Studio passed in 19s: ordinary suites reused 1,779 passing methods and
six existing opt-in skips; 56 dashboard methods and one performance baseline reran. Kover,
release alignment and production file-size gates passed against the exact final candidate.

Policy verified source identity, unchanged archive hashes and links in 211 current documents
(38 historical records excluded). Logs are `audit-pass50-{library,ftc,frc,ftc-starter,frc-starter,
studio,policy}.log` under ARESLib-Kotlin/build. Invoked XML/hash manifests and full Kover are saved
in `audit-pass50-verified-evidence`; baseline/focused evidence is preserved separately.

Inventory: 2,548 tracked files, 292 reviewed, 76 partial and 2,180 pending, with no stale or
orphaned records. Mockito 5.23.0 is available in the local dependency cache for a follow-up
native-adapter mock integration pass; that work is still pending. The goal remains active.
No push, merge, remote publication or physical device action has occurred.
