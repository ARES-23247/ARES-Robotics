# Task transition cleanup audit

Pass 271, 2026-09-14, continuing the
[nested factory ownership audit](nested-factory-task-ownership-audit.md).
This pass examines exceptional pause/resume and metadata cleanup in
[TaskExecutor](../../ARESLib-Kotlin/core/src/main/kotlin/com/areslib/sequencer/TaskExecutor.kt),
[built-in groups](../../ARESLib-Kotlin/core/src/main/kotlin/com/areslib/sequencer/TaskGroupDispatcher.kt),
and [compiled ownership](../../ARESLib-Kotlin/core/src/main/kotlin/com/areslib/routine/RoutineTaskOwnership.kt).

## Transition failures and returned cleanup actions

A throwing compiled child pause could discard neutral actions already returned by its siblings.
The executor could also resume a failed/cancelled preempted task, and a later terminal child could
be discovered only after earlier siblings had received resume callbacks. An incoming urgent task
must not initialize after the pause transition fails.

Compiled ownership now checks all owned terminal nodes before resuming any leaf. Pause continues
through other running leaves to collect available neutral actions; resume stops when a leaf fails.
A structured internal abort carries the collected actions and terminal outcome to the executor,
which cleans up the active and paused work and releases unstarted incoming/queued metadata.
Preempted tasks enter the stack only after successful pause. Failed/cancelled tasks are not
unconditionally changed back to RUNNING.

The structured abort is needed across private wrappers. A status-only approach failed a test
using the actual FRC starter drive-marker wrapper: its private child had failed, but the enclosing
task still allowed incoming work. The final consumer regression observes the neutral drive action,
no incoming initialization, one child end, a failed outer task, and an empty executor.

## Metadata ownership and partial initialization

Built-in groups and their compiled owner could each invoke the same child's metadata-release hook.
A hook that threw could also stop cancellation before other tasks returned their cleanup actions.
Group/executor releases now use the invocation's identity guard through the internal
[TaskRuntimeOwnership](../../ARESLib-Kotlin/core/src/main/kotlin/com/areslib/sequencer/TaskRuntimeOwnership.kt)
registry. The registry has weak task keys and weak owner references and invokes custom hooks
outside its monitor. Its direct-release fallback clears callback/timeout state in a finally block.

Cancellation detaches tasks before invoking cleanup, continues after end/metadata exceptions, and
retains available neutral actions. Normal completion metadata failure prevents queued progression.
Direct InterruptedException preserves the thread interrupt flag, including a throwing diagnostic
callback; tested Error failures also reach cleanup. These tests do not promise successful recovery
from every possible JVM resource failure.

Group cleanup distinguishes children whose initialization was attempted from untouched children.
Only the former receive interrupted end; dormant owned nodes receive metadata release. The first
implementation incorrectly inferred initialization from task status. The full library suite caught
two regressions in unchanged PathPlanner parser race/deadline tests, whose mock tasks initialize
without publishing default lifecycle metadata. Those tests were retained unchanged. Groups now
record attempted calls with primitive counters, including an initializer that throws before
calling the default implementation.

The initial source candidate `17.0.46-rc.e58605517957` was rejected before publication. The repair
uses a distinct source identity and version. Repeated custom metadata hooks are removed from the
tested invocation-owned paths; no new per-child set is needed for initialization tracking.
No measured allocation benchmark or competition-loop timing claim is made.

## Validation

The first baseline reproduced **7 failures in 7 tests**. Additional baselines exposed a direct
interruption loss, a terminal sibling resume, and the private marker boundary. The initial full
suite separately caught the two introduced parser regressions. Final focused core/API validation
passed **100 tests**. New coverage comprises **14 core methods and 1 FRC integration method**.

Repaired library tree: `a8eee7a6261ff6e47ae071e74c49f51520f67c1b`.
Frozen local candidate: `17.0.47-rc.a8eee7a6261f`. Public API signatures are unchanged.

| Candidate validation | Passing tests |
| --- | ---: |
| Full library and API checks | 2,932 |
| FTC season and simulator | 186 |
| FRC season | 306 |
| FTC starter and simulator | 17 |
| FRC starter | 206 |
| Total, excluding repeated focused/baseline runs | 3,647 |

The listed suites have zero failures, errors, or skips and preserve prior test cases. Generated
project verification and local FTC debug APK assembly passed. All 410 candidate artifact hashes
were recorded and rechecked; the previous successful candidate's 410 files remain unchanged.

Studio shared/gateway/app test sources compile against this candidate. Their normal test tasks
remain blocked by the existing release-alignment preflight; repository policy reaches the missing
updated starter archive. Shared guidance and current-document links pass. No gate was excluded
or bypassed, and tracked archives/workflow references remain unchanged. Their migration remains
unapproved after the earlier automatic approval review rejection.

## Remaining scope

TaskExecutor, built-in groups, and compiled ownership remain partial in the ledger. Raw task
admission, queued uncompiled trees, callback reentrancy, and timestamp/timeout-suspension boundaries
need separate targeted review. The FRC autonomous source gets an evidence-only ledger update for
its private marker boundary and remains partial for broader callbacks, match timing, preflight,
runtime failures, and preferred-engine policy.

Custom tasks own children they keep private; factories own objects they create but never return.
Metadata-only release cannot dispatch hardware-neutral actions. The observed action lists prove
available Redux cleanup output, not physical actuation. No GUI, physical hardware, HIL, or measured
competition-loop validation occurred. Studio compilation is not runtime-test evidence.

Evidence is local under `ARESLib-Kotlin/build/audit-pass271-verified-evidence/`, including baseline
and final XML, failed initial validation, candidate/source identities, artifact hashes, preserved
gate failures, and the coverage inventory. All changes remain local.
