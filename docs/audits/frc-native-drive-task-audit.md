# FRC native drive-task audit - pass 116

## Scope

Completed the remaining review of `FrcGeneratedRoutineCapabilities.kt`, previously partial
in pass 115. The scope includes configured alliance transforms, native trajectory generation,
resource declarations, immutable step ownership, delegate lifecycle, preset limits, generated
teleop command scaling and footprint bounds. Traced shared task groups, routine validation,
resource collection, named-command factories, task executor cleanup and trajectory validation.
Those shared implementation files are not newly claimed as fully audited by this pass.

Added twelve methods in `NativeDriveTaskAuditTest`. Existing transform, generated-drive sink
and autonomous contract tests remain part of full FRC validation; their prior review records
remain unchanged.

## Findings and fixes

- The native wrapper inherited a zero resource mask. Routine documents already have separate
  string-resource validation, but ordinary task groups could accept two native drive tasks
  sharing the same follower. The wrapper now declares DRIVE plus the registered masks of all
  marker, during and arrival actions before task-group construction. Tests reject conflicting
  drive and mechanism branches and verify the complete combined mask.
- Caller-owned mutable action lists could change the task after construction. The adapter now
  snapshots marker/during/arrival lists, keeping its behavior and resource declaration stable.
  Registry masks are captured without creating mutable action tasks and checked before path
  initialization. Deferred named tasks receive those same masks. Registrations are still expected
  to remain stable during robot execution; this is not a new concurrent hot-registration facility.
- `execute` called its delegate even after the wrapper's own timeout marked it FAILED. It now
  forwards work only while RUNNING and checks delegated terminal state before execution.
- Delegate cancellation was not propagated, leaving the outer task RUNNING indefinitely.
  FAILED and CANCELLED now reach the wrapper before/after completion and execution checks.
  A preexisting wrapper failure retains priority over child cancellation. End also observes
  status changes made during delegated end.
- A throwing delegate end skipped release, reference clearing and wrapper finalization.
  End now relinquishes the reference first, attempts delegate end/release and wrapper end,
  marks cleanup failures as failures, and then propagates the original error. This also avoids
  retrying the same failed delegate end through the executor's subsequent failure cleanup.
- A throwing delegate metadata release skipped the wrapper's own callback/deadline cleanup.
  Release now clears ownership first and always calls the wrapper's default release in `finally`.
  The regression verifies that a registered callback is actually removed, not merely that a
  reference field becomes null.
- Footprint validation recomputed the same sine and cosine twice. Each is now computed once;
  the unchanged projection formula agrees with independently transformed rectangle corners.

Task construction and terminal cleanup allocate as before; new resource snapshots and failure
collectors are confined to those transitions. Healthy execute/completion guards use primitive
status checks. No whole-robot zero-allocation or measured loop-time improvement is claimed.

## Evidence

Eight initial regression methods ran against unchanged production code; six failed after correcting
the test entry's authored alliance. `AutonomousCatalogEntry` defaults to RED, so an early BLUE-target
expectation was a fixture error, not a transform bug. The authoritative before XML/log are under
`ARESLib-Kotlin/build/audit-pass116-before-evidence/`. They show the six defects above; the fresh-pose
trajectory and rotated-footprint tests passed before and after.

Four supplemental methods cover terminal priority/end-time failure, changed registry masks,
all four preset scales with valid/invalid acceleration, and rejected preset/engine/overflow cases.
The exact original eight methods passed after the fixes before the supplemental full run.

Real native task initialization produces a path from the Redux pose at initialization, not the
earlier construction pose. Marker endpoints map to start/end distance, and arrival actions remain
in the delegated sequence. The footprint test checks 64 combinations of heading and position
against all four transformed corners.

Preset tests verify velocity, acceleration, jerk, centripetal and angular limits. Invalid acceleration
values use the existing default. A finite but enormous acceleration that overflows derived limits
is rejected by trajectory validation before a path is initialized. Explicit unavailable engines
fail rather than silently falling back; the installed jerk-limited engine's two accepted spellings
produce paths. No numeric formula needed correction in these paths.

The wrapper fault tests replace an initialized delegate with a test task to isolate status,
callback and cleanup behavior. Path setup, resource/group validation, registry checks and
trajectory limits use the real production implementations. Tests do not simulate every combination
of arbitrary user callbacks, concurrent registry mutation or hardware failure.

## Validation and limits

Full FRC validation passed 237 tests with zero failures, errors or skips, including twelve
new methods. Five core zero-GC methods passed. Generated-project/namespace verification
and monorepo policy passed. Gradle reused valid unchanged outputs. All four final validation
processes reached terminal exit zero. Copied XML/logs and source hashes are recorded in
`ARESLib-Kotlin/build/audit-pass116-verified-evidence/summary.json`.

An exception can prevent a task method from returning cleanup actions; the enclosing autonomous
lifecycle must still perform its direct fail-safe hardware stop. This pass verifies wrapper state
and ownership, not physical motor neutralization. Physical field dimensions, calibration, CAN
behavior and robot deadlines were not validated on hardware.

The shared library remains unchanged at candidate `17.0.3-rc.100852e472fb`, source tree
`100852e472fbeeba64fdf799665f51b4687f7f1b`. No push, merge or release occurred.
