# FRC starter marker lifecycle and shared selection audit

Pass 268, 2026-09-14, following the [pose-controller audit](frc-starter-pose-controller-audit.md).
This pass reviews marker execution and catalog selection in
[StarterFrcAutonomousRuntime.kt](../../ARES-FRC-Starter/src/main/kotlin/org/aresfirst/starter/frc/StarterFrcAutonomousRuntime.kt).
The enclosing file remains partial for capability construction, field transforms, and match lifecycle.

## Marker defects and changes

The marker treated an empty child executor as success, even when that executor had just failed or
cancelled its action. It now propagates the child's terminal status and completes only after a
successful child completion. Returned child cleanup actions remain visible to the caller. A real
`ParallelDeadlineGroup` and enclosing sequence test verifies that a failed marker fails the group,
neutralizes the drive, and prevents the arrival action from running.

The original progress calculation reached 100% only at the exact target. The drive controller can
finish anywhere inside its position tolerance, so an endpoint marker could never start. Marker
translation progress now snaps to the endpoint inside the same controller tolerance, referenced
from one constant. Normal intermediate progress and one-shot behavior are retained. An actual drive
deadline test verifies the endpoint action starts before three fresh settled observations complete
the drive. Progress is based on remaining translation distance, not heading or a trajectory's arc
length. A zero-length translation triggers at its start. Markers remain drive companions: unfinished
actions are cancelled when the drive ends; arrival actions are the separate post-drive phase.

Waiting markers now reject invalid progress/target construction and missing, future, stale,
reversed, or nonfinite pose feedback before starting their child. The calculation uses primitive
estimator fields instead of constructing a temporary Pose2d view. An already failed, cancelled,
expired, or ended marker cannot start another child; an active child is cancelled on terminal execute.

Untriggered child actions previously retained timeout/callback metadata after the marker was
cancelled. Those never-started actions now release metadata without invoking hardware cleanup that
could depend on initialization. Ending an owner with an active child now cancels it and returns its
neutral actions even if the caller requested a normal end. An unfinished marker cannot claim normal
completion. Repeated end/release does not release the same child again, and proper teardown permits
reuse without an old executor child. A later regression also confirmed that terminal cleanup lost
an explicitly configured marker timeout; the marker now retains and reapplies that duration on
initialization. As defined by Task itself, metadata-only `cancel()` releases
runtime metadata but cannot dispatch hardware actions; owners must use normal end/cancellation
cleanup when outputs are active.

Direct marker pause/resume now forwards child lifecycle callbacks and suspends/resumes its executor
clock. The direct test checks neutral output and preservation of a child's 50 ms deadline across a
pause. This does not establish that shared task-group wrappers forward preemption correctly; that
separate library boundary remains a next investigation, along with exceptions from those callbacks.

## Selection policy and redundancy

The local selector duplicated the shared AutonomousCatalogResolver. It now delegates enabled-entry
filtering, deterministic ordering, request normalization, and fallback selection to that resolver;
the result type is an alias of the shared resolution. Tests retain the existing policy: an enabled
configured default wins, then the enabled do-nothing entry, then the first enabled entry; an empty
enabled catalog rejects resolution. A valid explicit request retains precedence.

Consequently an unknown request does **not** universally imply no motion: a configured default can
be a motion routine. The suspected selection-policy defect was not established against the shared
contract, so behavior was preserved. The misleading local always-safe comment and `Running safe
fallback` status were replaced with an accurate configured-fallback description and `Running
fallback`. A real generated no-motion routine test verifies that status and completion, while policy
tests separately exercise defaults that select motion. Catalog validation remains upstream.

## Validation and remaining work

The baseline was **12 tests, 10 failures**, with only private-to-internal marker visibility changed.
Normal intermediate progress and zero-length translation already passed. A separate one-test reuse
baseline failed after the initial marker fixes. Final product command
`verifyAresProject test`: **181 passing tests**, zero failures, errors, or skips, retaining all 157
previous cases. New coverage comprises 20 marker methods and 4 selection/runtime methods, including
deadline-group integration, terminal status, teardown, direct pause/resume, reuse, progress geometry,
feedback validity, fallback policy, stable catalog snapshot, and actual no-motion fallback completion.

Fixtures use immutable feedback snapshots, fake actions, controlled RobotClock and real task
executors/groups. Clock state and task registries are restored; the selection integration closes its
runtime and uses a fake port sampler plus memory telemetry. The full product suite includes prior
native tests, but this pass makes no competition-loop, physical-stop, whole-loop GC, or deadline
claim. Marker bytecode contains primitive snapshot getter calls and no allocating pose-view getter.

Evidence remains local under `ARESLib-Kotlin/build/audit-pass268-verified-evidence/`. The library tree
and all 410 candidate repository files remain unchanged. Next work includes capability/action
construction, coordinate contracts, match stop/cancellation/timing, and shared group preemption
forwarding. Studio alignment and archive/reference migration remain unresolved and unmodified;
no approval or release gate was bypassed.
