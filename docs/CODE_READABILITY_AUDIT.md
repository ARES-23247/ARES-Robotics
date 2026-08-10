# Code Readability and KDoc Audit

Audit date: 2026-08-10

## Scope and accounting

This audit covers every first-party Kotlin or Java source and test file in the five published/runtime modules. Files were enumerated from each module's `src/main` and `src/test` trees, classified by subsystem, scanned for generated/placeholder comments and misleading contract language, and reviewed more closely at public and cross-thread boundaries. No Gradle task was run during this pass because other agents were editing the shared checkout.

| Module | Main | Test | Total |
|---|---:|---:|---:|
| `core` | 178 | 101 | 279 |
| `ftc-hardware` | 55 | 13 | 68 |
| `frc-hardware` | 12 | 4 | 16 |
| `simulator` | 25 | 3 | 28 |
| `ftc-mocks` | 41 | 0 | 41 |
| **Total** | **311** | **121** | **432** |

All 432 files are Kotlin (`.kt`); no first-party Java (`.java`) files occur in these source sets. This pass changed 180 source/test files and reviewed 252 without changing them. The audit document itself is additional to those source/test counts.

Excluded from the inventory:

- `build/`, `.gradle/`, IDE metadata, compiled output, generated reports, and dependency caches;
- Markdown, JSON, Gradle scripts, assets, and other non-Kotlin/Java files;
- the other three repositories in the ARES workspace;
- files outside the five named modules, including examples or tooling that are not in their `src/main` or `src/test` trees.

## Contract-focused changes (45 files)

These files received substantive documentation corrections or small, behavior-preserving readability changes.

| Area | Exact files | Rationale |
|---|---|---|
| Core state and time | `core/.../Store.kt`, `util/RobotClock.kt` | Document synchronized reduction versus callback execution, snapshot visibility, dispatch ordering, the process-global mock clock, and monotonic versus wall-clock semantics. |
| Core hardware | `hardware/SyncPolledDevice.kt`, `HardwareRegistry.kt`, `hardware/vision/VisionIO.kt`, `CompositeVisionIO.kt` | Define polling-thread ownership, cache requirements, registry lifetime, snapshot ownership, units, ordering, close behavior, and exception expectations. |
| Core NT4 and telemetry | `networktables/NT4Entry.kt`, `NT4Instance.kt`, `telemetry/ITelemetry.kt` | Replace overbroad thread-safety claims; document listener threads, process-wide server replacement, canonical keys, and array-copy ownership. The redundant compatibility facade was removed. |
| Core logging and HTTP | `logging/ARESDataLogger.kt`, `LogManagerServer.kt`, `DataLoggingTelemetry.kt`, `telemetry/web/LogArchivePackager.kt`, `PortForwarder.kt` | Describe bounded queues, dropped data, stable CSV schemas, map recycling, shutdown/drain behavior, LAN trust boundary, path preconditions, throttling, and socket/thread ownership. |
| Core sequencer | `sequencer/Task.kt`, `TaskExecutor.kt`, `TaskStateMachine.kt`, `TaskTimeoutManager.kt` | Define lifecycle order, action ownership, synchronization, preemption, transition limits, global registry retention, timeout clock, and completion fallback behavior. |
| FTC drive, cache, power, and profiling | `ftc-hardware/.../hardware/CachedHardware.kt`, `drivetrain/MecanumHardwareIO.kt`, `power/FtcPowerManager.kt`, `hardware/OctoquadIO.kt`, `telemetry/FtcLoopProfiler.kt` | Correct motor-name text; document normalized input scaling, one-read caching, voltage fallback, software odometry-frame seeding, sample timestamps, and overrun thresholds. `MecanumHardwareIO` also removes an avoidable `Pair` allocation from its hot path. |
| FTC vision and Photon | `ftc-hardware/.../vision/FtcVisionPortalIO.kt`, `photon/AresPhotonCore.kt`, `AresPhotonLynxModule.kt`, `AresPhotonReflectionUtils.kt` | Correct inaccurate asynchronous-write and safe-copy claims; document frames/units, measurement-pool ownership, synchronous global serialization, lock interception, ACK behavior, reflection timing, and shallow copies. |
| FRC boundaries | `frc-hardware/.../drivetrain/FRCSwerveHardwareIO.kt`, `power/FrcPowerManager.kt`, `telemetry/FrcTelemetryManager.kt` | Clarify estimator seeding units, cached PDP reads, actual CSV output, CAN topic names, utilization units, and ignored compatibility parameters. |
| Simulator | `simulator/.../physics/SimPhysicsWorld.kt`, `network/TelemetryPublisher.kt`, `opmode/SimOpModeRunner.kt`, `infra/VirtualDriverStation.kt`, `hardware/vision/SimVisionIO.kt` | Define coordinate origin/units, mutation-thread ownership, field-load fallback, canonical NT4 topics, ground-truth pose source, reflection lifecycle, Swing/gamepad behavior, and persistent synthetic vision data. |
| FTC mocks | `ftc-mocks/.../util/ElapsedTime.kt`, `hardware/gobilda/GoBildaPinpointDriver.kt`, `external/Telemetry.kt`, `hardware/HardwareDeviceMocks.kt`, `com/areslib/ftc/MockIndicatorLightIO.kt`, `configuration/annotations/Annotations.kt`, `com/qualcomm/robotcore/WebHandlerRegistrar.kt`, `org/firstinspires/ftc/robotcore/external/ExternalMocks.kt` | State exactly which SDK subset is modeled, which operations are snapshots/no-ops/unsupported, what units are retained, and where desktop behavior intentionally differs from hardware. |

## Mechanical placeholder cleanup (135 files)

An exact six-line generated KDoc pattern of the form “`X declaration` / `Standard args` / `corresponding output`” was removed where it added no information. This removed 502 boilerplate blocks: 191 blocks from 56 production files and 311 blocks from 79 test files. Declarations, annotations, and executable code were not changed. Files that were already modified by another agent were excluded from this mechanical pass.

<details>
<summary>Production files (56)</summary>

- `core`: `MotorIO.kt`, `ServoIO.kt`, `SwerveHardwareIO.kt`, `ImuIO.kt`, `ThreadedColorSensor.kt`, `ThreadedDistanceSensor.kt`, `ThreadedMultizoneDistanceSensor.kt`, `TopologyModels.kt`, `VisionHardware.kt`, `DynamicPathLoader.kt`, `PathPlannerJsonParser.kt`, `GridCostmapInflator.kt`, `ThetaStarSearchState.kt`, `PathfindToPoseTask.kt`, `TaskGroupDispatcher.kt`, `BlinkIndicatorTask.kt`, `SetIndicatorColorTask.kt`, `RobotFieldConfig.kt`, `DriveSubsystem.kt`, `GamepadState.kt`, `LocalTelemetry.kt`.
- `ftc-mocks`: `Canvas.kt`, `FtcDashboard.kt`, `TelemetryPacket.kt`, `BNO055IMU.kt`, `LynxCommandMocks.kt`, `LynxMocks.kt`, `RevHubOrientationOnRobot.kt`, `Base64.kt`, `FtcOpModeMocks.kt`, `OpModeManagerMocks.kt`, `OpModeMocks.kt`, `LynxConstantsMocks.kt`, `Gamepad.kt`, `I2cDeviceSynchSimple.kt`, `I2cMocks.kt`, `MotorMocks.kt`, `SensorMocks.kt`, `UsbMocks.kt`, `JSONObject.kt`, `SerialNumberMocks.kt`, `FtcNavigationMocks.kt`.
- `simulator`: `SimCliParser.kt`, `DesktopSimLauncher.kt`, `FieldElementLoader.kt`, `MecanumInteractionModel.kt`, `FakeControllerClient.kt`, `MockI2cDeviceSynch.kt`, `SimGamepadManager.kt`, `SimNetworkPublisher.kt`, `SimOpModeController.kt`, `MecanumRobotDouble.kt`, `NT4FieldPublisher.kt`, `RobotStateStruct.kt`, `TestCrash.kt`, `VerificationApp.kt`.

</details>

<details>
<summary>Test files (79)</summary>

- `core`: reducer, math, control, estimation, pathing, sequencer, telemetry, logging, hardware,
  networking, and tuning tests under `core/src/test`. Robot-side cloud exporter/replay tests were
  removed with those obsolete APIs; desktop Analytics owns cloud sync and replay.
- `ftc-hardware`: `AppFailsafeTier1Test.kt`, `HardwareFaultToleranceTier1Test.kt`, `HardwareBoundsTier2Test.kt`, `CompositeVisionIOTest.kt`, `FtcMecanumRobotBuilderTest.kt`, `MecanumHardwareIOTest.kt`, `FtcPowerManagerTest.kt`.
- `frc-hardware`: `FrcPowerManagerTest.kt`.

</details>

## Reviewed without changes (252 files)

The remaining 252 files were retained after inventory and scan. Their comments were either useful and consistent with the implementation, too trivial to warrant KDoc, or under concurrent behavioral edits where changing adjacent text would create unnecessary merge risk. This number closes the inventory exactly: 180 changed plus 252 unchanged equals 432 reviewed source/test files.

## Behavioral findings and remediation

The documentation review originally separated the following runtime concerns from its mechanical edits. A subsequent implementation pass addressed them rather than preserving compatibility shims:

- NT4 values now snapshot mutable arrays at ownership boundaries and return owned array copies.
- Normalized drive-facade methods are named explicitly; Redux drive state and hardware boundaries use physical SI units.
- OctoQuad and SRS Pinpoint adapters seed an explicit software frame transform and expose the timestamp of the last successful device sample. They no longer manufacture fresh timestamps for stale/default data.
- `LogArchivePackager` validates names at its own file boundary and rejects traversal components.
- Alliance mirroring receives an explicit field-origin contract instead of guessing from field length.
- Sequencer registries use weak identity keys, terminal paths release task state, and failures remain `FAILED` after interruption cleanup.
- `HardwareRegistry` isolates polling exceptions per device so one failed peripheral cannot stop every cache refresh.
- FTC vision measurements are copied before immutable Redux ownership; pooled transport objects remain internal.
- Photon remains an opt-in optimization, is enabled deliberately by FTC TeleOp and Auto bases, and no longer fabricates ACKs or clears unfinished commands on an arbitrary timer.

## Validation

- `:core:test`, `:ftc-hardware:test`, and `:simulator:test` pass after the remediation pass.
- `publishToMavenLocal` succeeds, and the FTC, FRC, and Analytics consumer suites pass against the resulting APIs.
- `git diff --check` reports no whitespace errors; Git emits only line-ending conversion warnings.
- All 432 files decode as strict UTF-8. A raw block-comment delimiter scan found only three apparent mismatches, each explained by a literal wildcard topic or route containing `/*`; their actual KDoc blocks are closed.
