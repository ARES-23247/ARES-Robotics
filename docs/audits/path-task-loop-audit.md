# Path task timing, marker work and failure audit

Pass 59, 2026-09-10. Branch `codex/robot-loop-math-audit`.
Source `48b876f5`, candidate `17.0.3-rc.3258b9d59e51`.

## Findings and changes

`FollowPathTask` treated timestamp zero as an uninitialized sentinel. Starting a mock/replay clock
at zero could issue a fictitious 20 ms step without elapsed time. The initialized timestamp is now
used directly. Repeated timestamps stop/reset the follower without advancing progress; backward
time and overflowing signed subtraction fail the task; advancing frames also reject negative
elapsed execution time. Suspension
prevents execution. Resume establishes a fresh motion-integration origin, excluding paused time.
Child elapsed time retains active time before pausing and saturates instead of wrapping at Long.MAX.

The default task timeout could mark the task failed, after which the path override still issued
motion and triggered markers. Execution now checks status immediately after the timeout boundary.
A failed marker immediately stops the follower, including direct task use without an executor.
Initialization and execution exceptions mark the parent failed, attempt neutral output, and preserve
the original exception, suppressing a distinct stop failure. A partially initialized marker is
registered as owned before calling its initializer, so interrupted cleanup can reach it. Reused task
instances are rejected rather than registered twice. Active marker tasks must be ended before
reinitializing a path; the check preserves the old children for cleanup instead of dropping them.

The task replaced negative, zero and small authored feedforward with a positive 0.3 m/s floor away
from the endpoint. It now retains the sampled velocity, including reverse and zero. Virtual progress
uses speed magnitude with the existing 0.1 m/s floor; this is a planning heuristic, not measured
robot distance. The existing 0.4 m lead bound remains, and progress cannot advance beyond the end.
Feedback can still request motion when feedforward is zero. No physical path-following performance
claim is made from these changes.

Initialization validates finite raw point fields, nonnegative nondecreasing distances and finite
nonnegative marker thresholds before alliance conversion can sanitize invalid headings. Execution
checks progress, primitive estimated pose and representable tracking errors. Invalid completion
feedback cannot satisfy velocity-hold completion. End-distance tolerance uses hypot instead of
squaring coordinate differences. Caller-owned path points must remain stable during execution;
this pass does not introduce a private immutable copy of the whole path.

Markers are copied and stably sorted by distance at initialization, with signed-zero ties retaining
authored order. A cursor visits only newly crossed markers; caller event-list mutation cannot change
the installed schedule. Earlier markers are not replayed when virtual progress retreats. This removes
the O(all markers) scan from every loop. Marker factories and callbacks remain synchronous; a burst
of crossed markers still costs work and allocates tasks/actions. Freshness checks currently scan active
tasks by identity, so a burst can cost O(crossed markers * active tasks), in addition to active task
execution and path sampling/projection. This is not an unqualified constant-time loop claim.

Each active child now owns a primitive elapsed-time field, replacing repeated boxed Long map
lookups/updates and pause-time origin shifts. Estimator coordinates are read directly from the
immutable snapshot, avoiding its allocating Pose2d convenience view. Tangent sine/cosine are each
computed once for both tracking errors. Progress actions, moving sampled poses, output dispatch,
new markers and caller/backend work can still allocate. Returned action storage remains reused;
callers must dispatch or copy it before the next execution. TaskExecutor already copies these actions
into its returned collection.

## Evidence

All seven initial regression methods failed against the original source. Baseline logs and XML are
retained in `ARESLib-Kotlin/build/audit-pass59-before.log` and `audit-pass59-before-evidence`.
The expanded focused gate passed 71 methods, including 15 new tests and existing path failure,
task lifecycle/group/suspension, follower and zero-GC tests. It covers zero/repeated clocks, parent
timeout, reverse/zero/small feedforward, marker ordering and source-list mutation, paused child time,
clock rollback/overflow, initializer cleanup, failed marker neutralization, invalid completion pose,
duplicate task ownership and malformed marker thresholds.

The marker-work test uses 10,000 future markers and 20 idle execution loops. The original code
revisited the source list each loop; the corrected code makes zero source-list reads after setup.
This is an operation-count regression, not a wall-clock or physical loop benchmark. Focused Kover
for FollowPathTask covers 194/208 executable lines, 149/228 branches and 15/15 methods. Missing
branches remain explicit; the focused suite does not prove all lifecycle interleavings.

Focused log/XML/Kover are retained in `audit-pass59-expanded.log` and `audit-pass59-focused-evidence`.
The pre-commit whitespace check rejected two changed lines retaining old trailing spaces; those
spaces were removed before the source commit. The full library gate passed 1,521 tests with no
failures/errors/skips, API compatibility checks, core Kover and isolated publication in 1m47s.
Full FollowPathTask Kover counts match the focused counts above. Source policy passed, including
220 current Markdown documents, 38 explicit historical exclusions, source identity, archive and
agent guidance checks.

Candidate consumers passed in dependency order: FTC 109 tests, FRC 134, FTC starter 14 and
FRC starter 34, with generated-project verification and both FTC application assemblies. Studio
passed in 3m21s: shared/gateway/app test tasks all reran, yielding 1,779 passes and six opt-in skips.
Dashboard smoke (56) and performance (1) reran and passed, together with coverage verification,
release alignment and production-file-size checks. Six opt-in skips remain limitations, not passes.
Final XML, core Kover, per-product logs and SHA-256 manifests are saved under
`ARESLib-Kotlin/build/audit-pass59-verified-evidence`.

## Review boundaries

The changed FollowPathTask timing, motion/progress, marker scheduling, child ownership and failure
boundaries were traced through TaskExecutor, TaskTimeoutManager, NamedCommands, AllianceMirroring,
HolonomicPathFollower and the estimator snapshot. These reads do not close those other files.
The aggregate `Task.kt` receives partial credit: remaining task abstractions, callback reentrancy,
marker resource ownership, timeout-watchdog interactions and broader lifecycle composition require
further review. In particular, a calculator's invalid feedback can still be distinct from the task's
marker progression; the follower/task API does not yet expose a unified execution-validity result.

The new 15-method test file and existing four-method FollowPathFailureTest were read in full and
executed. No hardware, live desktop window, deployed robot, hosted CI or real-time deadline
validation was performed. The full-monorepo audit goal remains active.
