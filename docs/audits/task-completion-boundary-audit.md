# Task completion boundary audit

Pass 62, 2026-09-10. Local source commit `2eeb9c2a`; candidate
`17.0.3-rc.2b248e8b8af9`, bound to the exact library tree in
`release/ares-source-tree.txt`. This pass follows the timeout arithmetic and lifecycle metadata
reviews, concentrating on normal completion, group propagation and marker cleanup ownership.

## Confirmed defects and corrections

The executor checked domain completion before elapsed timeout. A task that became ready on the
first update after its deadline could complete, invoke its success callback and initialize queued
work without ever executing the timeout check. Sequential, parallel, race and deadline groups had
the same child ordering. Some groups also accepted a true predicate after it marked the child
failed. These behaviors are independent of the periodic watchdog: deterministic tests keep its
clock at zero while advancing the executor timestamp.

The shared internal `completionReady` gate checks status and both timeout time domains before and
after the domain predicate. It publishes timeout failure without invoking user callbacks under
the timeout monitor. Existing owners deliver callbacks and interrupted cleanup. Executors, group
children, path markers and pathfinding delegates use this gate. Groups also check their own budget
before accepting child completion or initializing a sequential successor; a child's slow predicate
cannot spend the parent's budget and still start the next child.

Default `Task.end` previously overwrote FAILED/CANCELLED with COMPLETED, and left its watchdog
registration active while invoking completion callbacks. A watchdog scan could therefore overwrite
successful completion during a callback. `finishTask` now decides terminal status and removes the
deadline under the same monitor used by the watchdog. Normal end preserves failure/cancellation
and rejects an expired deadline; interrupted end preserves existing failure or cancels. Success or
failure callbacks run after releasing that monitor. Exact timeout equality remains valid: expiration
is strictly greater than the configured duration. Callback exceptions propagate with the deadline
already removed; an owning executor may subsequently mark the task failed and perform cleanup.

An end override can itself cross a deadline or reject success. The executor now inspects status
after normal end before advancing. Group completion shares one `completeChild` implementation in
place of four duplicated cleanup blocks. It handles rejected or throwing endings with interrupted
cleanup, propagates status to the parent and records cleanup exactly once. A group exception no
longer makes its failed normal child ending count as completed cleanup. Parent cleanup drains any
pending safe actions through the existing owner flow.

Path markers remain owned until their normal end succeeds. Rejected endings receive interrupted
cleanup; throwing endings remain reachable by parent cleanup. Both stop the path and prevent a
failed marker from silently disappearing. The pathfinding wrapper also propagates a delegate that
fails during end before attempting its own successful terminal transition.

## Verification

The first 12 new boundary tests ran against the previous source: 11 failed, with the exact-deadline
control passing. A separately added parent-timeout test also failed before its corresponding fix.
Logs and original XML are retained in `ARESLib-Kotlin/build/audit-pass62-before.log`,
`audit-pass62-before-evidence`, `audit-pass62-parent-before.log` and
`audit-pass62-parent-before-evidence`.

The corrected focused suite passed 96 tests without failures, errors or skips, including 20 new
methods: 16 completion boundary cases, three path marker cases and one allocation probe. Cases
cover all four group types, executor queue suppression, status changes inside predicates, time
spent in predicates/end, callback exceptions, parent deadlines, exact equality, failed normal
cleanup and marker ownership. The extra integration cases were added during the fix; the report
does not claim that all 20 were individually run against the old source.

The new completion helper covered all three executable lines, four branches and its one method.
Focused timeout-manager coverage was 88/88 lines, 80/84 branches and 18/18 methods. The executor
covered 119/145 lines, 47/70 branches and 12/12 methods. Group helper coverage was 16/24 lines,
5/10 branches and 3/3 methods. These are measured test coverage, not evidence of exhaustive
interleaving or lifecycle coverage. Full aggregate task/group/executor reviews remain partial.

The allocation probe measured zero bytes over 10,000 warmed completion checks. The existing
watchdog probe measured zero bytes over 10,000 scans of eight registered tasks; its escaped-array
calibration measured 48,000 bytes. Both probes retain a 256-byte tolerance and explicitly skip when
the JVM allocation counter is unavailable. Focused XML/Kover are saved under
`ARESLib-Kotlin/build/audit-pass62-focused-evidence`.

The full library gate passed 1,566 tests with no failures/errors/skips, API compatibility, core
Kover and isolated candidate publication in 1m50s. Source policy passed, including source identity,
archive integrity, agent guidance and links in 223 current documents (38 historical exclusions).
Candidate consumers passed in dependency order: FTC109, FRC134, FTCstarter14 and FRCstarter34
tests, generated-project verification and both FTC application assemblies. Studio passed in 2m58s:
shared/gateway/app test tasks reran, with 1,779 passes and six opt-in skips. Dashboard smoke56 and
performance1 reran and passed, along with coverage verification, release alignment and production
file-size checks. Final XML, core Kover, logs and SHA-256 manifests are retained under
`ARESLib-Kotlin/build/audit-pass62-verified-evidence`. The six opt-in skips remain limitations.

## Review boundaries and remaining work

The timeout manager was reread in full; its prior arithmetic and metadata review is refreshed.
The new completion helper and all three new test files were reviewed in full. TaskExecutor and
TaskGroupDispatcher receive partial file credit for this completion/cleanup pass. Task.kt and
PathfindToPoseTask retain partial credit. The ledger does not infer a full file review from a suite
passing or from a changed call site.

This introduces no per-update collections or user callbacks under the watchdog monitor. It adds
status/timeout checks around completion predicates, so the zero-allocation result is not a CPU
speedup claim. Registry lookup cost, group equality/identity and duplicate membership, elapsed
arithmetic during suspension/preemption, arbitrary callback reentrancy, simultaneous user lifecycle
bodies, group initialization failures and marker resource ownership remain separate audit areas.
The completion gate observes time at defined boundaries; it does not interrupt a blocked predicate,
callback or hardware call. Overrides must still honor the default Task lifecycle contract. Raw
external status rewrites are not a supported concurrent lifecycle protocol.

No physical hardware, live Studio window, hosted CI, electrical safety, hard realtime deadline or
robot-loop jitter validation was performed. The full-monorepo audit goal remains active.
