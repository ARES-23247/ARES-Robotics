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
  An inherited `JAVA_TOOL_OPTIONS` temporary-directory property also covers older exported JVM
  projects. Existing JVM options are preserved; this launch's temporary directory takes precedence.
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
The [evidence manifest](SIMULATOR_PACING_EVIDENCE.json) records source identity, the changed-file
inventory, results, and hashes of retained local artifacts.

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

## Verified behavior

Implementation checkpoint: `434472d1`. A rendered Studio window (PID 44628, HWND 2622780) reached
settled presentation. It loaded the freshly exported BioBuzz 1.0.6 project and used the real
Verify & launch flow with the candidate above, generated robot code, mocked hardware, season
physics, and normal NT4 transport. No alternate simulator wiring or truth-to-estimator substitution
was introduced.

- Start driving initialized and armed the actual TeleOp. Translation, strafe, release, simultaneous
  drive/intake/flywheel commands, and a feeder press worked. The feeder consumed one of four balls.
- Stop while drive, intake, flywheel, and feeder keys remained held produced a DISABLED lifecycle
  acknowledgement, a neutral drive acknowledgement, and identical complete stopped game snapshots
  two seconds apart. Restart restored four balls and driving worked again.
- The Field2D view expanded and restored while global Stop stayed available.
- Rebuilding `:FtcRobotController:bundleLibCompileToJarDebug --rerun-tasks` passed while the original
  simulator PID 39796 remained alive, confirming the immutable runtime classpath still works.
- Studio's global Stop removed simulator 39796, wrapper 4296, daemon 11956, and its complete
  114-file / 595,804-byte runtime directory. Studio stayed alive, ports 5810/5002 were released,
  and Launch simulator became available again.
- The same Studio session launched a fresh simulator (PID 24788) into a different owned directory.
  A 36.879-second repeated driving pattern with intake and flywheel held produced 1,843 pose frames,
  no missing sequence numbers, and enabled game frames throughout. Observed X/Y ranges changed
  while opposite directions kept the robot near its spawn.
- Normal app close removed the second simulator and all recorded owned children, its 114-file
  directory, the desktop runtime snapshot, and listeners on 5810/5002/49628. The app's Gradle run
  exited successfully. No manual process kill or manual runtime-directory deletion was needed.

## Performance evidence and budget assessment

The interactive target is 50 Hz on average with a 20 ms work budget. This Windows/JBR 21 desktop
is not a hard-real-time controller. Two bounded captures recorded 6,000 frames each; the first
250 frames of each capture are treated as warmup. Active statistics include 2,947 and 873 frames,
respectively. Both captures retain startup/idle frames and transitions for separate inspection.

| Active simulator runner | First capture | Second capture |
| --- | --- | --- |
| Mean wall-clock period | 19.996 ms | 19.998 ms |
| Period p95 / p99 | 31.454 / 32.008 ms | 31.446 / 31.992 ms |
| Worst active period | 33.066 ms | 32.477 ms |
| Work mean / p99 | 1.336 / 2.998 ms | 1.351 / 3.084 ms |
| Worst active work | 8.909 ms | 6.607 ms |
| Active work over 20 ms | 0 | 0 |
| Mean runner work allocation | 137,121 bytes/frame | 138,499 bytes/frame |

The average pacing target is met and active execution fits the work budget in these samples.
Uniform 20 ms frame spacing is **not** achieved: Windows timer granularity remains visible in
approximately 15.7/31.4 ms periods. The thread parks between frames; this fix does not busy-spin
or change the system timer configuration. About 6.9 MB/s of runner work allocation at 50 Hz is a
measured desktop baseline, not a zero-allocation claim. It did not cause an observed active work
deadline failure here; no speculative allocation rewrite was made.

The worst recorded work delays, including warmup, were 238.028 ms and 188.049 ms, both on frames
before the OpMode was started, around initialization. Those observations are retained rather than
hidden in the active statistics. Host scheduling rebased 12 and nine wall periods, respectively,
without skipping/enlarging robot physics steps or replaying a backlog of controls.

An independent read-only NT4 capture covered 395.975 seconds in the first simulator process:

| End-to-end arrivals | Previously recorded baseline | This checkpoint |
| --- | --- | --- |
| Mean active pose interval | 30.955 ms | 20.002 ms |
| Pose p95 / p99 | 62.110 / 62.965 ms | 45.956 / 47.043 ms |
| Mean complete BioBuzz snapshot interval | 154.80 ms | 100.062 ms |

The new pose capture had zero missing sequence numbers. Its worst pose arrival interval was
61.970 ms; complete game snapshots reached 318.224 ms across initialization. Arrival batching is
separate from runner work and deadline measurements. During the second sustained-input window,
mean pose arrival was 20.004 ms (worst 48.196 ms), with zero missing sequences. That sustained
window occurred after the second bounded work capture ended; it is additional functional/arrival
evidence, not another CPU/allocation measurement.

## Checks preserved

| Local check against the candidate | Result |
| --- | --- |
| Full ARESLib tests, API compatibility, size ratchet, isolated publication | 3,046 tests passed |
| Studio app/shared/gateway | 2,684 passed, six explicit opt-in skips, zero failures |
| FTC reference robot / simulator / APK | 190 tests passed |
| FRC consumer | 306 tests passed |
| FTC starter generation / verification / simulator / APK | 17 tests passed |
| FRC starter generation / verification | 206 tests passed |
| Actual exported BioBuzz generation / verification / field / APK | 43 tests passed; repeated through Studio |
| CI changed-path and starter-export regressions | 32 tests passed |
| Source policy and local documentation links | Passed |
| Five updated template archives | Reproduced byte-for-byte |

New pacing tests model coarse timer boundaries, variable work, long stalls, spurious wakeups,
clock wrap, interruption, and independent robot/host clocks. The existing physics integration
reference still verifies fifty fixed frames move a constant-velocity body exactly one meter.
Cleanup tests launch a real JVM using legacy `Files.createTempDirectory` behavior, cover normal
and failed exits, stop/restart/shutdown, preserve a neighboring directory, and retain files while
an owned process remains alive. These tests run in existing CI scopes; no remote CI was started.

Initial local attempts hit the existing version-base/preflight checks and a sandbox denial for
a temporary Git test fixture. Pins were synchronized and that fixture was rerun with appropriate
filesystem access. An earlier local candidate remains immutable; the final candidate differs in
library source only by removal of an extra EOF blank line. Final library/consumer checks used the
identity recorded above. No failing assertion or required check was disabled.

## Remaining limits and deferred observations

- Windows wakeup jitter and the non-running initialization delays remain. The evidence supports
  a separate timer/initialization investigation if more uniform desktop frame spacing is required;
  it does not support removing real-time pacing or changing robot control budgets.
- Work allocation covers the simulator runner's body only. Other threads, complete sensor-to-output
  latency, physical buses, and target-controller timing still require separate instrumentation or
  hardware. Existing hardware-readiness checkpoints remain open.
- Some raw subsystem output topics retain their last pre-Stop sample after their publisher closes.
  They were not treated as fresh measurements of disabled motor outputs. The lifecycle tests and
  the disabled game/control acknowledgements are separate evidence. The health card also showed
  empty metrics after the full process reconnect while field/control telemetry worked. These
  diagnostic freshness observations are deferred; regression provenance has not been established.
- This checkpoint does not stabilize the separately recorded intermittent waypoint allocation
  test, validate physical mechanisms, or resume the broad audit.

The selected pacing, control, rebuild-isolation, and cleanup scenarios pass. No known high-impact
defect remains in the changed scheduling/cleanup paths. Changes are committed locally; a future
release still requires the normal protected PR, hosted checks, packaging, and candidate promotion.

## Release-validation addendum

PR [102](https://github.com/ARES-23247/ARES-Robotics/pull/102) exposed the previously recorded
waypoint allocation assertion in Monorepo CI run `35008036595`, attempts one and two. A focused
local recheck passed on unchanged source; the second hosted failure ended diagnostic retries.
Neither the waypoint loader nor its original test differed from released main. The short hosted
failure logs did not retain the measured byte count, so the precise source of those bytes remains
unproven. The original failures are preserved in `build/sim-pacing/release/`.

The test now warms the complete counter/lookup/counter routine with both missing and present map
implementations before measurement, avoiding a type-profile change between the two cases. Each
lookup escapes its result through a volatile test sink. Three fixed windows must all report exactly
zero bytes; there is no best-window selection, retry loop, or relaxed threshold. An intentionally
allocating control must report at least 16 bytes per lookup in each window, proving that the counter
detects escaped allocations. Every measured window is printed for future diagnosis.

The focused local result was `missing=[0,0,0]`, `present=[0,0,0]`, and
`escapedControl=[240000,240000,240000]` bytes over 10,000 lookups per window. The complete local
library tests, API check, and isolated publication then passed. The new test-only library source
tree is `03a39314803a59ee44996be066a015969624f8aa`, with local candidate
`19.0.5-rc.03a39314803a`. Production library code and the five template archives are unchanged from
the measured local checkpoint above; that original evidence manifest remains a historical record.
Hosted consumer and package checks must run against the updated PR before protected promotion.
