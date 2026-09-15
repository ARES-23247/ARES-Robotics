# Robot-readiness checkpoint status

**Completed for the selected local/desktop scope, 2026-09-14.** Read the
[readiness report](ROBOT_READINESS_REPORT.md) and [final evidence](checkpoints/robot-readiness.json).
The saved bounded goal is complete; no further audit will start automatically.

The interrupted library check was resumed successfully: 3,036 tests, 13 APIs and the source-size
gate pass. Candidate `19.0.1-rc.6d49e143e3a9` is published only to the local validation repository;
716 FTC/FRC consumer tests, generated verification and FTC APK assembly pass. Studio test sources
compiled here, while ordinary tests were blocked by the distribution-version alignment gate.
The subsequent authorized [alignment follow-up](RELEASE_ALIGNMENT_REPORT.md) records the resolved
local gate, official-template integration and final Studio validation.
XRP verification passes 121 tests, reusable MicroPython 130, and CI routing 28.

Library source remains `1cef6f04`, tree `6d49e143e3a919569c5b3d933b8a8200adeb9a36`.
The final XRP fixture correction is `ad11a35f`: center the free-motion circle and verify its analytic
trajectory. Earlier boundary-contact timing remains preserved below as a different workload.
All owned builds are terminal. Work is local; hardware and release approval remain outside the
observed evidence. The historical file ledger and pass-277 fixes are preserved.

The authorized [desktop follow-up](DESKTOP_READINESS_REPORT.md), completed 2026-09-15, adds observed
live Studio/native-dialog behavior, fresh Lightbot creation/build/control and the dashboard baseline.
Hardware remains unavailable. Its report documents desktop cadence and deferred disk cleanup/wording
limits; all owned processes are closed. No automatic further pass is scheduled.

The app's old objective remains paused because its available API cannot edit that objective.
It was not falsely marked complete. Only the replacement tracked bounded goal is complete.

## Historical pause record

The following is the preserved pause snapshot, superseded by the completion above and the final
report. Its pending steps and timing observations describe the state at pause, not current work.

Paused on 2026-09-14 at the user's request. The bounded objective in
[ROBOT_READINESS_GOAL.md](ROBOT_READINESS_GOAL.md) replaces the old file-count goal. Do not resume
automatically, create another task, start sub-agents, or start another broad audit.

## Reproducible source checkpoint

- Isolated checkout: `.codex-validation/audit-pass191`, branch `codex/robot-loop-math-audit`.
- Readiness source commit: `1cef6f040dd84e329f74d95d87d60d4568a496df`.
- Library tree: `6d49e143e3a919569c5b3d933b8a8200adeb9a36`.
- Reserved local candidate: `19.0.1-rc.6d49e143e3a9`; canonical library version `19.0.1`.
- No candidate artifact directories existed for that coordinate when paused. Publication is pending.
- Prior useful production fixes, candidate and complete evidence remain preserved at
  [pass 277](checkpoints/pass277.json), source checkpoint `779fb0a2`.
- This readiness commit changes tests, evidence handling, the plan and source identity; it introduces
  no production runtime changes. The unrelated main checkout and other tasks' processes were preserved.

## Completed work and evidence

The combined pass-277 changes were reviewed around generated control ownership, cancellation,
target application, freshness, partial preparation and lifecycle cleanup. The previous fixes remain:
per-host drive state, cleanup across all owners after failures, exact target comparisons, preservation
of multiple fields in a preset, authored freshness guards and idempotent generated shutdown.
These defects existed before pass 277. The Studio preview API adapter mismatch was introduced by
that batch and fixed within it; Studio execution remains blocked by the existing release gate.

New actual generated FTC/FRC scoring scenarios pass through production state, task, superstructure,
controller and mock-IO code: startup, explicit host enable/disable, simultaneous flywheel/intake
targets, missing/failed/stale feedback, routine cancellation, partial preparation, rollback after
cleanup exceptions, and inert closed lifecycle. Physical constructors are deliberately unexecuted
stubs. These are representative host scenarios, not execution on league controllers or calibrated plants.

Independent math fixtures pass: hand-calculated wheel speeds, rigid-body swerve reference velocities,
analytic straight/clockwise/counterclockwise trajectories, scalar Bayesian EKF posteriors, and delayed
noisy vision. Vision reduced RMS position error from 3.9–9.1 cm to about 0.8 cm on the selected paths.
Fixture development exposed setup/assumption failures, not a confirmed new production defect.

XRP regeneration and complete starter verification passed (121 tests), reusable MicroPython host
tests passed (130), and changed-part CI routing passed (28). CI already routes shared library changes
to the candidate and consumers; explicit regression assertions and readiness artifact retention were added.

Local logs, reports and scripts are in `ARESLib-Kotlin/build/robot-readiness/`.
The [tracked pause record](checkpoints/robot-readiness-paused.json) preserves source hashes,
performance samples and evidence hashes. Original full logs/XML remain local build outputs.

## Preliminary desktop performance assessment

Code budgets: FRC and XRP 20 ms; FTC documentation indicates 50–100 Hz (20–10 ms), with actual
OpMode scheduling still requiring target verification. The initial JVM measurements had p99 execution
near 0.016 ms, worst below 1 ms and about 1.05 KB allocated per cycle. XRP CPython simulation had
p99 near 1.01 ms and worst 1.95 ms. No unpaced sample exceeded its loop budget.
The JVM's paced intervals reached about 32 ms despite a 20 ms request; the first XRP paced maximum
was 20.43 ms. Scheduling jitter must be assessed separately from execution cost. Exact observations
and boundaries are retained in the pause record; runs may differ with host load/JIT scheduling.

No demonstrated production bottleneck was established, so no speculative optimization was added.
Desktop allocations, sleep behavior and synthetic sensor-to-output measurements do not establish
Control Hub, roboRIO or Pico/MicroPython behavior. XRP tracing measures peak/retained Python memory,
not total allocated bytes. Physical bus, network transport and acquisition latency are unmeasured.

## Interrupted work and next steps, only after explicit resume

The owned full-library command (`test apiCheck verifyAresLibSourceFileSizes publishReleaseValidation`,
using the reserved candidate, one worker and the existing low-memory init script) was interrupted
with Ctrl-C at pause. Tool session `29411` returned exit 1 from cancellation. Do not label this
a test failure or a completed library run. No matching task-owned client/worker remained in the
post-interruption process check. Existing shared daemons were not terminated.

1. Verify checkout/source identity and running processes. Resume the full library/API/local candidate
   check with the frozen source; review `library-full.log`. The focused scenarios passed, but full
   library completion and the latest profile instrumentation still need final consolidated evidence.
2. After successful candidate publication, run the existing dependency-ordered FTC/FRC and starter
   generated-project, unit, simulator and FTC APK checks against that exact local candidate. These
   new-candidate consumer checks have not started; pass-277 evidence remains the prior baseline.
3. Recheck Studio normally and compile affected consumers. Preserve the release gates: Studio tests
   were blocked by distribution version alignment, and source policy still stops at the missing
   FTC starter 19.0.0 archive. Documentation links and shared guidance passed before that stop.
   Do not change protected archive/workflow/template migration or bypass gates to manufacture green.
4. Assess final measurements against explicit budgets, retain per-suite evidence and candidate
   hashes, refresh the historical ledger only for actual reviewed scope, and write the final readiness
   report. Keep extreme-input, speculative and cosmetic leads deferred. Unreviewed files are not a queue.
5. List physical enable/disable, stale feedback, concurrent load, cancellation, failure/shutdown,
   loop distribution/GC/deadline and sensor-to-output checks for actual controllers. No hardware was available.

The bounded checkpoint is **not complete**. The app's older goal also remains paused: the available
goal API cannot replace its objective. This saved bounded plan governs any explicit resumption.
