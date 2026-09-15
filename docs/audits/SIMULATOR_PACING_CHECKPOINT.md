# Simulator pacing and runtime cleanup checkpoint

Date: 2026-09-15. Base: `1bb936b3729412bcd252c6a0866504d031c76b13`.
Branch: `codex/simulator-pacing-cleanup`. Local validation; no hardware or publication.

## Scope and changes

This bounded follow-up addresses two pre-existing findings from
[BioBuzz readiness](BIOBUZZ_READINESS_REPORT.md): sleep-after-work timing and temporary simulator
snapshots left after Studio closes its owned process tree. The broad every-file audit stays paused.

- `SimFramePacer` schedules against absolute host deadlines. Work consumes the 20 ms frame budget;
  ordinary timer overshoot is corrected by the next wait. A whole-period stall rebases scheduling,
  avoiding an unbounded catch-up burst. Waiting parks the thread, handles spurious wakeups, and is
  interruptible. Every executed physics frame and mock-clock increment remains exactly 20 ms.
- `RobotClock.hostNanoTime()` explicitly separates desktop pacing/measurement from robot time.
  Controllers, freshness, control leases, estimator timestamps, and replay keep the robot clock.
- Studio creates a unique `ares-studio-sim-*` parent and passes `ARES_SIM_RUNTIME_ROOT` to its child.
  FTC and exported starter runners create their immutable classpath snapshots inside that parent.
  Studio retains process identities before stopping the tree, then removes only its owned directory
  after those processes exit. Traversal does not follow links. Unmanaged Gradle runs keep the existing
  finalizer and independent temporary snapshot. A surviving child or deletion failure is reported.
- Existing changed-part CI already runs the simulator/library tests, Studio lifecycle tests, and
  generated BioBuzz integration for these paths. Workflow edits only synchronize release identity;
  branch protections and test scopes are unchanged.

## Reproduction

Candidate: `19.0.5-rc.9b29daf45957`.
Library tree: `9b29daf45957d532e997668d82118a70c84a517d`.
Use the absolute file URI of this worktree's `ARESLib-Kotlin/build/release-repository` for every
consumer. Do not use ambient Maven-local artifacts or replace candidate bytes.

Local evidence is under `build/sim-pacing/`: library and consumer logs, exported BioBuzz project,
isolated Studio home, rendered captures, process identities, NT4 observations, and host timing CSV.

For a bounded full-loop measurement, set `ARES_SIM_TIMING_PATH` to an absolute CSV path before
launching Studio and `ARES_SIM_TIMING_FRAMES=6000` (about two minutes at 50 Hz). Its simulator inherits
the opt-in settings. The recorder preallocates bounded arrays; CSV writing occurs after capture or
normal runner exit. A forced process exit before capture completes may produce no CSV. The
diagnostic is inactive without the path. Warmup and idle/active transitions must be identified in
the data rather than interpreted as steady operation.

CSV records real host start, work duration, wait duration, deadline lateness, rebased wall periods,
runner work allocation bytes (or -1 when unavailable), and whether an OpMode is started. Work
allocation excludes the clock increment/pacing and other threads. These are desktop observations;
NT4 arrival intervals are a separate end-to-end measure, and robot mock-time telemetry still reads
20 ms. None establish target-controller deadlines, physical sensor latency, or hardware safety.

## Results

Validation in progress. Final observed results and remaining limitations will be recorded here
before this checkpoint is closed.
