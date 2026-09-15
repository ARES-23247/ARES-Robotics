# Executor timing and suspension cleanup audit

Pass 273 reviews timestamp arithmetic, suspension exceptions, and diagnostic failures in
TaskExecutor, plus terminal propagation through RoutineTaskOwnership. It adds 18 test methods.
Changes remain local in the isolated audit worktree.

## Findings and changes

- Untimed tasks accepted decreasing elapsed time after clock rollback. A frame sequence of
  1000, 1100, 1050 ms previously reached the task callback three times. The executor now fails
  and ends the invocation on the backward frame, returning neutral actions and releasing queued work.
- Subtraction could wrap a positive elapsed interval into a negative Long. A Long.MIN_VALUE
  origin to -1 is valid Long.MAX_VALUE elapsed; advancing to zero now fails before task callbacks.
  Preemption validates its timestamp and saved elapsed before starting incoming work.
- Suspension changed state before throwable timeout hooks, and resume used unchecked arithmetic.
  A rollback or hook failure now marks the active invocation terminal, retains and rethrows the
  failure, and blocks further void control transitions. The next update or preempt drains cleanup
  before the suspended early return. Explicit cancelAll also drains it. Cleanup preserves the
  suspended mode until an explicit successful resume.
- A timeout could fail a nested child during suspension without making the root terminal.
  Control transitions now propagate failed/cancelled status through owned raw and compiled trees
  without invoking domain completion callbacks. Preemptor timeout hooks are inside the same
  failure boundary as initialization. Stacked and queued work are drained after a failure.
- A task name getter or Throwable.toString could throw while reporting the original error,
  interrupting cleanup. Formatting and the diagnostic sink are now contained, with class-name
  fallbacks and preservation of direct interruptions from the error and diagnostics.
- Explicit cancellation discarded actions carried by a latched TaskTransitionAbort. Cleanup
  now returns them once through either update or cancelAll. An independent regression reproduced
  this after the initial timing fixes.
- Incoming cancellation from an active task's pause hook could put the same task in both active
  and preempted cleanup slots. Transferring it to the stack now clears the active slot immediately;
  the regression checks one end call and one neutral action.

## Timing contract and efficiency

Within active work, timestamps must be monotonic. Suspend/resume reads RobotClock, so callers
using those methods must supply update/preempt timestamps in that same millisecond domain.
Idle suspension binds no epoch, and a fully drained executor accepts a fresh origin.
Negative timestamps remain valid. Equal timestamps remain valid.

Elapsed subtraction requires timestamp >= origin and a nonnegative subtraction result; the
second condition detects a positive exact interval larger than Long.MAX_VALUE. Resume adds the
checked paused interval to the origin with checked addition. Restoring preempted work subtracts
its saved elapsed with checked subtraction. Boundary and nested-preemption tests verify that
pauses and higher-priority work are excluded without discarding valid cross-zero intervals.

Steady active updates add primitive timing checks and do not scan task trees or allocate timing
records. Tree scans occur at admission/control transitions. The existing warm executor probe
measured **0 bytes across 10,000 active updates**, following its unchanged 20,000-call warm-up;
the 256-byte batch limit is unchanged. This is desktop JVM allocation evidence for a narrow
empty-action task, not a whole-robot latency or hardware benchmark.

## Validation

The initial baseline ran seven methods with six failures. The later latched-action baseline ran
one method with one failure. Final focused library/API checks passed 140 tests, including all
18 timing methods, existing admission/suspension/lifecycle/routine cases, and applicable runtime
and allocation checks. The FRC marker compatibility suite passed its existing 20 cases against
the sibling source before candidate freeze. Public API checks passed with no signature changes.

Frozen source tree: `89c4b65bc69d8189299d4c3af78e6f666931a7f6`.
Local validation candidate: `17.0.51-rc.89c4b65bc69d`.

| Candidate validation | Passing tests |
| --- | ---: |
| Full library and API checks | 2,968 |
| FTC season and simulator | 186 |
| FRC season | 306 |
| FTC starter and simulator | 17 |
| FRC starter | 206 |
| Total, excluding repeated focused/baseline runs | 3,683 |

All listed suites have zero failures, errors, or skips. Prior library and consumer test cases
are preserved. Generated-project verification and FTC debug APK assembly passed. All 410 new
candidate artifact hashes were verified, and the previous candidate's 410 files remain unchanged.

Studio shared/gateway/app test sources compile. Normal Studio tests stop at the existing
release-alignment preflight; no test gate was excluded or bypassed. The earlier archive/workflow
migration remains unapproved after automatic approval review rejected it. Repository policy
still needs the aligned starter archives. This pass makes no GUI, HIL, physical-neutralization,
or upstream-WPILib defect claim.

TaskExecutor and shared ownership remain partial for the remaining external lifecycle/custom
ownership boundaries. Callers must not initialize/reset admitted tasks. Custom private children
remain their task owner's responsibility. The pass does not establish exception containment
for every other logger or wrapper, or neutral-output forwarding by every raw task group.

Local evidence: `ARESLib-Kotlin/build/audit-pass273-verified-evidence/`, including baseline/final
XML, completed build logs, candidate identities, artifact hashes, and the coverage inventory.
