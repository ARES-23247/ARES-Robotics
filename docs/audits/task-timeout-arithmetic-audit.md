# Task timeout arithmetic and watchdog audit

Pass 60, 2026-09-10. Branch `codex/robot-loop-math-audit`.
Source `56dc4ff1`, candidate `17.0.3-rc.7129e238d186`.

## Findings and changes

The watchdog added elapsed intervals with signed Long arithmetic. A positive exact interval larger
than Long.MAX_VALUE, or accumulated active time across pauses, could wrap and appear unexpired.
The corrected comparison checks clock order and representability before subtracting the remaining
nonnegative timeout budget. Pause accumulation occurs only after proving the sum fits that budget.
Strict deadline equality remains valid; expiration is `elapsed > timeout`, not `>=`.

An active clock moving backwards now fails closed. Negative executor elapsed time also latches a
configured timeout failure. RobotClock uses a monotonic live timeline; changing the mock/replay
clock epoch while a task runs requires a new lifecycle. Paused wall time remains excluded. A pause
at an already overdue timestamp now marks failure instead of hiding the deadline until resume.

Expiry is latched until explicit start or reset. A deadline change preserves age and cannot rescue
an already overdue old deadline, including one not yet seen by the periodic watchdog. Lowering a
paused deadline below accumulated active time immediately fails. An extension before expiry is
allowed without resetting elapsed time. Resume restores a latched failure if the executor restored
RUNNING during preemption; queued tasks are not accidentally started by resume.

The watchdog used to sample time before waiting for the registry monitor. Another thread could
start a task at a newer timestamp while it waited; the old sample would then resemble clock rollback.
The periodic no-argument entry point now samples RobotClock after acquiring the monitor. A separate
explicit-timestamp overload supports deterministic tests. A forced monitor-contention regression
reproduced the stale-sample failure before this correction.

Failure detection and watchdog status publication have separate flags. A caller checking elapsed
time can latch failure while leaving the watchdog able to publish FAILED later. Once published, the
periodic scan avoids repeatedly rewriting that task's state. Resume and explicit lifecycle operations
still handle their own status transitions. Failure callbacks and hardware cleanup remain on the
control-loop owner; the watchdog never invokes arbitrary user callbacks or hardware IO.

The reusable expired-task list is cleared in a finally block after each scan, releasing its temporary
strong references rather than retaining tasks until the next scan. Normal scans allocate no per-task
snapshots. Weak-map traversal remains linear in registered tasks; status-map lookup during newly
expired task publication can add work. Lifecycle configuration/pause/resume may allocate metadata.
The single process-wide daemon still wakes every 50 ms; this is not a hard real-time safety watchdog.

## Evidence

The initial eight-test regression run failed seven methods against the original source. The next
retuning run failed two of 16 methods against the first correction. A final 15-method boundary run
reproduced the monitor-contention failure. Their logs and XML are retained under
`ARESLib-Kotlin/build/audit-pass60-{before,retune-before,monitor-before}` names and evidence folders.

The corrected focused gate passed 61 tests, including 15 new boundary methods and one allocation
calibration method. A seeded 1,000-case BigInteger oracle checks exact elapsed subtraction and
strict deadlines across signed timestamp boundaries. Other tests cover pause accumulation,
Long.MAX_VALUE deadlines, active rollback, negative elapsed time, deadline extension/lowering,
queued/resumed tasks, expiry latching, duplicate pause/resume and a forced blocked watchdog thread.
Existing group lifecycle, suspension, path failure and core allocation regressions also passed.

The allocation probe measured 0 bytes across 10,000 direct watchdog scans over eight active tasks.
Its escaped-array calibration measured 48,000 bytes. The existing 256-byte allocation gate was
retained. Unsupported allocation counters now produce an explicit skipped test rather than a silent
pass. This measures the registry scan, not all ScheduledExecutorService work or robot loop timing.

Focused Kover covers timeout-manager class 63/63 executable lines, 60/62 branches and 13/13 methods;
the state and visitor cover 6/6 lines each, with all eight visitor branches covered. The recursive
suspension-dispatch extension covers 8/8 lines and 10/14 branches in this focused gate. Uncovered
branches remain explicit. Evidence is in `audit-pass60-final-focus-complete.log` and
`audit-pass60-focused-evidence`. The full library gate passed 1,537 tests with no failures/errors/skips,
API compatibility, core Kover and isolated publication in 1m46s. Full coverage counts match those
above. The full run repeated the 0-byte scan measurement and 48,000-byte calibration. Source policy
passed (221 current Markdown documents, 38 explicit historical exclusions), including source
identity, archive integrity and agent guidance.

Candidate consumers passed in dependency order: FTC 109 tests, FRC 134, FTC starter 14 and
FRC starter 34, with generated-project verification and both FTC application assemblies. Studio
passed in 3m21s: shared/gateway/app test tasks all reran, with 1,779 passes and six opt-in skips.
Dashboard smoke (56) and performance (1) reran and passed, together with coverage verification,
release alignment and production-file-size checks. The six opt-in skips remain limitations.
Final XML, core Kover, logs and SHA-256 manifests are saved under
`ARESLib-Kotlin/build/audit-pass60-verified-evidence`.

## Review boundaries

`TaskTimeoutManager.kt` was reviewed in full: registry ownership, synchronized access, duration
validation, strict comparison, active-clock ordering, pause/resume/start/reset, retuning, failure
latching, callback separation, scratch lifetime and recursive suspension dispatch. Its weak identity
map, RobotClock and lifecycle callers were traced, but those reads do not close their separate files.
The new 15-method boundary test and the two-method allocation test were reviewed in full.

Caller-supplied execution elapsed time remains the caller's responsibility; task-executor timestamp
arithmetic and broader resource/callback lifecycle composition still need separate review. Task
initialization currently changes status and starts its timer in separate operations; their atomicity
against an old watchdog deadline is a further candidate for a controlled concurrency audit. Arbitrary
external state-machine rewrites are not a supported restart mechanism. A stalled control loop may
delay cleanup after FAILED publication, and this pass does not add an independent actuator lease.
No physical hardware, live desktop window, hosted CI or real-time deadline validation was performed.
The full-monorepo audit goal remains active.
