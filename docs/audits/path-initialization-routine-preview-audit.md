# Path initialization and routine preview audit — pass 205

Scope: path-task initialization, reuse and stationary heading targets; Studio routine
preview expansion, numeric timeline construction, failure reporting and cancellation.
Changes and candidate artifacts remain local.

## Confirmed issues and fixes

`PathfindToPoseTask` previously generated a path before stopping the follower. A bad
profile limit could throw before a delegate existed, leaving a prior nonzero command
in place even when the executor attempted cleanup. Initialization now neutralizes first,
validates finite positive velocity/acceleration limits, and preserves the original
failure through stop/callback failures. Failure removes the wrapper deadline, records
FAILED and releases failure callbacks. Ending without a delegate also attempts a stop.

Uninitialized wrappers could report completion, and cancelled/reset wrappers could
continue ticking a still-running delegate. Completion and execution now require RUNNING.
An execution/completion exception also fails the outer task immediately. Reinitializing
an owned delegate now rejects the request and keeps that delegate available for explicit
end/cleanup. End detaches ownership; a properly ended/reset task can start a fresh delegate.
These checks retain the task owner's responsibility to end after cancellation or reset.

A target at exactly the current XY position lost its requested heading when passed
through a zero-distance spatial spline. After the existing costmap/Theta* endpoint
checks succeed, this case now builds an owning stationary target sample. The follower
commands CCW rotation for positive heading error and waits for heading tolerance.
Alliance transformation still happens once, and the Redux estimator remains the source
of the start pose. No hardware enable, freshness, lease or output policy was weakened.

Studio previously skipped failed generated segments and published the remaining route,
duration and actions as if the routine were complete. It also accepted trajectories
carrying ERROR diagnostics and allowed provider exceptions to escape the preview job.
Compilation now checks the full generation result and suppresses the entire timeline
on failure, with a warning. Cancellation remains cancellation; fatal JVM errors are not
converted into ordinary preview diagnostics.

Shared structural checks reject missing drive targets/action keys, nonfinite poses,
negative/nonfinite waits and missing/nonpositive/nonfinite condition timeouts. The
analyzer applies those checks only to active deterministic steps. A call cycle or other
expansion warning returns no partial steps or drives. Duplicate called routine IDs are
ambiguous; unrelated duplicates do not prevent previewing the current routine. The
edited root retains precedence over its saved copy.

Timeline additions must remain finite and preserve positive elapsed time. Shifted drive
samples must remain strictly increasing, so overflow or lost increments cannot create
an infinite duration or duplicate timestamps. Zero waits remain valid. Condition waits
retain their declared timeout as an educational upper bound. Compiled action arguments
are copied, preserving their snapshot when an input map is later mutated.

Unknown presets now use case-insensitive enum lookup with a balanced fallback, avoiding
exception allocation and uppercase copies. Empty repeat bodies skip repeated traversal
while repeat-count limits remain enforced. The editor owns and cancels its previous
preview job, invalidates old playback while recalculating, and publishes diagnostics and
results only for matching inputs inside the atomic state update. Cancellation checks
between steps and after each provider call prevent obsolete work from generating later
segments. They do not preempt a provider call already executing.

## Validation

All nine initial lifecycle regressions failed against the old path wrapper. A separate
stationary-heading regression then reproduced loss of the requested rotation. All ten
new methods pass after the fixes. The focused library run passed 38 tests, including
path lifecycle/alliance checks, FTC wrappers, suspension and zero-GC regressions.

The initial Studio baseline had nine failures across fourteen tests: six compiler
failures and three analyzer failures. The final focused run passed 43 tests, including
19 new methods. Coverage includes failed later segments, diagnostic-bearing results,
provider exceptions, cancellation identity and work counts, invalid structural/numeric
inputs, snapshot ownership, neutral/autonomous anchors, action ordering, call cycles,
duplicate IDs, 4096-step and 64-level boundaries, and valid/invalid/valid editor recovery.
The editor test owns and joins its coroutine scope and records escaped exceptions.

Full library tests, API checks and isolated candidate publication passed. Frozen source
commit: `54aafe0f6369c250b386de32ce6e49c6ba9721cd`; library tree:
`af2998dac603d828aeb9a77bf1e2411ea104e1ed`; exact local candidate:
`17.0.12-rc.af2998dac603`. Consumers resolve this candidate from the absolute local
release-validation repository. The artifact manifest records SHA-256 for 410 files.

| Validation scope | Tests | Passed | Skipped |
| --- | ---: | ---: | ---: |
| ARESLib JVM modules | 2,258 | 2,258 | 0 |
| FTC TeamCode + simulator | 156 | 156 | 0 |
| FRC | 305 | 305 | 0 |
| FTC starter TeamCode + simulator | 14 | 14 | 0 |
| FRC starter | 34 | 34 | 0 |
| Studio shared + gateway + app | 2,058 | 2,052 | 6 |
| MicroPython host suite | 130 | 130 | 0 |
| Repository tooling | 101 | 101 | 0 |

This totals 5,050 passing test results and six explicit Studio skips,
with no failures/errors. Gradle may reuse unchanged task outputs; these are suite results,
not a claim that every test was re-executed. Focused runs are not counted again. The skips are three
generated-project integrations, native file chooser, performance baseline and physical
dashboard validation. Both FTC products also passed project verification and debug
packaging; both FRC products passed project verification. Full Studio validation passed
against the exact candidate after the preview fixes.

Four bundled starter archives were rebuilt under new version identities. Entry-level
comparison found only `release/ares-versions.properties` changed in each archive, with
no entries added or removed. Workflow version/URL/hash copies and the canonical starter
hash manifest match those exact bytes. Library and starter versions are 17.0.12, Studio
is 7.0.12, and XRP/Lightbot are 3.0.11. These are local candidate pins, not a public release.

Repository policy and shared guidance passed, including source-tree identity, version
and archive-hash alignment. Local links were checked in 366 current documents; 38
historical records were explicitly skipped.

Evidence: `ARESLib-Kotlin/build/audit-pass205-verified-evidence/`, including before/final
logs, copied XML, focused/full summaries, candidate identity/hash manifest and archive
entry/hash comparisons. No visible-window, physical robot, target MicroPython firmware,
deployment or remote workflow/release validation was performed. Host tests demonstrate
software behavior, not measured robot stopping distance or physical loop latency.

## Coverage boundaries and next work

The preview compiler/analyzer and their focused tests were read in full. Preview checks
remain educational: they do not replace routine catalog validation or runtime checks of
conditions, actions and motion constraints. The larger path-planner view model and state
file retain partial review; this pass covers preview ownership/publication and recovery,
not every editor intent, project-switch interleaving or playback-clock behavior.

The path wrapper's initialization/terminal boundaries were reviewed and tested, but its
broader planning integration retains partial status. Investigate short nonzero spline
paths next: `SplineMotionProfiler` currently uses zero heading progress when total travel
is below 1e-6 meters. The exact-zero case is fixed here; loss of heading on short nonzero
paths remains a hypothesis requiring an independent regression and shared-math review.
The lighting preview model was inspected only as a consumer of action snapshots; its
per-frame sorting, allocations and numeric behavior remain a separate scope.

The final ledger accounts for 2,909 tracked files: 1,094 reviewed, 157 partial and 1,658
pending, with no stale fingerprints or orphaned records.

File-review accounting is not line/branch coverage or a claim that all tracked files are
executable. The complete monorepo audit goal remains active; hardware-only, generated,
vendor, declarative and documentation files retain explicit individual limitations.
