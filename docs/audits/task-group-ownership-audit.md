# Task group ownership and resource audit

Pass 63, 2026-09-10. Source commits `31bb443c` and `68690714`, local candidate
`17.0.3-rc.9466080d5889`; `release/ares-source-tree.txt` records its exact library tree.
This pass covers group membership ownership, task identity, resource masks and redundant
completion bookkeeping. It follows pass 62's completion/timeout boundary review.

## Confirmed defects and changes

Parallel completion sets used user-defined equality and hash codes while timeout, status and
cleanup registries used task identity. Equal but distinct tasks could skip execution or make a
deadline appear complete when only its companion had finished. Mutating a completed task's hash
could make its end run again. Parallel, race and deadline groups now use their existing identity
cleanup set for completion bookkeeping, removing three redundant completion sets and their
separate inserts/clears/lookups. Failed or cancelled cleanup propagates terminal parent status;
the existing completion gates prevent that handled child from becoming normal group success.

Sequential, parallel and race groups retained the caller's child list. Changing it after
construction could invalidate the cached resource union, replace validated children, or omit
initialized children from interrupted cleanup. All four group types now obtain an unmodifiable
ArrayList snapshot through one membership validator. This also gives group hot-loop indexing
constant-time access even when input is a linked list. Deadline groups previously copied their
combined list already; they now use the same validation and ownership boundary.

Repeated zero-resource task instances were accepted in groups and nested built-in task trees.
Such instances share status/timeout state and cannot own independent lifecycle positions. The
validator now rejects repeated object identity throughout sequential, parallel, race and deadline
descendants, including shared leaves under separate branches. Distinct objects with equal values
remain valid. Traversal uses an explicit deque, not recursive calls or user equality/hash methods.
Resource conflict validation remains limited to parallel siblings; different sequential tasks may
reuse the same resource. The DSL's documented one-instance-per-tree rule is now enforced for its
built-in groups when the enclosing tree is constructed.

The previous validator read each parallel child's resource mask three times across overlap
validation and union construction. The shared snapshot reads each direct child's mask once and
uses that same value for both. `Task.requiredResources` now explicitly documents that the mask is
fixed for the instance. Changing a task's mask after construction is outside this contract.

Resource allocation correctly reserves 11 built-in bits, 32 generated bits at positions 16..47
and 15 season bits at positions 48..62, with range checks before shifting. Tests exercise all
58 distinct one-bit allocations and invalid indices. Diagnostic formatting was incorrect for a
custom mask with bit 63 set: it emitted a negative hexadecimal number. It now emits the unsigned
bit pattern. This does not allocate bit 63 through the generated or season APIs.

## Evidence

The initial 15 tests ran against pass 62 source; 13 failed and the allocation-range/range-rejection
controls passed. Baseline logs and XML are retained under `ARESLib-Kotlin/build/audit-pass63-before.log`
and `audit-pass63-before-evidence`. The linked-input test observed 4,100 source indexed reads for
100 children over 20 completion/execute updates plus cleanup; after construction the corrected
test observes zero source indexed reads. The mask-read test observed three reads before and one
after. These are deterministic operation counts, not robot-loop latency measurements.

The final focused suite passed 127 tests without failures, errors or skips. It includes 21 new
methods: 17 ownership tests and four resource-mask tests. Cases cover all group types, nested
duplicate identities, equal tasks, mutable hashes, throwing user hash/equality methods, caller
list replacement/clearing, immutable internal snapshots, reinitialization, nested resource
conflicts, DSL ownership and linked-list traversal. The added integration cases were not all run
individually against the old source. Existing sequencing, completion, path and allocation gates
also passed.

Focused Kover: TaskResources covers 22/22 lines, 18/18 branches and 4/4 methods;
TaskResourceValidator covers 20/20 lines, 18/18 branches and 1/1 method; TaskGroupMembers covers
1/1 line and 1/1 method with no branches. Full source review is credited to TaskResources.kt,
including all three contained classes, and both new test files. TaskGroupDispatcher.kt retains
partial credit. Focused XML/Kover are saved in `ARESLib-Kotlin/build/audit-pass63-focused-evidence`.

The full library gate passed 1,587 tests with no failures/errors/skips, API compatibility and
local publication in 1m56s. After the construction-cost KDoc clarification, the final source
identity passed the same gate in 14s using unchanged test outputs where Gradle considered them
up to date; no test result is claimed to have rerun solely because a candidate version changed.
Policy passed with 224 current documents and 38 historical exclusions, including source identity,
archive integrity and agent guidance. Final candidate consumers passed in dependency order: FTC109, FRC134, FTCstarter14 and
FRCstarter34 tests, generated-project verification and both FTC application assemblies. Studio
passed in 3m30s: shared/gateway/app test tasks reran with 1,779 passes and six opt-in skips.
Dashboard smoke56 and performance1 reran and passed, along with coverage, release alignment and
production-file-size checks. Final XML, core Kover, logs and SHA-256 manifests are saved in
`ARESLib-Kotlin/build/audit-pass63-verified-evidence`. Six opt-in skips remain limitations.

## Limits and remaining work

Membership validation allocates during construction and visits the descendants of each newly
built group. Repeated construction of deeply nested groups can revisit descendants; this pass
does not claim globally linear construction cost or benchmark a complete autonomous routine.
No new per-update collections were added. A dynamic command factory can still construct a group
on the robot loop, so construction-time validation cost must be considered there. Existing
output-action and lifecycle allocations remain.

Identity validation covers built-in group descendants. Custom tasks with hidden child trees,
dynamic named-command factories, separate executor submissions, marker resource declarations and
simultaneous use of a task in unrelated executors remain their owners' responsibilities and
separate audit areas. Constructor snapshots do not permit concurrent mutation of an input list
while it is being copied. Group initialization failures, callback reentrancy and timestamp
arithmetic during suspension/preemption remain open full-file review work.

No physical robot, live Studio window, hosted CI, electrical behavior or hardware-loop jitter
validation was performed. The full-monorepo audit goal remains active.
