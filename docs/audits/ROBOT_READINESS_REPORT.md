# Bounded robot-readiness checkpoint

**Selected local/desktop validation is complete.** The realistic scenarios below pass, and no known
high-impact production defect remains within that selected scope. This is not physical robot or
release approval. Target-controller timing/IO and existing Studio/release gates remain unverified.
The open-ended file audit is superseded; no further broad pass will start automatically.

Source: `1cef6f04`, followed by XRP fixture correction `ad11a35f`, on local branch
`codex/robot-loop-math-audit`. Library tree `6d49e143e3a919569c5b3d933b8a8200adeb9a36` was validated as
`19.0.1-rc.6d49e143e3a9`. See [durable evidence](checkpoints/robot-readiness.json),
[current status](ROBOT_READINESS_STATUS.md), and [saved objective](ROBOT_READINESS_GOAL.md).

## Verified robot scenarios

The FTC/FRC fixture uses the existing flywheel-shooter and intake-conveyor templates. It compiles
and executes actual generated state, registries, controllers, lifecycle, superstructure and project
runtime against simulated IO. The host follows refresh → Redux → tasks/superstructure → outputs,
with explicit enable gating. Physical IO constructors are unexecuted stubs that throw if called;
the test does not exercise a HardwareMap, vendor bus, Driver Station transport or calibrated plant.

| Scenario | Observed behavior |
|---|---|
| Startup and missing feedback | Both mechanisms remain neutral; enabling without feedback selects the declared fault posture. |
| Enable/disable | Disable neutralizes latched targets. Re-enable remains neutral until a new command. The fixture explicitly selects STOW as its disabled state. |
| Simultaneous commands | Generated RUN targets reach both the 300 rad/s flywheel and 4 V intake; outputs stay bounded. Existing target-composition regressions also preserve multiple fields of one subsystem. |
| Autonomous cancellation | Cancelling the active routine and disabling the host neutralizes outputs; the cancelled wait/action cannot restart them after enable. Existing consumer tests cover cancellation exceptions and terminal stop. |
| Failed/stale feedback | One failed refresh or 260 ms-old cached feedback faults the scoring group and neutralizes both mechanisms. |
| Partial preparation/cleanup | A later factory failure releases the prepared child even if release throws. Required-device rollback attempts both generated IO owners when one safe callback throws, clears ownership and leaves outputs neutral. Closed lifecycle remains inert. |
| XRP starter | Startup, leased drive, loss of control, explicit rearm, STOP and inert shutdown pass through the actual runtime, simulated motors and generated rangefinder. Existing starter/micro suites retain brownout, failed feedback/output and preparation/cleanup checks. |

New fixtures:
[generated FTC/FRC scenarios](../../ARESLib-Kotlin/codegen/src/test/kotlin/com/areslib/codegen/GeneratedRobotReadinessTest.kt),
[executed robot host](../../ARESLib-Kotlin/codegen/src/test/resources/readiness/ScoringRobot.kt.txt),
[XRP host scenario](../../ARES-XRP-Starter/tests/test_robot_readiness.py).
League-host consumer tests complement these fixtures; they do not turn simulated IO into hardware
evidence. Generated reset-failure cleanup was inspected; new fault injection specifically exercises
task release and IO safe/close ownership, not every possible controller-reset exception.

## Findings and useful fixes preserved

The combined pass-277 changes were reviewed against `614dc76c`. “Pre-existing” below means present
at that batch's beginning; earlier origin across all previous audits was not reconstructed.

| Impact and origin | Finding and disposition |
|---|---|
| High; pre-existing at pass 277 | Generated drive state leaked between hosts. Each controls instance now owns its drive buffer/emitter; interleaved-host and cancellation regressions pass. |
| High; pre-existing at pass 277 | Cancellation/cleanup exceptions prevented other owners from stopping or releasing. Aggregate cleanup and idempotent lifecycle closure preserve all attempts and failure evidence. |
| High; pre-existing at pass 277 | Authored guard ages were ignored, target hash collisions lost updates, and multiple fields overwrote each other. Explicit freshness limits, exact target comparison and projected target initialization preserve commands. |
| Performance; measured in pass 277 | Stable controls metadata reads allocated 1,280,000 bytes over 10,000 reads. Caching reduced the same fixture to zero bytes. This is focused evidence, not whole-loop allocation. |
| Audit-introduced; fixed in pass 277 | The health-binding API change left Studio's preview adapter incompatible. Signature/age semantics were fixed and compile checks pass; preview execution remains blocked by release alignment. |
| Test-fixture issue; fixed here | The XRP benchmark's original circle reached the field edge. It now starts at (0, −0.25 m) and checks an independent circle. Older timing is retained as a different, boundary-constrained workload. |
| Lower priority; deferred | Sequence exhaustion, timestamp/sentinel extremes, additional schema/LUT combinations and cosmetic deprecations. No new realistic defect was established behind these leads. Already completed safe numerical fixes remain. |

No production change was necessary in this bounded checkpoint. Earlier extreme-input fixes were
not reverted. The prior concern about final-pose capture calling a throwing custom FTC getter remains
an extension-hardening hypothesis: both supplied adapters return `robot.base`, and
`DriveSubsystem.odometryPose` reads cached Redux state. No realistic failure was established for
the selected configurations. Broader unreviewed files remain outside this checkpoint.

## Independent math evidence

[RobotReadinessMathTest](../../ARESLib-Kotlin/core/src/test/kotlin/com/areslib/math/estimation/RobotReadinessMathTest.kt)
uses references independent of the implementation:

- Mecanum wheel speeds match hand-calculated basis/mixed-motion values; differential speeds match
  concentric circles. Swerve vectors match differentiated rigid-body module positions over ±3 m/s
  forward, ±1 m/s lateral and ±3 rad/s rotation.
- EKF propagation follows closed-form continuous straight and clockwise/counterclockwise paths for
  six seconds at 20 ms intervals, including angle wrapping. This is not a forward/inverse round trip.
- A diagonal EKF correction and covariance match independent scalar Bayesian posterior precision.
- With 2% translation bias, 0.8% yaw-rate bias and 100 ms-delayed noisy vision, 59 observations per
  trajectory are accepted. Position RMS over the last five seconds is:

| Yaw rate | Odometry alone | Fused |
|---|---:|---:|
| −0.8 rad/s | 5.280 cm | 0.786 cm |
| 0 rad/s | 9.108 cm | 0.791 cm |
| +0.8 rad/s | 3.890 cm | 0.784 cm |

The XRP encoder/runtime path also matches `x=.25 sin(.8t), y=−.25 cos(.8t)` and heading `.8t` after
120 simulated seconds within 1 μm position tolerance. That is deterministic simulated floating-point
precision, not physical sensor accuracy or calibration evidence.

## Performance baseline and explicit budgets

FRC and XRP specify **20 ms / 50 Hz**. FTC documents **50–100 Hz**, with a nominal 20 ms fallback;
both 10 and 20 ms execution thresholds are reported. Actual FTC cadence depends on its OpMode/target.
References: [FRC base](../../ARESLib-Kotlin/frc-hardware/src/main/kotlin/com/areslib/frc/FrcBaseRobot.kt),
[FRC registration](../../ARES-FRC/src/main/kotlin/org/aresfirst/marvin/ARESRobot.kt),
[FTC base](../../ARESLib-Kotlin/ftc-hardware/src/main/kotlin/com/areslib/ftc/FtcBaseRobot.kt),
[XRP scheduler](../../ARES-XRP-Starter/main.py).

Measured on Windows 11, AMD Ryzen 7 8845HS (8 cores/16 threads), Java 17.0.12 and CPython 3.14.2.
The JVM workload includes two active generated mechanisms, Redux/task/superstructure processing,
mecanum conversion and EKF work: 3,000 warmups, 10,000 measured cycles. Mock-clock publication is
outside timing/allocation. XRP includes simulated motor advancement, the generated rangefinder,
odometry, field constraints and motor output: 1,000 warmups, 5,000 measured cycles.
Clocks are monotonic `nanoTime`/`perf_counter_ns`.

| Desktop execution, ms | p50 | p95 | p99 | Worst | Samples over execution budget |
|---|---:|---:|---:|---:|---:|
| FTC generated host | 0.0040 | 0.0079 | 0.0220 | 0.6961 | 0/10,000 over either 10 or 20 ms |
| FRC generated host | 0.0040 | 0.0069 | 0.0136 | 0.2687 | 0/10,000 over 20 ms |
| XRP free motion | 0.0602 | 0.0721 | 0.1144 | 0.2362 | 0/5,000 over 20 ms |

Sensor-to-output p99/worst: **0.0218/0.6956 ms FTC**, **0.0135/0.2678 ms FRC**,
**0.1069/0.2219 ms XRP**. This measures simulated refresh/read to output, excluding physical acquisition,
bus transit, network transport and telemetry serialization.

JVM allocation: **1,063 bytes/cycle FTC**, **1,048 bytes/cycle FRC**, approximately 53/52 KB/s at 50 Hz.
No JVM collection occurred during the unpaced samples. This is not a zero-allocation whole loop;
target heap/GC budgets and resulting pauses remain unverified. A separate 1,000-cycle XRP trace
retained **6,384 bytes**, peaking at **9,480 bytes**; total allocated volume is unavailable.
Tracing was excluded from timing and does not measure the Pico heap.

Paced timing is separate from execution cost. All paced requests used 20 ms periods:

| Desktop pacing | Period p99 / worst, ms | Worst start lateness, ms | Intervals >20 ms | Starts ≥one whole period late |
|---|---:|---:|---:|---:|
| FTC, 200 starts | 31.986 / 32.007 | 16.270 | 59/199 | 0/200 |
| FRC, 200 starts | 32.048 / 32.204 | 15.696 | 58/199 | 0/200 |
| XRP, 100 starts | 20.374 / 20.374 | 0.812 | 65/99 | 0/100 |

The execution samples fit their budgets; this desktop does **not** demonstrate a stable 20 ms
callback cadence. Counts distinguish execution overruns, long inter-start periods and starts late
by an entire period. Windows/JVM scheduling jitter is observed; its precise attribution was not
profiled. No measured production bottleneck warrants another optimization pass. These short runs
are a baseline, not a worst-case guarantee or sustained target-controller performance proof.

Older XRP boundary-constrained timing was p99 1.012 ms/worst 1.953 ms, with no 20 ms execution overrun.
It remains in the pause record as additional desktop evidence, not an optimization comparison.

## Checks preserved in CI and local validation

| Check | Result |
|---|---|
| ARESLib | 3,036 tests / 474 suites, no failures/errors/skips; all 13 APIs and source-size gate pass. |
| FTC + starter | 187 + 17 tests, generated-project verification and debug APK assembly pass. |
| FRC + starter | 306 + 206 tests and generated-project verification pass. |
| XRP + reusable MicroPython | 121 + 130 host tests pass. |
| Changed-part CI | 28 routing/result tests pass; shared runtime/schema/generator and readiness resources select candidate and consumers. |
| Studio | All three test-source compilations pass; normal tests stop at `verifyReleaseVersionAlignment`. |

Gradle reused valid unchanged results. Prior test identities were retained; the new candidate's
410 artifact hashes and both earlier candidates' 820 hashes were verified unchanged. Work remains
local: no external publication, merge, deployment or rendered Studio test occurred.

CI retains the regressions in existing candidate/XRP scopes and uploads readiness JSON/XML.
Shared library changes trigger relevant FTC/FRC, starter and Studio consumers. Only scope assertions
and evidence retention were added. Remote CI was not run because work remains local.

The existing release gate expects `ARES_VERSION: 19.0.1` in the distribution workflow. Source policy
also stops at the missing bundled FTC starter 19.0.0 archive. Guidance and current-document links
pass. The previously unapproved archive/workflow/template migration remains unchanged. These gates
must pass before release; compilation is not substituted for Studio execution or release approval.

## Remaining hardware checkpoint and next action

Run the selected scenarios on controllers actually deployed (FTC Control Hub/Android, FRC roboRIO,
XRP Pico/MicroPython), with reviewed wiring/configuration and real sensors:

1. Verify neutral startup, explicit enable/rearm and disable while commanding drive and mechanisms
   together. Check applied output, not just requested state or dashboard values.
2. Cancel autonomous and interrupt feedback/control delivery. Verify configured freshness/lease
   limits and recovery. Test missing-device startup and cleanup failures with supported fault
   injection; every accessible owned output must neutralize and resources must close.
3. Record actual callback periods, execution distributions, maxima, missed deadlines, target heap/GC,
   and sensor-to-applied-output latency during sustained concurrent load. Use the configured
   20 ms FRC/XRP or 10–20 ms FTC budget; explain overruns before accepting target timing.
4. Verify encoder scale, heading sign, wheel geometry, straight/curved motion and delayed vision
   against measured field references. Calibrate the actual plant; simulated precision is not accuracy.

The evidence supports this physical-controller checkpoint and, separately, protected release-alignment
work if preparing a release. Other unreviewed files, extreme-input hypotheses and cosmetic cleanup
remain deferred. The historical ledger is accounting, not a mandate to resume the every-file goal.
