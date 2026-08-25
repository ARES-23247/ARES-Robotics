# Development, testing, and troubleshooting

## Before changing code

1. Identify the owning module and all three consumer repositories.
2. Read [Math and coordinate contracts](math-and-coordinate-contracts.md) for geometry, estimator, vision, or path changes.
3. Read [Telemetry and logging](telemetry-and-logging.md) for any topic, networking, replay, or file-format change.
4. Inspect the working tree and preserve unrelated changes.

## Runtime rules

### Deterministic time

Use `com.areslib.util.RobotClock` throughout library runtime code:

```kotlin
val timestampMs = RobotClock.currentTimeMillis()
val startNanos = RobotClock.nanoTime()
```

Tests and simulation switch the clock to mock time with `useMockTime`/`setMockTimeMs`, then restore system time with `useSystemTime`. Direct calls to `System.currentTimeMillis()` or `System.nanoTime()` make simulation, replay, timeouts, and logs disagree.

### Allocation-aware hot paths

Treat robot loops, hardware refresh, estimator updates/replay, trajectory sampling, and local-planner steering as hot paths. Within them:

- Reuse primitive buffers and mutable request objects where ownership is obvious.
- Use index loops where collection iteration allocates on the target runtime.
- Do not construct geometry objects merely to access scalars; prefer direct/primitive overloads.
- Do not use reflection or create coroutines/jobs per frame.
- Move JSON, file I/O, topology serialization, and path parsing outside the loop.
- Measure allocation and loop-time changes on the target controller; desktop tests alone cannot
  predict Android ART or RoboRIO pause behavior.

The goal is bounded loop latency, not universal zero allocation. Small, readable allocations are
acceptable outside timing-critical sensor, estimator, and actuator paths—and inside them when
measurement shows they do not create harmful pause or throughput behavior. Do not introduce object
pools or mutable aliasing solely to satisfy an unmeasured zero-GC claim.

### Cached hardware reads

Read each motor, encoder, voltage sensor, IMU, analog input, and servo position once during the loop's refresh phase. Store the result in a preallocated input/cache object. Controllers, getters, telemetry, and `writeOutputs()` consume cached values only. This keeps samples coherent and prevents hidden I2C/CAN work in unrelated code.

### Safe failure behavior

Controllers and math utilities should reject non-finite input and unsafe time deltas. Robot-facing failures should default to stopped/limited outputs, stale sensors should fail closed, and latched faults should require an intentional recovery condition. Exceptions leaving an autonomous or mode lifecycle must stop all mechanisms.

## Test strategy

Use the smallest focused suite while iterating, then run the affected modules:

```powershell
# A test class
.\gradlew.bat :core:test --tests "com.areslib.math.estimation.PoseEstimatorTest"

# Core and platform adapters
.\gradlew.bat :core:test :ftc-hardware:test :frc-hardware:test

# Simulator integration
.\gradlew.bat :simulator:test

# Everything in this repository
.\gradlew.bat test
```

The suite includes unit and regression coverage for reducers, controllers, pathing, estimation, telemetry, logging, hardware adapters, simulator integration, fault injection, and steady-state allocation. Tests in `core/src/test/kotlin/com/areslib/e2e` cover selected requirement-level scenarios; their directory name is not a substitute for running platform/simulator tests.

After a cross-repository library change:

```powershell
.\gradlew.bat apiCheck publishReleaseValidation "-ParesVersion=8.0.0-rc.<commit>"
```

Then build and test each affected sibling with `-ParesRepository=<ARESLib-Kotlin>/build/release-repository`. Normal builds resolve the pinned release from the ARES GitHub Maven repository, with Maven Central as an optional secondary channel; `-ParesUseSiblingLib=true` is the explicit source-substitution escape hatch.

## Common failures

### `Unsupported class file` or wrong Java version

Run `java -version` and `./gradlew --version`; the Gradle JVM should be JDK 17. IDE settings and `JAVA_HOME` can point at different installations.

### FRC tests fail while loading JNI

`frc-hardware` extracts WPILib/vendor desktop natives for tests and uses the WPILib JDK at `C:/Users/Public/wpilib/2026/jdk/bin/java.exe` when installed. Verify the WPILib installation and the matching desktop native dependencies. Do not copy RoboRIO natives into the desktop test path.

### Consumer compiles against stale ARESLib

1. Confirm the consumer's `aresVersion` is the intended release.
2. For unpublished changes, run `publishReleaseValidation` with a unique prerelease `-ParesVersion`, then pass both that exact version and its repository to the consumer.
3. Confirm `-ParesUseSiblingLib=true` was not supplied accidentally.
4. Use Gradle dependency insight if two versions are present.

### Simulator starts but no OpMode runs

With no `--opmode`, `DesktopSimLauncher` runs OpMode discovery/server mode. Run from the season simulator module or add the season code to the runtime classpath, then pass the fully qualified class with `--opmode`.

### Heading is reversed

Test a known positive CCW turn at the hardware boundary. For FTC Pinpoint, set the mounting polarity flag correctly and remove downstream negations. For target alignment, verify Limelight yaw uses negative rotation about target-space Y.

### EKF rejects every vision sample

Inspect `lastRejectionReason`, timestamps, tag count, ambiguity, standard deviations, and field frame. A receipt-time timestamp or degrees supplied where radians are expected can make otherwise plausible observations fail gating.

### Tests leave ports occupied

NT4 uses `5810`, the log manager uses `5002`, and the robot web server defaults to `8082`. Stop the prior process cleanly before rerunning network tests. Avoid running multiple port-owning integration suites in parallel.

## Documentation hygiene

When behavior changes, update the closest document rather than adding an isolated note:

- Module/API ownership: `architecture.md`
- Frames, units, EKF, or vision: `math-and-coordinate-contracts.md`
- Topics, ports, logs, or offline sync: `telemetry-and-logging.md`
- Commands, tests, or troubleshooting: this file and `README.md`

Use repository-relative Markdown links so documentation works in local checkouts and source hosting.
