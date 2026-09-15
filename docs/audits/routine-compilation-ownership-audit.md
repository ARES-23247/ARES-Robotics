# Routine compilation and task ownership audit

Pass 222 covers `RoutineCompiler`, `RoutineDsl`, `CapabilityArgumentReader`, the new internal
runtime ownership implementation and their tests. It traces execution through `RoutineManager`
and the existing task executor without repeating drive-facade or hardware-topology audits.

## Findings and changes

- **Routine decorators weakened terminal status.** A child that cancelled itself, or failed
  during normal `end`, could be treated as completed and allow the next step to initialize.
  A failure inside a branch's `execute` was not visible to the manager until a later update.
  Shared wrapper logic now propagates failure/cancellation after initialization, completion
  checks, execution and ending. It uses the existing owner-side deadline gate before the
  child's completion predicate. A deterministic test advances executor elapsed time while
  keeping `RobotClock` fixed, proving an overdue predicate is rejected without a watchdog race.
- **Child failure callbacks were discarded.** Cleanup removed callbacks before delivering the
  child's failure notification. Notifications now run once before child cleanup. Throwing
  diagnostic callbacks are reported without discarding the neutral Redux actions returned by
  `end`. Metadata cleanup failures also preserve returned actions and mark the invocation failed.
- **Rejected and dormant trees retained callback state.** Compilation could create a task,
  fail on a later factory, and abandon the earlier task's callbacks. Queued cancellation and
  selected-branch completion also left future or unselected children registered. One invocation
  now owns every compiled node and releases metadata on rejection and terminal cleanup, including
  branches never initialized. Cleanup visits all owned nodes even when a hook throws. Timeout
  and callback registry entries are removed even if an override fails to remove its own entries.
- **Wrappers concealed reused task instances.** Factories could return the same task for two
  repeated steps because the outer wrappers looked distinct to group validation. Compilation
  now checks object identity and requires fresh, unstarted factory tasks. It traverses built-in
  factory groups as well. A task already running elsewhere is rejected without clearing that
  task's status or callbacks. Distinct objects with equal `equals`/`hashCode` remain independent.
  A weak process-wide identity claim also prevents two compilations from acquiring the same
  still-pending task. Releasing its owner frees the claim without retaining discarded tasks.
- **Invalid called documents escaped preflight.** An empty called routine could compile and
  silently complete. The compiler now validates each reachable document before creating tasks;
  unrelated documents do not block the requested routine. Factory, metadata and group-construction
  exceptions become compilation diagnostics, with acquired metadata released on failure.
- **Numeric defaults bypassed validation.** Missing arguments returned defaults before finite
  and range checks, allowing NaN, infinity or out-of-range actuator targets through this decoder.
  Defaults and supplied values now use the same checks. Bounds must also be finite and ordered,
  including when an optional value is absent. Boolean, text and enum behavior is preserved.
- **An empty `otherwise` branch could be declared twice.** The builder used an empty list as
  both the absent and explicitly empty state. A nullable declaration slot now distinguishes
  those cases and enforces the existing single-declaration contract.

The compilation owner also freezes nested watchdog deadlines and forwards its pause/resume only
to initialized factory leaves. Transparent decorators participate in the executor's timeout
traversal, including when a compiled routine sits inside another task group. Unselected branches
stay dormant. This deadline traversal does not add physical pause callbacks to arbitrary outer
task groups. Built-in task membership is
visible to ownership checks; arbitrary custom tasks remain responsible for private children.
The public API snapshot is unchanged. The routine guide now documents these contracts and
corrects its stale instruction to check in generated plumbing: current FTC/FRC generation writes
under `build/generated/ares/`, owned by checked-in `.ares` documents.

## Focused evidence and efficiency

`RoutineCompilationOwnershipAuditTest` adds 21 methods and `RoutineCompilerStructureAuditTest`
adds eight. The initial 13 regression methods all failed on the old source. A later, separate
one-method run confirmed the invalid-called-document issue before that fix. Further checks
reproduced shared pending-task acquisition and nested timeout suspension failures. These are
16 failing scenarios with overlapping causes, not 16 independent defects. The first nested-timeout
fixture used queue insertion, which does not preempt; that failure is excluded. The retained
`nested-timeout-before.xml` uses the corrected executor suspend API with the old traversal.
The remaining cases exercise
the repaired contracts, including callback/metadata exceptions, pause/resume, hidden aliases,
equal-but-distinct tasks, every control-flow kind, primitive resource conflicts, drive marker
claims, typed arguments and DSL snapshot ownership.

The final focused run passed **214 results**, covering all routine and sequencer tests plus
the shared allocation regressions; all library API checks passed. The original routine-manager
allocation regression still passes its requirement for two consecutive zero-byte windows of
10,000 warmed holding updates. Compilation, pause/resume and terminal cleanup may allocate;
the ownership tree is not scanned on each steady update. Shared lifecycle handling replaces
duplicated decorator logic. This is desktop JVM evidence, not physical loop-time measurement.
The immutable store/EKF regression still allocates (905,016 bytes for 1,000 focused reductions).

## Candidate and validation

Source commit: `359a33139dff85dd044a51241e425a82c217fec1`.
Library tree: `c03e14b0ae4c0b9b8bac0f25f3a98cff6eac8ef4`.
Local candidate: `17.0.29-rc.c03e14b0ae4c`.

Logs, before/final XML, candidate hashes and archive comparisons are retained under
`ARESLib-Kotlin/build/audit-pass222-verified-evidence/`. Consumers resolve the same candidate
from the isolated local repository.

The earlier `17.0.28-rc.fcf2ee307098` candidate was superseded when final review found the
pending-claim and nested-timeout cases. Its evidence remains under `superseded-candidate/`;
its results are not substituted for validation of the final candidate below.

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| ARESLib | 2,624 | 0 |
| FTC TeamCode | 143 | 0 |
| FTC simulator | 13 | 0 |
| FRC | 305 | 0 |
| FTC starter TeamCode | 13 | 0 |
| FTC starter simulator | 1 | 0 |
| FRC starter | 34 | 0 |
| Studio shared | 31 | 0 |
| Studio gateway | 18 | 0 |
| Studio app | 2,043 | 6 |
| MicroPython host tests | 130 | 0 |
| Repository tooling tests | 101 | 0 |

The suites account for 5,456 passing results, zero failures/errors and six existing Studio
skips. These cover three opt-in starter integration scenarios, native file chooser, dashboard
performance baseline and physical dashboard validation. FTC/starter generated-project checks
and APK assembly passed; FRC/starter generated-project checks passed. All 410 candidate files
were hashed and reverified after consumer validation. Monorepo policy passed, including
source/version/archive identity, shared guidance and links in 384 current documents, with
38 explicitly historical records skipped. Four rebuilt archives differ only in version properties.

Validated results include Gradle up-to-date/cache outputs. Focused results are not counted
twice in the matrix.

## Coverage and limitations

The ledger accounts for 2,975 tracked files: 1,229 fully reviewed, 171 partially reviewed
and 1,575 pending, with zero stale or orphaned records. Coverage describes file review and
appropriate validation, not universal executable coverage.

The three pre-existing production files and three original routine test files receive complete
review records. The new ownership implementation, two regression files and this report are
also accounted for. `RoutineManager` remains partial: this pass traces task ownership and its
request/update/cancellation integration, not all document mutation, concurrent publication,
reentrant task-callback ordering or custom state-provider failure behavior. The routine guide
receives a partial review of runtime ownership and generated-output guidance; its broader editor,
controller and catalog claims remain separate scopes. Reducer/state files inspected for context
are not claimed as complete reviews.

Factories perform setup and metadata acquisition, not physical IO. The existing robot lifecycle
still owns enable, freshness, disable/stop and actual actuator neutralization. Documents and
binding inputs must stay stable during compilation; custom private task trees retain their own
child lifecycle responsibilities. No physical robot, native Studio window, remote CI or public
release was exercised. No WPILib defect was established. All changes remain local, and the
whole-monorepo goal remains active.
