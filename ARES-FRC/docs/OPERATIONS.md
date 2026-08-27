# Build, test, deploy, and troubleshoot

## Prerequisites

- Windows PowerShell for the documented wrapper commands.
- WPILib 2026.2.1, including its Java 17 runtime and desktop native libraries.
- The sibling `../ARESLib-Kotlin` checkout. `settings.gradle` includes it as a composite build.
- Vendor dependencies installed/resolvable for CTRE Phoenix 6 and WPILib.
- An `scp` client and RoboRIO network access when running `fetchOffsets`.

The project uses Kotlin 1.9.23 and targets Java 17. Do not run robot builds with an arbitrary newer JVM when diagnosing native test or simulation issues; prefer the WPILib-provided Java runtime.

## Common commands

Run from the ARES-FRC repository root:

```powershell
# Compile and execute all JUnit 5 tests
.\gradlew.bat test

# Build robot artifacts
.\gradlew.bat build

# Launch WPILib desktop simulation
.\gradlew.bat simulateJava

# Deploy code and src/main/deploy contents to team 23247
.\gradlew.bat deploy -PteamNumber=23247

# Fetch the current runtime swerve calibration from the RoboRIO
.\gradlew.bat fetchOffsets
```

The Gradle deployment default is team `23247`; `-PteamNumber` is available only for an intentional alternate target.

Tests use JUnit 5 and configure WPILib desktop JNI extraction. On Windows the build prefers `C:/Users/Public/wpilib/2026/jdk/bin/java.exe` when it exists.

Normal operations use the pinned ARESLib release from Maven Central. To test an unpublished shared change through its exact binary bundle:

```powershell
$candidate = "8.0.0-rc.<areslib-commit>"
cd ..\ARESLib-Kotlin
.\gradlew.bat apiCheck publishReleaseValidation "-ParesVersion=$candidate"
cd ..\ARES-FRC
$repository = ([Uri](Resolve-Path ..\ARESLib-Kotlin\build\release-repository)).AbsoluteUri
.\gradlew.bat test "-ParesVersion=$candidate" "-ParesRepository=$repository"
```

## Deployment checklist

1. Run `test` and a desktop simulation of the intended native auto.
2. Confirm `SmartDashboard/SelectedAuto` names an enabled entry compiled from `.ares/`.
3. Run `generateAresProject` and `verifyAresProject`; confirm every action appears in
   `.ares/action-catalog.json` with correct resource ownership.
4. Verify `src/main/deploy/swerve_offsets.json` matches the robot.
5. Physically place cowl, intake pivot, and climber at their safe zero stops. While Disabled or
   Test-enabled, have both operators hold Back+Start together once and verify
   `Safety/MechanismsHomed=true` before using any mechanism.
6. Confirm both Limelights are reachable as `limelight-shooter` and `limelight-back`.
7. Confirm the GradleRIO target is team 23247, then deploy.
8. While disabled, confirm alliance, pose, mechanism validity telemetry, zero/safe outputs, and a
   populated `Topology/HardwareMap` containing the expected CAN2 IDs.
9. Enable mechanisms individually before running a full autonomous routine.

GradleRIO copies `src/main/deploy` to `/home/lvuser/deploy`; autonomous documents are not among
those files because the verified generated Kotlin is the only robot runtime source.

## Swerve offsets

The checked-in baseline is `src/main/deploy/swerve_offsets.json`, with keys:

- `frontLeft`
- `frontRight`
- `backLeft`
- `backRight`

The runtime robot may write `/home/lvuser/swerve_offsets_runtime.json`. `fetchOffsets` downloads it
to a same-directory temporary file, requires exactly four finite values in `[-1, 1]`, and atomically
replaces the checked-in baseline. Network, parse, schema, or move failure fails the task and leaves
the baseline intact.

Before the first fetch, connect once with `ssh lvuser@10.232.47.2 true` and verify the RoboRIO
fingerprint. The task requires a trusted `known_hosts` entry and fails on unknown or changed host
keys.

Treat `fetchOffsets` as a calibration update:

1. Put the robot in a mechanically known calibration state.
2. Generate/verify runtime offsets on the correct RoboRIO.
3. Run `fetchOffsets`.
4. Review the JSON diff for all four modules.
5. Rebuild and re-test steering orientation before deployment.

Do not fetch offsets from an unknown robot or network target and immediately deploy them.

## Troubleshooting

### Autonomous immediately stops

Check `ARES/Auto/Error` and `SmartDashboard/SelectedAuto` first. Verify the selected entry exists in
the generated catalog, every action is registered, and no robot footprint crosses the field
boundary. If the reason reports mechanism safety inhibition, restore verified configuration and
safe-zero homing before retrying; autonomous will not arm around that latch. `do-nothing` is a valid
safe default and preserves the current localized pose.

### An auto exists in Analytics but is missing on the RoboRIO

Save it into the selected FRC project's `.ares/` directory, run `generateAresProject` and
`verifyAresProject`, then deploy the rebuilt robot program. Loose deploy documents are ignored.

### `shooter.feedWhenReady` waits and then continues without a shot

The wait is intentionally capped at 2 s. Inspect flywheel target RPM, all four motor observations,
measured cowl position, and both validity flags. Readiness requires a target above 100 RPM, less
than 150 RPM error, and cowl error no greater than 0.05 rotations. Once authorized, transfer lasts
450 ms and always clears feeder/floor/latch outputs on completion or cancellation.

### Feeder/game-piece state never changes

Marvin XIX has no configured physical beam break, so `pieceDetectionValid` is false by design. The default desktop simulation mirrors this. Do not force validity true just to make inventory move; enable a real detector implementation or explicitly configure one in a test/simulation.

### Cowl moves far beyond the intended angle

The cowl API and shot table use mechanism **rotations**, not degrees. `0.50` means half a rotation. Valid commands are clamped to `0.0..1.80` rotations. Audit any dashboard or autonomous value that labels the field as degrees.

### Climber position is wrong or hits a soft limit

Position commands are mechanism rotations with an 80:1 sensor-to-mechanism ratio and hardware limits
of `0.0..1.73` rotations. A process restart invalidates the relative zero; repeat the Disabled/Test
safe-zero contract before enabling. A Talon reset while the process is running invalidates both its
configuration and relative zero; restart robot code so all CTRE settings are reapplied, then repeat
safe-zero. Do not compensate by bypassing the homing guard or soft limit.

### Robot drives 180 degrees from the expected field direction

Confirm the alliance value and coordinate convention. The pose frame is always blue-origin and CCW-positive. Teleop currently negates both translation axes on Red to preserve driver-forward perspective. Autonomous mirrors the path separately. Do not add an extra translation or heading inversion in swerve IO.

### Mechanism values freeze or jump to zero

Check the corresponding CTRE refresh status/validity before tuning gains. Hardware observations are cached once per loop. The flywheel reducer deliberately exposes zero RPM after an invalid refresh so stale data cannot authorize shooting.

### Any periodic exception or outputs unexpectedly zero

Driver Station should contain the originating exception. The robot intentionally calls `safeHardware()` on periodic failures, so zero outputs are evidence of the fail-safe path rather than a second fault. Fix the original exception before bypassing any safety call.

### Desktop tests fail to load WPILib/CTRE native libraries

Verify the WPILib 2026 installation and its Java 17 runtime at `C:/Users/Public/wpilib/2026/jdk/bin/java.exe`. Then rerun with the Gradle wrapper so the configured desktop JNI extraction is applied. Avoid launching test classes directly from an IDE until its native-library configuration matches Gradle.

### Changes in ARESLib are not visible elsewhere

Confirm the consumer's `aresVersion` is the intended Maven Central release. For an unpublished library change, publish a unique prerelease coordinate such as `8.0.0-rc.<areslib-commit>` and pass both that exact `-ParesVersion` and the isolated `build/release-repository` URI. Never republish a released coordinate with different bytes. Do not copy shared classes into the season repository.
