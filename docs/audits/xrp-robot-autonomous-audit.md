# XRP robot lifecycle and autonomous control audit

Pass 8 reviews `ares_micro/robot.py`, `ares_micro/opmode.py`, their new regression
files, and the runtime README. Final source commit: `64996819`, following `e64eb2b0`.
The subsystem implementation and generator were inspected to establish boundaries;
their remaining behavior is still open for the next pass.

## Findings and resulting behavior

- The waypoint follower previously returned `reached=True` as soon as position entered
  tolerance, even when its returned angular command still requested final alignment.
  The routine then discarded that command. Completion now requires both position and
  heading tolerance; the new optional heading tolerance defaults to 0.05 radians.
- Waypoint `speed` was unused. Forward and reverse translation now obey both waypoint
  and follower speed ceilings. Multiplying translation by the cosine of the steering
  error reduces cross-track motion while turning; a perpendicular target starts with
  effectively zero translation. This remains a proportional differential-style follower,
  including when used by a mecanum robot; it does not generate lateral velocity.
- Invalid waypoint/follower configuration, invalid current pose, invalid routine periods,
  and negative/non-finite wait durations are rejected. Explicit empty `steps=[]` now
  means an empty routine instead of silently falling back to supplied waypoints.
  WAIT/ACTION execution still advances at most one step per tick, and failed actions
  do not advance the routine.
- Completed autonomous execution previously changed the robot to teleop without a
  Teleop request. It now stays in autonomous mode, keeps the drivetrain neutral, and
  holds mechanism control under the existing fresh autonomous lease. Stop/expiry still
  neutralizes everything. An explicit mode request is required to enter teleop. A
  completion flag prevents repeated calls into the finished routine.
- `shutdown()` previously allowed later INIT/Start calls to resume the same robot.
  Shutdown is now terminal. Listener ownership is detached before cleanup, and a
  client-cleanup failure cannot skip listener closure. Cleanup errors can still propagate
  to the caller after other owned cleanup is attempted.
- Invalid loop periods and non-finite odometry now latch a fault and attempt all neutral
  outputs before further control. Simulator constraint results must contain three finite
  coordinates before installation. Brownout threshold validation matches the project's
  finite 3.0..6.0 V contract.
- Disconnected cycles no longer construct subsystem telemetry snapshots. Unconstrained
  cycles no longer allocate simulator pose tuples; constrained cycles reuse the proposed
  tuple. Duplicate autonomous lease branches and duplicated emergency-stop code were
  removed without weakening the shared stop boundary.

The completed-autonomous behavior intentionally preserves a final ACTION's mechanism
target. Disabling every mechanism on completion would make a final target-setting action
ineffective. Studio already sends neutral drive intent while its requested mode is
autonomous; the runtime now preserves that mode rather than silently interpreting future
frames as teleop. Autonomous mechanism control remains subject to the same control lease.

## Validation and efficiency evidence

The shared Python suite passes **85 tests**, including **23 new regressions** in this
pass. Three independent constant-twist integrations exercise forward, reverse, and
perpendicular waypoint approaches and finish inside both position and heading tolerances.
Other cases cover wraparound, bounded speed, invalid waits/periods/poses, action failure,
terminal shutdown, cleanup failures, completed-action leases, and explicit mode changes.

A 100-cycle test confirms zero disconnected subsystem-state snapshots while both encoder
inputs are still read exactly once per cycle. Connected telemetry snapshots each subsystem
once and preserves the supplied 35 ms period. Source/generated XRP verification and the
separately extracted final starter each pass **107 tests**; these starter tests are
distinct from the shared-runtime unit suite.

The import-inclusive trace reports **98.2% executable-line coverage** for `opmode.py`
and **83.6%** for `robot.py`. The new autonomous and robot regression files show 97.6%
and 99.3%, respectively. These are scoped line-execution measurements, not branch coverage.
Annotated sources are under `ARESLib-Kotlin/ares-micro/build/audit-pass8-trace/`.

A desktop CPython comparison of the disconnected INIT path with ten fake mechanisms
measured a median 4,234 ns/cycle before the loop-work change and 2,545 ns/cycle afterward.
It used 500 warmup cycles per version and seven alternating batches of 10,000 cycles,
with no physical IO or network cost. This measures the disconnected-path optimization;
it is not a Pico timing/jitter or zero-allocation claim. The deterministic snapshot/read
counts provide the stronger evidence about work removed.

Final validation uses candidate `17.0.3-rc.c25aa89f6134` and library source tree
`c25aa89f61348be6786537e1eb94266ff5e23829`. The final unpublished XRP 3.0.3 archive is
`5f7b481972216961bf9ba770ffe3241663a1e6853425895f685bb661e5d16003`.
The other three deterministic archives reproduced unchanged. The final library test/API/
local-publication gate, FTC/FRC and starter consumer gates, Studio gate, and release
alignment passed in dependency order. Available reports contain 1,089 passing library
tests, 291 passing robot/starter tests, and 1,244 passing Studio tests with six opt-in
skips. Unchanged JVM library, simulator, FRC, and Studio shared/gateway tests reused
up-to-date results; FTC TeamCode and Studio app tests executed. These report totals are
not a claim that every JVM test executed again. Source/archive policy, guidance, and
links in 169 current documents also passed.

The earlier `537fef1427a1` candidate and
initial pass-8 archive are superseded local artifacts, not release evidence for the final
autonomous behavior. No candidate bytes were reused under a different source identity.

Evidence logs are `ARESLib-Kotlin/build/audit-pass8-micro*.log`,
`ARESLib-Kotlin/build/audit-pass8-loop-benchmark.log`,
`ARESLib-Kotlin/build/audit-pass8-final-*.log`,
`ARES-XRP-Starter/build/audit-pass8-final-verify.log`, and
`.codex-validation/audit-pass8-final-standalone/audit-pass8-final-standalone.log`.

## Remaining coverage

These are CPython, fake-hardware, and desktop integration results. No physical motor,
radio, Pico interpreter, closed-loop tracking accuracy, or worst-case loop timing was
measured. The robot facade still relies on caller-provided hardware and battery suppliers;
its default battery model is 6 V, while the physical starter supplies the hardware reading.
Some action-dispatch, recovery-failure, and malformed runtime-mutation branches did not
execute in this suite. Per-cycle JSON and controller-result allocations remain.

The next pass must review subsystem PID reset/anti-windup, bang-bang direction/hysteresis,
derivative wrapping/filtering,
measurement reuse, target types, and descriptor features that the Python runtime may
accept without implementing. Generator-to-runtime speed/feature propagation and Studio
requested-mode feedback also remain open. Source review or passing suites in this pass
do not close those files or the whole-repository audit goal.
