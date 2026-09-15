# Task lifecycle metadata and watchdog audit

Pass 61, 2026-09-10. Branch `codex/robot-loop-math-audit`.
Source `4a87a821`, candidate `17.0.3-rc.93435bb3591c`.

## Findings and changes

Default task initialization published RUNNING before acquiring the timeout registry monitor to
establish its new deadline. While initialization waited, the watchdog could expire the old deadline
and replace the new status with FAILED. Initialization then refreshed the timer but left the task
incorrectly failed. Reset and cancellation had analogous races: an old watchdog could restore
FAILED after status removal or overwrite CANCELLED before deadline removal completed.

Three deterministic monitor-contention tests reproduced those outcomes. Initialization now changes
status and starts its deadline under the watchdog's monitor. Reset and cancellation remove the old
deadline and publish their respective status changes under that same monitor. This makes these
metadata transitions indivisible relative to watchdog scans. A fresh start still expires at its new
strict deadline; the fix does not disable the watchdog or extend the timeout.

Virtual runtime cleanup remains outside the timeout monitor, preserving overridden cleanup and
avoiding arbitrary user code inside the watchdog critical section. Reset and cancellation remove
their own deadline before invoking that cleanup, so a thrown cleanup exception cannot leave the old
timer armed. The original exception propagates. These methods retain their existing contract: they
do not invoke end or dispatch hardware-neutralization actions. Owners requiring hardware cleanup
must continue to use the executor's interrupted-end/cancellation path.

The default virtual cleanup may perform an additional no-op timeout removal after the coordinated
transition. It is retained to preserve the override contract; this is lifecycle work, not per-frame
work. The change adds no polling, threads, locks around hardware calls or controller-loop allocation.
No overall speedup or real-time deadline guarantee is claimed.

The status registry was reviewed in full. Its existing identity-based weak-key behavior correctly
separates equal task instances and tolerates mutable hash codes. Concurrent markFailed calls have
one winner until an explicit state transition or reset. The registry deliberately permits arbitrary
explicit transitions; it is not a legal-lifecycle graph validator. No production change to that file
was needed.

## Evidence

All three initial race tests failed against the previous source. The baseline log/XML are retained
in `ARESLib-Kotlin/build/audit-pass61-before.log` and `audit-pass61-before-evidence`.
Each test blocks a lifecycle operation on the timeout monitor, expires the old deadline from the
owning thread, then releases the waiting operation and checks the final status and timer behavior.

The corrected focused gate passed 76 tests, including nine new methods: six lifecycle tests and
three registry tests. Additional cases cover a fresh start without timeout configuration, virtual
cleanup dispatch without invoking end, cleanup exceptions, exact new deadlines, unrestricted
explicit statuses, equal-but-distinct task identities, mutable hash codes and eight concurrent
failure callers. Existing timeout, sequencing, path-failure and allocation tests also passed.

Focused Kover: TaskStateMachine covers 8/8 executable lines, 4/4 branches and 5/5 methods.
TaskTimeoutManager covers 69/69 executable lines, 60/62 branches and 16/16 methods. The broader
aggregate task file remains partial; these counts are not a claim that every lifecycle interleaving
or every task subclass was covered. Focused evidence is retained in `audit-pass61-final-focus.log`
and `audit-pass61-focused-evidence`. The full library gate passed 1,546 tests with no failures/errors/skips,
API compatibility, core Kover and isolated publication in 1m47s. Source policy passed (222 current
Markdown documents and 38 explicit historical exclusions), including source identity, archive
integrity and agent guidance.

Candidate consumers passed in dependency order: FTC 109 tests, FRC 134, FTC starter 14 and
FRC starter 34, with generated-project verification and both FTC application assemblies. Studio
passed in 3m19s: shared/gateway/app test tasks all reran, with 1,779 passes and six opt-in skips.
Dashboard smoke (56) and performance (1) reran and passed, together with coverage verification,
release alignment and production-file-size checks. The six opt-in skips remain limitations.
Final XML, core Kover, logs and SHA-256 manifests are saved under
`ARESLib-Kotlin/build/audit-pass61-verified-evidence`.

## Review boundaries

TaskStateMachine and both new test files were read in full. The timeout manager's previous full
review is refreshed with the three coordinated lifecycle operations. Task.kt's initialize, cancel
and reset boundaries were reviewed against virtual cleanup and the watchdog; its existing partial
status remains for the rest of the aggregate file.

The guarantee is coordination with the watchdog, not support for concurrent arbitrary user
lifecycle operations on the same task. Task initialization bodies, cleanup overrides, completion
callbacks, executor completion handling, timestamp arithmetic and marker resource ownership remain
separate review areas. Raw external status rewrites do not constitute a supported restart protocol.
No physical robot, live Studio window, hosted CI or hardware timing validation was performed.
The full-monorepo audit goal remains active.
