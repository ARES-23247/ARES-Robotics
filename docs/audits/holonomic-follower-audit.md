# Holonomic follower lifecycle and loop audit

Pass 55, 2026-09-10. Local branch `codex/robot-loop-math-audit`.

## Findings and changes

The follower deduplicated markers by command name, suppressing later occurrences of the same
command. Every update scanned all markers and newly seen names allocated hash-set entries.
Setup now owns an array snapshot sorted by distance, preserving authored order at equal distances,
including positive and negative zero. A final regression caught the default Double comparator
reordering signed-zero ties; numeric comparison now treats them as equal.
A cursor consumes each occurrence once. Retreating progress does not replay markers; starting a
new path resets them. Mutation of the caller's event list cannot change an installed schedule.
Update work is O(1 + crossed markers), excluding callbacks, controller and drivetrain operations;
snapshot allocation and sorting happen only at setup. Bursts of crossed callbacks still cost O(k).

New paths and stops previously retained integral and derivative history. One regression generated
-1.8 m/s at a new path's already-reached target. These transitions now reset all three PIDs.
A lifecycle revision prevents a callback's stop or replacement from being overwritten by the old
update, and prevents subsequent old callbacks from running. Recursive updates are rejected.
Stopping retains marker progress and permits a later explicit update, preserving sequencer pauses.
Starting a path configures markers and history; it does not write output or independently arm motion.

Invalid raw headings previously wrapped to zero, allowing a 1 m/s command from invalid feedback.
Invalid target coordinates, velocity, curvature, progress or dt could consume/execute a marker
before the controller rejected the sample. The follower now checks finite raw headings, coordinates,
position differences and scalars, nonnegative progress, and positive finite dt before callbacks.
Invalid input requests neutral output and resets history without consuming markers. Infinite tangent
is invalid; NaN preserves the documented automatic tangent fallback. Event thresholds are checked
at setup. Invalid setup requests neutral output, preserves the previous marker schedule/progress,
and propagates its original failure.

The command is computed before callbacks, so mutating the target during a callback cannot change
the command that was validated. Callback/output exceptions still request neutral output and propagate;
distinct cleanup errors are suppressed and the failed marker is consumed. An already attempted,
failed invalid-input stop is not immediately retried. Failure-path stderr formatting was removed.
Same-instance cleanup preservation is explicitly tested; Kotlin's prior suppression helper already
handled that case, so it is not counted as a newly reproduced defect.

KDoc now describes the actual robot-relative output, derivative on measurement, lack of angular
feedforward, virtual marker progress, lifecycle semantics, and external safety responsibilities.

## Evidence

The initial 14-method regression run reproduced ten failures. An additional signed-zero sorting
regression failed against the first corrected implementation; its XML/log are retained in
`audit-pass55-zero-before-evidence` and `audit-pass55-zero-before.log`. Baseline XML/logs are retained in
`ARESLib-Kotlin/build/audit-pass55-before-evidence` and `audit-pass55-before.log`. The first fixed
run exposed a test-fixture mistake: clearing the instrumented list itself reads that list. The read
counter baseline was moved after the caller mutation; production behavior already passed the
snapshot assertion. That failing run is retained as `audit-pass55-focused.log`.

The final focused gate passed 32 methods: 20 new and 12 existing follower, sequencing-failure and core
allocation checks. Coverage includes duplicate and unsorted markers, distance ties, progress retreat,
mutable input ownership, stop/replacement callbacks, callback mutation/failure, cleanup identity,
invalid raw-heading grids, nonfinite inputs/dt, overflowed position differences, PID reset, recursive
updates, robot-frame output and NaN tangent fallback. Focused Kover covers 73/73 follower lines,
8/8 methods and 75/82 branches; that is not exhaustive input or hardware coverage.

An earlier fixed run measured five zero-byte windows of 1,000 updates crossing unique markers.
The final focused run measured [152, 0, 0, 0, 0] bytes with a cached-pose test double and scalar
output sink; the isolated 152 bytes are not attributed to a proven source. A volatile escaping 32-byte array per callback
registered about 48,000 bytes per window, confirming the probe detects recurring allocation.
The gate requires at least one zero window and no window above 4 KiB; observed zero windows do
not establish a strict allocation guarantee for arbitrary drivetrain or callback implementations.

## Integration and remaining work

This is a single-loop facade, without thread safety, sensor-age validation, enable/fault latching,
or physical stopping guarantees. Callbacks and drivetrain calls can block or allocate. `DriveSubsystem`
reads the Redux estimator but its pose getter creates a Pose2d, and intent dispatch allocates;
those operations were traced, not optimized or fully closed by this pass. No elapsed-time benchmark,
real-time deadline proof, physical robot or HIL validation was performed.

`FollowPathTask` calls direct updates without `startPath`, owns its own per-occurrence named-command
tasks, and uses stop for pauses. The follower retains that direct-use API; it does not add a second
event owner to the sequencer. Sequencer event scanning, scratch Pose2d allocation, path sampling and
projection, complete controller configuration validation, and remaining task lifecycle code remain
separate review obligations. The wrapper overload of HolonomicDriveController also needs raw-heading
validation review. Full-file review in this pass covers HolonomicPathFollower, PathEvent, the three
new test files and the existing three-method TrajectoryFollowerTest. Other traced files retain their
previous ledger status.

## Validation checkpoint

Source commits `ab73f606` and `57ac1db7` bind the final candidate
`17.0.3-rc.4c7029b25c9a` to library tree `4c7029b25c9ad93c819f71d739b214449992f891`.
The earlier `17.0.3-rc.e934548a7aef` publication passed its library gate but was superseded
by the signed-zero correction; its version was not reused. Final library validation passed in
1m47s: 1,462 tests, no failures/errors/skips, API checks, core Kover and isolated publication.
The full-suite marker allocation probe observed five zero-byte windows; earlier nonzero focused
measurements remain recorded above. FTC/FRC/FTC starter/FRC starter gates passed with
109/134/14/34 tests, generated-project verification and FTC assembly. Studio passed in 3m23s:
shared, gateway and app ordinary test tasks all reran (1,779 passes and six existing opt-in skips),
as did dashboard smoke (56 passes) and its performance baseline (one pass). Studio coverage,
version alignment and production file-size gates passed. Source policy passed, including source-tree identity, archive hashes,
agent guidance, and links in 216 current documents (38 historical exclusions).


Final XML and SHA-256 manifests, plus core Kover, are retained in
`ARESLib-Kotlin/build/audit-pass55-verified-evidence`; focused XML/Kover and earlier failures
have separate snapshots. The file ledger now accounts for 2,570 tracked files: 328 fully reviewed,
73 partial and 2,169 pending, with no stale fingerprints or orphaned records. This checkpoint is
verified progress toward the active audit goal, not complete monorepo coverage. All owned build
processes are terminal; no remote publication, push, merge or device operation was performed.
