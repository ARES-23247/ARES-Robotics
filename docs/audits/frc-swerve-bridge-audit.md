# FRC swerve bridge lifecycle, feedback and estimator audit

Pass 53, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and corrections

The bridge could destroy the native drivetrain repeatedly, send commands after close, and return
fresh-looking cached measurements after releasing native resources. If brake and close threw the
same exception, self-suppression masked the original cause. Pinned Phoenix 26.1.1 source calls
`JNI_DestroyDrivetrain` unconditionally in close without clearing its drivetrain ID; assuming
repeatability at the wrapper boundary was unsafe.

One private monitor now serializes bridge calls with close. Close latches the closed state and
invalidates the cache before attempting brake and native destruction once, including on failures.
It preserves original/distinct cleanup exceptions. Closed native operations reject calls; safe and
close are no-ops, history returns false, and cached getters return unavailable values. This is
at-most-once cleanup, not evidence that a failed native destruction released every resource.

The bridge also forwarded motion without checking feedback freshness or reported faults. The FRC
power manager can use fresh PDH current independently of swerve feedback, so its scale is not a
substitute for this gate. Motion now requires the reader's fresh signals and pose/motion cache,
with all 24 reported drive/steer fault flags clear. Invalid refreshes immediately request X-brake;
acquisition exceptions do so before propagating. Denied writes also brake. The brake path stays
outside the refresh exception catch to avoid retrying a failed brake within the same call.

Estimator mutations previously accepted nonfinite pose/time inputs. Invalid explicit covariance
fell back to the two-argument API, reusing retained vendor covariance. Historical sampling returned
true and partially published invalid coordinates. These inputs are now validated before native IO;
history publishes three finite values atomically and otherwise preserves caller storage. Heading
validation checks rawRadians: the wrapped getter deliberately maps nonfinite values to zero.
Reset revokes the previous snapshot and brakes before native reset; motion waits for valid refresh.
The empty initializer was removed. Vision matrix storage and cached request objects remain reused.

## Test evidence

All seven initial baseline methods failed in 10s. Two additional transition regressions failed
against the intermediate fix: invalid refresh did not immediately brake an active command, and
reset retained the old snapshot. The raw-heading grid also caught an intermediate validation error.
Baseline XML/logs are retained under `ARESLib-Kotlin/build/audit-pass53-before-evidence` and
`audit-pass53-transitions-before-evidence`; intermediate logs preserve the heading failure.

The focused gate passed 68 tests with no failures or skips: 27 new bridge methods, two allocation
probe calibration methods, and 39 existing reader/writer/binding methods. API checks passed.
Bridge Kover covers 98/98 lines, 24/24 methods and 81/82 branches; the reader covers 101/101 lines
and 22/22 methods across this combined scope. Counters do not establish exhaustive input coverage.

Tests exercise all reported faults, pre-refresh/stale/invalid motion, immediate brake transitions,
refresh/reset/native failures, same/distinct cleanup errors, concurrent closes, close during an
in-flight write, post-close native rejection and unavailable cached reads. Numerical grids cover
every nonfinite pose/standard-deviation member, zero/negative covariance, invalid timestamps,
finite units/timebase forwarding, matrix reuse, absent/bad history, buffer lengths and untouched
tails. Tests mock native devices and do not command physical motors.

## Allocation evidence and limitation

Strict two-window probes were unstable: the bridge getter probe reported 248, 192 and 56 bytes
on different runs, and the existing numerical writer probe reported 64 bytes. Pass 52 had also
recorded 400 bytes. Warming the same loop body was insufficient to eliminate this. Exact allocation
sites remain unresolved; the failures are retained, not attributed conclusively to the JVM.

The three probes now share the **existing** five-window CTRE writer criterion: at least one entire
window must allocate zero bytes, and total transient allocation must not exceed 64 KiB. This
explicitly relaxes the two strict probes; it does not assert every window is zero. The negative
control forces a ByteArray to escape on every iteration and is rejected (480,000 bytes per window).
The final focused getter, varied writer, original writer and empty-control windows each measured
`[0, 0, 0, 0, 0]`. Getter and varied-writer windows contain 10,000 iterations; original-writer
windows contain 20,000. Unsupported instrumentation is a JUnit skip. Acquisition/native execution
is outside these probes, and small intermittent allocations below the cap remain possible.

## Ownership and remaining scope

Bridge synchronization prevents overlap among bridge calls, not arbitrary external/native access.
The caller transfers close ownership after successful construction. Native device callbacks,
vendor shutdown hooks and other direct drivetrain users are outside this monitor. A blocked native
call can delay close indefinitely. No physical stopping, CAN latency, real-time deadline or HIL
claim is made. Explicit enable/arm, correct hardware configuration and whole-robot fault latching
remain upstream; a new valid refresh only restores this local feedback gate.

Vision covariance is checked as finite positive standard deviation, not against physical camera
confidence limits; outlier filtering and FPGA-to-vendor clock conversion remain in FrcVisionTracker.
That surrounding file and FrcSwerveRobot are still partial reviews. A separate raw-heading check in
TrajectoryPlanning uses wrapped heading for validation and needs investigation in a later pass.

## Validation checkpoint

Source `0226495e` binds candidate `17.0.3-rc.287afbd1d312` to library tree
`287afbd1d312a9156961810b41fe32974d3bcea3`. Full library validation passed in 23s:
1,414 tests, no failures/errors/skips, API checks, Kover and isolated publication. Full-run
allocation windows matched the final focused values above, including the rejecting negative control.

FTC/FRC/FTC starter/FRC starter gates passed with 109/134/14/34 tests, generated-project verification
and FTC assembly. Studio passed in 17s: ordinary tests were UP-TO-DATE (1,779 passed-result tests,
six existing opt-in skips); dashboard smoke (56 tests) and performance baseline (one test) reran.
Coverage, version alignment and file-size gates passed. Policy verified 214 current documents,
38 historical exclusions, source identity and archive integrity. XML/hash manifests and Kover are
preserved in `ARESLib-Kotlin/build/audit-pass53-verified-evidence`; build/cache reuse is not a claim
that every test executed afresh. Two old Kotlin daemon diagnostic files materialized during the
build and were preserved under `build/audit-pass53-kotlin-errors`; the current build exited zero.

The inventory records 2,560 files: 310 reviewed, 74 partial, 2,176 pending, with no stale or orphaned
records. The goal remains active. Nothing has been pushed, merged, remotely published or deployed
to hardware.
