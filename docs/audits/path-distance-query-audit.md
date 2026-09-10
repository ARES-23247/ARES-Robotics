# Path distance sampling and projection audit

Pass 56, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and corrections

Both sampling overloads treated distance spans below 1e-6 as zero, collapsing legitimate tiny
intervals to their first point. Their duplicated interpolation could overflow finite opposite
coordinates, velocities and curvatures. Tangent subtraction could overflow before angle wrapping.
Sampling now shares bracket selection and validation, uses every positive representable span,
uses convex interpolation without overflowing opposite-endpoint differences, and normalizes finite
raw angles before shortest-arc interpolation. Exact interior knots reuse their mutable source point.
Empty paths now return the same zero state through both overloads.

Nonfinite sample queries previously fell through or clamped to endpoints. Invalid raw headings
could be normalized to zero, and malformed sampled fields could reach outputs. Queries now reject
nonfinite distances, validate sampled raw fields and visited distance brackets, and leave the
primitive output unchanged on rejection. Negative finite query distances still clamp to the start.
This does not perform a complete path validation on each query; ownership limits are below.

Projection squared segment lengths and separations directly. Tiny geometry was discarded by a
1e-12 cutoff, huge geometry overflowed, and narrow arc windows were ignored by a separate 1e-6
cutoff. Nonfinite input could return a plausible zero, and windows wholly beyond the path could
return an arc length outside the path. Projection now checks finite ordered query bounds, clamps
the window to path extent, preserves nonzero tiny geometry and uses normalized projection/hypot.
When true separation exceeds Double.MAX_VALUE, quarter-scaled distances remain comparable rather
than all becoming an indistinguishable infinity. Axis-aligned paths use only their relevant axis.
That also fixes a regression in the initial correction: an overflowing perpendicular offset erased
a subnormal segment during common coordinate rescaling.

Every closest-point query scanned all segments, even for a narrow window near the end of a long
path. Random-access paths now binary-search the first intersecting segment and stop after the
window. Linked lists now use linear traversal rather than repeated indexing. Random-access
sampling is O(log N); windowed projection is O(log N + K) for K intersecting segments. Generic
non-random-access lists use linear traversal and may allocate iterators. The existing counted
sampling fixture implements constant-time indexing; it now declares RandomAccess explicitly,
retaining its original fewer-than-40-read bound rather than weakening that assertion.

The two legacy alliance helper bodies were identical. They now share one X-axis reflection
implementation, preserving center-origin behavior. KDoc no longer suggests this operation alone
implements corner-origin FRC mirroring; callers use the existing AllianceMirroring origin/symmetry
contract for that. Reflection validates raw sample fields before wrapping them.

MutablePathPoint.copyInto always allocated a Pose2d despite its no-allocation claim. It now reuses
the immutable pose when its raw values are unchanged. Moving poses still allocate, explicitly
documented. toPathPoint is documented as an independent mutable point, not an immutable object.

## Evidence

All 12 initial regression methods failed before corrections. A later oracle run found one failure
in the initial rescaling implementation (a huge perpendicular offset with a subnormal vertical
segment), corrected by the axis-specific projection. Baseline and expanded-failure XML/logs are
retained under `ARESLib-Kotlin/build/audit-pass56-before-evidence` and
`audit-pass56-expanded-before-evidence` with their corresponding logs.

The final focused gate passed 44 methods: 25 new and 19 existing sampling, closest-point and core
allocation checks. It includes 2,000 finite-exponent convex interpolation cases against exact
BigDecimal endpoint arithmetic, plus 1,000 independently computed decimal projection cases at
three power-of-two coordinate scales (3,000 comparisons). Other checks cover subnormal oblique
and axis geometry, reversed enormous axes, overflowing scale ratios, every sampled field, invalid
raw headings and query windows, duplicates, mutable aliases, copy/destructuring, reflection and
unchanged-pose reuse. Projection cases use tolerances appropriate to double rounding; they do not
prove arbitrary cancellation is accurate.

A 32,768-point narrow-window query requires fewer than 60 point reads. Linked-list query tests
require fewer than ten indexed reads. Five measured windows of 1,000 varied in-place sampling and
projection pairs each allocated zero bytes with prebuilt random-access points and primitive output.
An escaping 32-byte array per pair registered at least 48,000 bytes per window. The allocation gate
requires at least one zero window and at most 4 KiB in any window, not a strict every-call guarantee.

Focused Kover covers Path 122/123 lines and 14/14 methods; numerical helpers 34/34 lines and 4/4
methods; MutablePathPoint 20/20 lines and 10/10 methods; and PathPoint 5/5 lines and 1/1 methods.
Branch coverage remains incomplete. The unexecuted Path line is the non-random-access search
fallback, which is not reached by a stable valid ordered list whose final point bounds the query.
Full-file review covers both production files, all five new test files and the existing
PathSamplingAuditTest and FindClosestDistanceTest. Traced sequencer/controller files retain their
previous review status.

## Ownership, numerical limits and remaining work

Path remains a data class retaining caller-owned lists and mutable point payloads. No hidden
snapshot diverges from its public fields; mutations between queries remain observable, and its
public signatures/value operations remain intact. Callers must keep distances finite, nonnegative
and nondecreasing and sampled values finite, and must not mutate during a query. Only visited
points are validated; an unvisited malformed point may remain undetected. Full configuration/input
validation belongs at the owning construction or ingestion boundary and remains a separate audit
obligation. This pass does not claim that binary search can validate an entire mutable list in
logarithmic time.

Scalar interpolation is independent of trajectory dynamics. Distances are caller-supplied arc
labels; duplicate arc labels and zero-length spatial segments remain supported. Equal computed
projection separations keep the earliest visited segment. Extremely unequal/cancelling coordinates
are still limited by floating-point rounding; no exact-real-arithmetic nearest-point proof is made.
Read-count and allocation evidence is not a robot loop deadline or physical tracking measurement.
No physical robot, HIL, or live Studio-window validation was performed.

The sequencer still converts moving primitive samples into allocating Pose2d values, scans its own
events, and owns additional timeout/progress/lifecycle behavior needing complete review. The
drivetrain's estimator getter and Redux intent dispatch also allocate. Those paths, remaining
controller validation, and parser/configuration ownership are subsequent scopes.

## Validation checkpoint

Source `d9d1a903` binds candidate `17.0.3-rc.0303879d9315` to library tree
`0303879d9315b7fcd2f1e43c3567a93b959a41b0`. Full library validation passed in 1m49s:
1,487 tests, no failures/errors/skips, API checks, core Kover and isolated publication. The full-suite
allocation probe also measured five zero-byte windows. FTC/FRC/FTC starter/FRC starter gates
passed with 109/134/14/34 tests, generated-project verification and FTC assembly. Studio passed
in 3m19s: shared, gateway and app ordinary test tasks all reran (1,779 passes and six existing
opt-in skips), as did dashboard smoke (56 passes) and its performance baseline (one pass).
Studio coverage, version alignment and production file-size gates passed. Source policy passed, including source-tree identity, archive
hashes, agent guidance and links in 217 current documents (38 historical exclusions).


Final XML and SHA-256 manifests, plus core Kover, are retained in
`ARESLib-Kotlin/build/audit-pass56-verified-evidence`; focused and baseline evidence has separate
snapshots. The file ledger accounts for 2,577 tracked files: 338 fully reviewed, 73 partial and
2,166 pending, with no stale fingerprints or orphaned records. This is verified progress toward
the active audit goal, not complete monorepo coverage. All owned build processes are terminal;
no remote publication, push, merge or device operation was performed.
