# Vision frame freshness and recovery continuity audit - pass 209

Scope: the shared frame gate and the FTC/FRC tracker boundaries around polling, connection
state, per-source duplicate detection, empty/stale observations and recovery continuity.
The complete tracker files and their existing tests were read to trace this boundary, but
recovery quality, candidate selection and estimator policy remain partially reviewed.
All work remains local in the isolated audit branch.

## Confirmed issues and fixes

Both trackers compared capture time with `now + 50`, which can overflow near Long.MAX_VALUE
and reject a current frame. The new core VisionFrameGate compares ordered age/lead differences
without adding to either endpoint and rejects subtraction overflow. It preserves the existing
positive-capture-time requirement, inclusive 50 ms future tolerance, FTC 500 ms maximum age
and FRC 1,000 ms maximum age. Native microsecond clock conversion is not part of this gate.

Both duplicate tables stored only eight sources and evicted a hash-selected slot on overflow.
With twelve sources, repeating the identical camera batch could be consumed again. Source
storage now grows when a new configured identity is encountered and retains every known
source. The unnamed empty source no longer aliases a camera literally named `default`.
Repeated nonzero frame IDs and non-increasing capture times are rejected independently;
a camera may restart its frame numbering if its canonical capture time advances. Accepted
history stores primitive timestamps/IDs, not borrowed mutable observations.

The gate identifies clock rewinds as a new frame-history epoch. A forward update gap beyond
the platform age limit breaks continuity while preserving duplicate history. Resetting this
gate does not reset the Store's estimator history or guarantee acceptance of rewound data
by that separate estimator. The tracker tests establish renewed dispatch, not estimator
reinitialization or successful fusion across arbitrary clock changes.

Trackers now clear the borrowed input snapshot before polling. A partial read cannot retain
a prior connected flag or observation batch. Disconnected observations cannot reach fusion
or reseeding, and update failures invalidate published inputs/status before propagating the
original Throwable. Recursive updates are rejected. A later successful poll can recover;
these trackers do not take ownership of camera closure. FTC clears its last displayed pose
on disconnect/failure and expires it even when an IO keeps returning a stale nonempty batch.
Previously, only an empty batch triggered that expiry.

Recovery accumulators now reset after an empty observation poll, disconnected camera, update
failure, clock rewind or excessive update gap. Old consensus also expires while the robot
continues polling duplicate/stale frames. Brief duplicate polls within the platform age
window retain consensus, since camera and robot loop rates can differ. FRC fusion-mode changes
reset recovery immediately, including disable/re-enable transitions between two polls.
FRC stationary dwell is updated from drive observations even when no camera target is present;
motion during an empty poll must earn a new stationary dwell before enabled recovery resumes.

FRC previously dispatched cached frames to Redux repeatedly after its hardware fusion path
had already rejected them as duplicates. It now dispatches only fresh frames, and skips that
action entirely when none are fresh. This avoids repeated immutable observation copies,
filter work and misleading diagnostic increments. Tracker loops use indexed traversal and
reuse their scratch lists. Both products share the same freshness implementation.

## Validation

All 21 initial tracker regression methods failed against the unchanged production code:
ten FTC and eleven FRC cases. Their failing XML is retained. The completed pass adds 38
methods: ten core gate tests, thirteen FTC tracker tests and fifteen FRC tracker tests.
The final focused evidence covers 66 passing methods, including 23 existing tracker tests
and five zero-GC regressions. Public API checks passed; the API addition is VisionFrameGate,
with its constructor, beginUpdate, isRecent, accept and clear methods. Existing tracker
constructors and public signatures are unchanged.

Freshness agrees with exact BigInteger arithmetic in 20,400 comparisons, including Long
extremes, zero/negative captures, inclusive age/skew boundaries and very large configured
limits. Tests also cover 65 sources, duplicate IDs versus capture times, camera restart,
rejected-frame history, explicit clearing, rewinds and overflow in update continuity.

The warmed gate benchmark recorded zero allocated bytes over 10,000 polls of twelve
preallocated camera observations, with each observation tested once fresh and once duplicate.
The observed desktop time was 488.2 ns/poll in that run. This isolates the gate: it does not
measure the complete tracker/Store pipeline, network/SDK calls, Android/roboRIO behavior,
GC pauses or physical robot loop deadlines. Source storage may allocate at a new topology
high-water mark; known-source steady-state polling does not allocate in this benchmark.
The linear source lookup remains O(number of configured cameras).

Source commit: `d185de2f2247bb033dd3b38900bc5578bd0fd5d5`.
Library tree: `0599f2cfaa63e12f2f1a6593fbbfd90d624a1552`.
Candidate: `17.0.16-rc.0599f2cfaa63`.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,369 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,020 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,178 passing results, zero failures/errors and six
existing Studio skips. The skips cover three opt-in starter integration scenarios, the native
file chooser, the dashboard performance baseline and physical dashboard validation. FTC and
its starter also passed generated-project verification and APK assembly; FRC and its starter
passed generated-project verification. The candidate manifest records 410 publication files,
and their hashes were reverified after consumer validation. All four new starter archives
differ only in release version properties. Monorepo policy passed, including source identity,
version/archive pins, shared guidance and links in 370 current documents; 38 historical records
were explicitly skipped.

Evidence directory: `ARESLib-Kotlin/build/audit-pass209-verified-evidence/`, including
failing baselines, focused/full XML, allocation output, candidate manifest, starter archive
content/hash comparisons, policy output and the final summary. Gradle up-to-date/cache
results are distinguished from rerun work; focused tests are not counted twice.

## Remaining scope

The tracker records remain partial. The next pass must validate recovery physical/ambiguity
and measured-drive validity gates, FTC candidate selection versus the final batch EKF decision,
recovery means and thresholds, and FRC estimator-time conversion/history failure behavior.
The existing tracker fixtures also need scrutiny when validating drive validity: many FTC
fixtures initialize default DriveState flags to false while expecting alignment. This pass
used explicit valid drive observations for its new freshness tests and did not redefine the
recovery quality policy to make them pass.

FTC base lifecycle rejects reads/updates after close; FRC camera ownership is registered with
the hardware registry. Full post-close behavior in the broader FRC base loop, producer clock
coherence and factory cleanup require their own regression evidence. Limelight producers,
factory rollback, prior spline approximation and other open ledger scopes remain active.

The ledger now accounts for 2,925 tracked files: 1,140 fully reviewed, 164 partially
reviewed and 1,621 pending, with zero stale or orphaned records. Six file records were closed
(the shared gate, its test, two new tracker test files, the tracker interface and this report).
Two existing tracker test files received partial records; the production tracker records
remain partial with the remaining quality/estimator scope stated explicitly.

No physical robot, rendered Studio session, remote CI run, deployment or public release ran.
The complete monorepo audit goal remains active.
