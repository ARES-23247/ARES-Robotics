# Troubleshooting

Start with the safest test that can distinguish the failure. Keep the robot on blocks for drivetrain diagnosis and be ready to stop the OpMode whenever outputs are enabled.

## Build failures

### Gradle reports an unsupported Java version

Android builds require a JDK 17-compatible Gradle JVM. In Android Studio, select JDK 17 for Gradle. The standalone simulator intentionally requests JDK 21, so ensure a matching toolchain can be discovered.

Check the active JVM:

```powershell
.\gradlew.bat --version
```

### ARESLib classes are unresolved or stale

Confirm `aresVersion` points to an available Maven Central release. For an unpublished sibling change, build its isolated repository and pass it explicitly:

```powershell
$candidate = "8.0.0-rc.<areslib-commit>"
Push-Location ..\ARESLib-Kotlin
.\gradlew.bat apiCheck publishReleaseValidation "-ParesVersion=$candidate"
Pop-Location
$repository = ([Uri](Resolve-Path ..\ARESLib-Kotlin\build\release-repository)).AbsoluteUri
.\gradlew.bat :TeamCode:assembleDebug "-ParesVersion=$candidate" "-ParesRepository=$repository"
```

### Android build works but simulator compilation fails

The simulator uses JDK 21 and compiles TeamCode against FTC mocks. Look for an unmocked Android/FTC API or a dependency available only on the Android classpath. Shared season logic should depend on the IO interfaces, not directly on an Android-only object.

## Robot initialization

### Drive motor is missing

The generated drivetrain descriptor supplies the exact names; the team template uses `fl`, `fr`,
`rl`, and `rr`. The rear names are not `bl`/`br`. Run `ARES Drivetrain Diagnostic` with the robot
securely on blocks. It reports all four generated names and configured directions, blocks all motion
if any motor is missing, and powers one motor at 0.4 only while its displayed button is held. Fix the
canonical Drivebase Builder value and regenerate rather than creating a Robot Controller alias.

### Pinpoint, IMU, or Limelight data is absent

Check `pinpoint`, `imu`, and `limelight` configuration names. Pinpoint is the primary pose input. The IMU fallback supplies heading but cannot recover field X/Y translation, so a robot apparently rotating correctly while pose remains at zero usually indicates unavailable odometry.

For Limelight target-space alignment, robot yaw comes from the negative target-space Y rotation; target-space Z rotation is tilt, not heading. Do not add an extra negation to field heading after the Pinpoint boundary.

### Lightbot lights do not appear

Confirm the Robot Controller names are exactly `indicator`, `indicator2`, and `prism`, then open
Robot Studio → Verification. Generated checks prove safe output, independent actions, mock parity,
and cleanup; the supervised hardware checklist still must confirm wiring and perceived colors. The
simulator renders the indicators as left/right side squares and Prism as underbody lighting using
the placements stored in the subsystem descriptors.

## Driving and localization

### Robot drives diagonally or rotates during translation

Verify each motor individually before changing signs in software. The expected facade directions are forward for `fl`/`rl` and reverse for `fr`/`rr`. A wheel installed or wired differently should be reconciled deliberately; do not compensate by changing coordinate or odometry signs blindly.

### Field-centric direction is wrong only on blue

Both translational axes must be mirrored for blue. The team drive controller already performs that transformation. Confirm the Redux alliance value, then ensure the OpMode uses `driveWithGamepad`/`driveFieldCentric` rather than calling a lower-level field drive with a second mirror.

### Heading is reversed or 90 degrees off

ARES uses CCW-positive radians with zero on +X. Check the Pinpoint heading-direction configuration first. The Pinpoint IO boundary performs the convention conversion; remove any downstream extra negation. A 90-degree error in the desktop field icon may be a field-to-canvas rendering offset rather than robot pose, so compare numeric telemetry before changing robot math.

### Pose jumps at Auto-to-TeleOp transition

Autonomous saves the final pose and alliance through `PoseStorage`; the main TeleOp restores them when `hasValidPose` is true. Confirm autonomous reached its normal persistence path and that the Robot Controller was not restarted between OpModes. A new process cannot retain in-memory pose.

## Autonomous

### Generated auto is BLOCKED/FAILED

Confirm:

- `.ares/autonomous-catalog.json` references an existing `.ares/routines/<id>.aresroutine`;
- disposable `GeneratedAresProject.kt` regenerates successfully from the project (`:TeamCode:verifyAresProject`);
- every named action has hardware-backed capability on this robot;
- starting/target poses and complete drive sweeps clear field boundaries and blocking obstacles.

The autonomous base reports the validation/routine failure, performs a full safety stop, invalidates
pose handoff, and exits without running a partial sequence.

### Auto starts from the wrong side

Select the correct alliance during INIT (or check the locked validation OpMode). The generated
catalog starting pose is mirrored once at the FTC boundary. Also confirm the physical robot is
placed at the displayed starting pose.

### Auto stops after one loop error

This is fail-safe behavior. A loop exception cancels the active routine and stops all registered
season/drivetrain outputs; it does not retry stale targets. Read the reported failure and fix the
underlying missing hardware, invalid sweep, or controller exception.

## Mechanisms and safety

### Intake stops by itself

The intake stall detector requires valid current above 8 A continuously for more than 250 ms, then dispatches `intakeActive = false`. Check for a physical jam and drivetrain voltage before adjusting thresholds. An invalid current sample clears rather than latches stale overcurrent data.

### Flywheel target does not decrease during brownout scaling

That is intentional. Shot velocity depends on target RPM, so ordinary scaling does not alter the velocity setpoint. A zero power scale is reserved as the emergency-stop signal and commands zero RPM. Diagnose bus voltage and load rather than interpreting the unchanged target as an ignored safety state.

### A mechanism continues after stop or crash

Treat this as a critical lifecycle defect. Confirm its IO:

1. has an explicit neutral implementation in `safe()` and `close()`;
2. registers with `HardwareRegistry` during initialization;
3. is wrapped by a subsystem registered with `base.registerSubsystem`;
4. treats `writeOutputs(..., scale = 0.0)` as a stop;
5. does not write outputs asynchronously after close.

Do not resume operation until the restrained-hardware stop test passes.

## Networking and logs

### Dashboard shows duplicate or missing topics

Use NT4 keys without a leading slash, for example `ARES/Input/driveFrame`. Publishing a leading-slash duplicate from custom code creates an inconsistent contract even though current server/client boundaries normalize canonical keys.

### Remote drive stops despite an apparent connection

Remote drive accepts only exact v2 `ARES/Input/driveFrame` arrays. Verify the publisher uses a
positive session nonce, increasing sequence, nondecreasing client monotonic time, integral known
flags, and publishes a neutral-first frame. A 200 ms receiver-side lease intentionally commands
zero velocity even if NT4 still retains the last moving frame. Flag bit 4 selects field-relative
(`1`) or robot-relative (`0`) axes consistently with the simulator.

### Logs are not in the cloud

The robot never uploads directly. Connect the ARES Analytics desktop application to the robot's local log service on port `5002`, import the files to the laptop, then sync from the laptop. Internet availability should not affect robot operation or local logging.

## Diagnostic OpModes

- `AAA Blank Null OpMode`: hardware-free control-system isolation; useful for distinguishing app/lifecycle issues from I2C or power trouble.
- `ARES Drivetrain Diagnostic`: uses generated names/directions and individually powers one drive
  motor while held; it must be used on secure blocks with a person ready to press Stop.
- `ARES Live Tuning TeleOp`: dedicated calibration surface; it still requires a fresh enable token while command is `STOP`.
- `ARES Remote Drive (NT4)`: tests the atomic v2 frame and 200 ms receiver lease.

When a problem remains ambiguous, capture the exact OpMode, hardware-map names, Driver Station error, local log, last valid sensor values, and whether the same behavior occurs in simulation.
