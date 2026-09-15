# Executor admission and callback audit

Pass 272, 2026-09-14, continuing the
[task transition cleanup audit](task-transition-cleanup-audit.md). This pass changes
[TaskExecutor](../../ARESLib-Kotlin/core/src/main/kotlin/com/areslib/sequencer/TaskExecutor.kt)
and [RoutineTaskOwnership](../../ARESLib-Kotlin/core/src/main/kotlin/com/areslib/routine/RoutineTaskOwnership.kt).

## Exclusive admission and queued cleanup

TaskExecutor previously accepted duplicate task identities and tasks claimed by another executor
or compiled routine. This could initialize the same task more than once or let one executor clear
another owner's metadata. Cancelling a queued uncompiled group only released the root's metadata,
leaving callbacks on dormant descendants. A foreign incoming task could also pause current work
before its ownership conflict was noticed.

Each admission now acquires the existing shared tree ownership mechanism, preserving the original
task object for execution. Built-in descendants are claimed once, including future sequence steps.
Duplicate, running, or foreign-owned admissions are rejected before changing the queue
or pausing current work. Failed acquisition abandons only newly acquired claims, preserving all
caller metadata and leaving foreign owners untouched. Terminal executor release drains every
node through the invocation guard, including descendants that never initialized.

Root status is checked again before dequeue/preempt initialization. A newly terminal queued root is
released without an initialization or end call. A queued descendant's failed/cancelled status is
also propagated before its siblings can start, through nested compiled owners as well as raw
groups. An additional regression exposed this descendant case after the first admission fix.

The initial full suite caught an over-restrictive admission rule: an existing indicator task test
intentionally resubmits completed raw tasks without reset. That test's reuse assertions remain
unchanged. Consumer validation then established that marker restart also deliberately resubmits
cancelled raw children. Successful explicit admission now returns idle raw nodes to PENDING without
clearing newly configured callbacks or timeouts. This begins a new queued invocation; a subsequent
failure/cancellation blocks startup, including cancellation of a previously cancelled task that
was deliberately resubmitted. Compiled factories retain their stricter fresh-task contract.

A compiled invocation whose tree has been released cannot be resubmitted, even after resetting
its public status to PENDING. Another targeted failure established that status alone concealed
lost ownership. Such invocations must be rebuilt. Raw tasks/groups can be explicitly resubmitted
after terminal cleanup without resetting newly configured metadata.

FRC starter validation also caught two fixtures that initialized a marker before handing it to a
new group's executor. Those two setups now leave first initialization to the actual group, matching
production factory construction. All assertions and case names are retained. The third failing
marker-restart case was a runtime regression; its assertions and setup remain unchanged and pass
with the explicit-resubmission repair.

## Callback reentrancy

A custom end hook could recursively preempt the executor while the original task was still
RUNNING. The outer completion then cleared the new active-task reference, stranding initialized
incoming work. A recursive update could execute another frame inside the same callback. Cleanup
callbacks could append fresh tasks while the executor was trying to drain its queue.

Public update, preempt, cancelAll, suspend, and resume now share a guard that rejects same-executor
recursive control before mutation. Exceptions raised inside task callbacks reach the existing
failure/neutral-action cleanup. Private internal drains do not reenter the public guard.
Failure/cancellation and the executor's final metadata drain reject additions, so those cleanup
phases cannot replenish their queue. Ordinary lifecycle and completion callbacks can still append
fresh queued work; this compatibility path has a positive test. Guard flags restore in finally.

The original standard onComplete preemption case already failed closed and passed its baseline.
It remains compatibility coverage. The distinct custom end-hook case failed independently and
is the evidence for the orphaned-active-task fix. Tests use bounded callbacks, not an unbounded
recursion or queue-filling process.

## Efficiency and scope

Admission reuses shared ownership rather than introducing another tree-release algorithm. Failed
admission removes claims without running user hooks. Duplicate callback/timeout resets were
removed from RoutineTaskOwnership because its direct-release helper already guarantees them.
No additional terminal-state snapshot map is needed: successful raw resubmission starts from
PENDING. Tree acquisition and queued-terminal inspection occur at admission/start; steady active updates
do not traverse the tree or allocate ownership records. The operation guards are inline.

The new JVM allocation probe warmed 20,000 frames and measured **0 allocated bytes across 10,000
active updates** in the focused run. Its assertion permits at most 256 bytes across that batch.
This is a narrow empty-action task test on the desktop JVM, not a whole-robot allocation or
competition-loop timing benchmark. The full suite also passed this probe without a skip.

The existing indicator allocation probe reported 1,000 bytes, then 368 bytes when isolated, with
its unchanged 5,000-call warm-up. Its steady runtime path was unchanged by this pass. Warm-up was
increased to 20,000 calls; the measured 10,000-call workload and 256-byte limit were retained. Both
the indicator and executor probes then measured zero bytes in the focused run and passed the
full suite. The observations do not identify a specific JVM allocation source.

TaskExecutor and shared ownership remain partial in the ledger. Timestamp rollback/overflow,
suspension exception boundaries, and broader external lifecycle changes need separate review.
Callers must not initialize or reset admitted tasks. Arbitrary private children remain the custom
task's responsibility. Metadata hooks invoked privately inside a custom lifecycle method are not
all executor drain phases; this pass does not claim to intercept every such hook or API misuse.

## Validation

Baseline: **8 tests, 7 failures**, followed by **1 failing custom end-hook test**. Expanded coverage
then exposed queued descendant cancellation: **15 tests, 1 failure**. A released-wrapper baseline
also failed. Initial full validation caught the completed-task compatibility regression, so
candidate `17.0.48-rc.28132463042f` was rejected before publication. Candidate
`17.0.49-rc.99ebd4b924d4` passed library tests but failed three marker consumer cases; its original
artifacts and evidence were preserved. Final focused/API validation passed **185 tests**, including
all **18 new admission methods**, existing task lifecycle/routine
tests, generated runtime, and superstructure cases. Public API signatures are unchanged.

Frozen source tree: `0fd1e530f36351c25c943e0a30ceba3444a321c8`.
Local validation candidate: `17.0.50-rc.0fd1e530f363`.

| Candidate validation | Passing tests |
| --- | ---: |
| Full library and API checks | 2,950 |
| FTC season and simulator | 186 |
| FRC season | 306 |
| FTC starter and simulator | 17 |
| FRC starter | 206 |
| Total, excluding repeated focused/baseline runs | 3,665 |

All listed suites have zero failures, errors, or skips and preserve prior cases. Generated-project
verification and local FTC debug APK assembly passed. All 410 new artifact hashes were verified;
the previous candidate's 410 files remain unchanged.

Studio shared/gateway/app test sources compile. Normal Studio test execution remains blocked by
the existing release-alignment preflight, and repository policy reaches the missing updated
starter archive. Shared guidance and current-document links pass. The archive/workflow migration
remains unapproved after the earlier automatic approval review rejection; no gate was bypassed.
All changes remain local. No GUI, physical hardware, HIL, or physical-neutralization claim is made.

Local evidence is in `ARESLib-Kotlin/build/audit-pass272-verified-evidence/`: baseline/final XML,
allocation output, candidate/source identities, artifact hashes, gate failures, and ledger inventory.
