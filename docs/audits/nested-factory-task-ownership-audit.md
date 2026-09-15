# Nested factory task ownership audit

Pass 270, 2026-09-14, continuing the [FRC capability construction audit](frc-starter-capability-construction-audit.md).
This pass fixes ownership of returned factory task trees in
[RoutineTaskOwnership.kt](../../ARESLib-Kotlin/core/src/main/kotlin/com/areslib/routine/RoutineTaskOwnership.kt)
and the FRC starter's generated action boundary.

## Rejected trees and foreign owners

The shared compiler stopped traversing a returned built-in group when a child was already running
or claimed by another compilation. Children encountered later in that group were never acquired,
so failed-compilation cleanup could leave their callbacks and timeout configuration registered.
The compiler now continues acquiring fresh siblings before reporting acquisition failure. Its
normal rejection cleanup then releases all nodes it acquired. Rejected foreign or running nodes
are never released or recursively claimed; a running root's dormant children remain with that root.

Two baseline core regressions reproduced the skipped-tail problem, including a nested group and a
foreign pending task. Further tests cover sequential, parallel, race, and deadline groups; fresh
nodes before and after the rejected subtree; callback preservation for the foreign owner; and
cleanup failure propagation without skipping other nodes. Failure aggregation also preserves a
direct InterruptedException's thread flag and does not suppress the same exception twice.

## Ownership during factory composition

The FRC builder previously tracked only the root returned by an action factory. Releasing a
built-in group root after a later factory failure did not clean up its nested children. A pending
task owned by another compiled invocation could also pass the builder's unstarted-task check.
Both conditions were reproduced against the previous immutable library candidate.

The new public `ownRoutineTaskTree(Task): Task` helper acquires the returned tree through the
shared ownership registry and returns an owner that delegates execution and pause/resume. It
preserves the root's name, priority, and resource mask. Factories can retain that owner in their
construction cleanup scope until returning the enclosing tree to RoutineCompiler. Rejection and
explicit metadata release attempt every acquired node, clear callback/timeout registries even if
custom hooks throw, retain the primary error, and release the claims. Repeated owner release does
not repeat those ownership-registry releases.

The FRC builder now wraps each generated action immediately with this helper. Its existing unwind
scope therefore owns returned nested action trees, including private marker actions, and a foreign
pending task is rejected without erasing its original owner's callback. All 203 prior FRC starter
cases remain present and pass, including construction error suppression and successful composed
drive/marker/during/arrival execution. The two new integration regressions also pass.

Factories remain responsible for objects they create but never return. Custom tasks still own
children that they keep private. Metadata-only cancellation cannot dispatch hardware-neutral
actions; active tasks must end through their lifecycle executor. These boundaries are documented
on the helper rather than claiming that it can discover arbitrary hidden objects.

## Preemption evidence and open work

The third baseline core test already passed. A real TaskExecutor preempting a compiled parallel
group forwarded both running leaves' neutral actions and preserved their 10 ms deadlines across
a 1,000 ms urgent task. Both leaves resumed once. This verifies the normal compiled-task path;
the absence of a pause override on an ordinary group does not by itself prove that path is broken.
It does not establish which generated control binding invokes preemption.

Throwing pause/resume callbacks still need targeted validation. Interaction between a built-in
group's own metadata hooks and central ownership cleanup also remains open. The prior whole-file
complete label for RoutineTaskOwnership was therefore reopened as partial. Its registry's
once-only release guard does not prove that custom hooks run once across every group/executor
path. The FRC autonomous file remains partial for exceptional callbacks, match timing,
preflight/runtime exception handling, and preferred-engine policy.

## Validation

Baseline: **3 core tests, 2 failures**, plus **2 FRC starter tests, 2 failures**. Final focused
core validation passed 38 tests. New coverage comprises 9 core methods and 2 FRC integration methods.
The source was frozen as library tree `be6f53042221e2123eba11170ed7cdb3cb4a11ce`, with local
candidate `17.0.45-rc.be6f53042221`. The API change is additive: one helper, no removed signatures.

| Candidate validation | Passing tests |
| --- | ---: |
| Full library and API checks | 2,918 |
| FTC season and simulator | 186 |
| FRC season | 306 |
| FTC starter and simulator | 17 |
| FRC starter | 205 |
| Total, excluding repeated focused/baseline runs | 3,632 |

All listed suites have zero failures, errors, or skips and preserve previous test cases. Generated
project verification and local FTC debug APK assembly also passed. All 410 new candidate artifact
hashes were recorded and rechecked; the previous candidate's 410 files remain unchanged.

Studio's shared, gateway, and app test sources compile against this candidate. Their normal test
tasks still fail the existing release-alignment preflight because the workflow version is stale.
Repository policy similarly reaches the missing updated starter archive. Archive/workflow
migration remains unapproved; no gate was excluded or bypassed. Compilation is not runtime-test,
visible-window, physical hardware, HIL, or competition-loop evidence.

Evidence is local under `ARESLib-Kotlin/build/audit-pass270-verified-evidence/`, including copied
XML, baseline failures, candidate hashes, source identities, gate failures, and the coverage ledger
inventory. Shared guidance and current-document link checks pass. No remote publication occurred.
