# Development workflow

## Prerequisites

- Windows, macOS, or Linux with Git.
- Android Studio Ladybug (2024.2) or newer and an installed Android SDK.
- JDK 17 for Android/AGP builds.
- A JDK 21 toolchain for the standalone `simulator` module.
- ADB for Robot Controller deployment.
- The sibling `ARESLib-Kotlin` checkout only when modifying the library itself.

On Windows, the settings script can detect a standard JDK 17 or Android Studio's runtime when Gradle starts under an unsupported JVM. Prefer setting Android Studio's Gradle JVM explicitly rather than relying on the one-time fallback.

## Dependency behavior

Normal builds use immutable Maven Central artifacts constrained by the ARES BOM:

```text
org.aresfirst.ares:{core,codegen,ftc-hardware,simulator,ftc-mocks,simulator-runtime-*}:8.0.0
```

The sibling checkout is not selected automatically. Library developers can compile against exact shared source with `-ParesUseSiblingLib=true`; student and release builds use the pinned binaries.

After changing ARESLib, validate the exact unpublished binary bundle through its isolated repository:

```powershell
$candidate = "8.0.0-rc.<areslib-commit>"
Push-Location ..\ARESLib-Kotlin
.\gradlew.bat apiCheck publishReleaseValidation "-ParesVersion=$candidate"
Pop-Location
$repository = ([Uri](Resolve-Path ..\ARESLib-Kotlin\build\release-repository)).AbsoluteUri
.\gradlew.bat :TeamCode:testDebugUnitTest "-ParesVersion=$candidate" "-ParesRepository=$repository"
```

## Build and test

Run commands from the ARES-FTC repository root.

```powershell
# Fast competition module build
.\gradlew.bat :TeamCode:assembleDebug

# JVM unit tests for the Android debug variant
.\gradlew.bat :TeamCode:testDebugUnitTest

# Build the full Robot Controller application
.\gradlew.bat assembleDebug

# Optional coverage report
.\gradlew.bat :TeamCode:koverHtmlReportDebug
```

Tests use JUnit 4 and Mockito. OpMode classes are excluded from Kover's configured TeamCode report, so test controller/subsystem behavior directly and cover OpMode integration in simulation.

For an ARESLib change used by this repository, a practical validation sequence is:

```powershell
$candidate = "8.0.0-rc.<areslib-commit>"
Push-Location ..\ARESLib-Kotlin
.\gradlew.bat :core:test :ftc-hardware:test :simulator:test
.\gradlew.bat apiCheck publishReleaseValidation "-ParesVersion=$candidate"
Pop-Location

$repository = ([Uri](Resolve-Path ..\ARESLib-Kotlin\build\release-repository)).AbsoluteUri
.\gradlew.bat :TeamCode:testDebugUnitTest :TeamCode:assembleDebug "-ParesVersion=$candidate" "-ParesRepository=$repository"
```

## Desktop simulation

The standalone simulator module compiles the real `TeamCode/src/main/java` sources against ARESLib's FTC mocks. Its Gradle toolchain is JDK 21.

```powershell
# Launch DesktopSimLauncher
.\gradlew.bat :simulator:run

# Pass launcher arguments through the project property
.\gradlew.bat :simulator:run -PappArgs="--headless"

# Exercise calibration/SysId routines
.\gradlew.bat :simulator:runCalibrationVerification
```

The Android `TeamCode` module also defines a headless runner whose classpath puts simulator/mocks before Android FTC classes:

```powershell
.\gradlew.bat :TeamCode:runSim
.\gradlew.bat :TeamCode:runSim -PappArgs="--opmode org.firstinspires.ftc.teamcode.opmodes.ARESMecanumTeleOp"
```

Launcher argument names can evolve in ARESLib; inspect `DesktopSimLauncher` before scripting a new option. The essential invariant is that simulation runs the same season facade and OpMode logic, not a rewritten simulator-only controller.

Simulation limitations:

- it validates state flow, path execution, coordinates, telemetry, and safe lifecycle behavior;
- mocks cannot establish real REV transaction timing, USB/I2C failures, motor current accuracy, radio congestion, or mechanical sign conventions;
- follow simulation with a restrained hardware test whenever a change affects physical IO or safety.

## Routines and autonomous assets

New autonomous and macro authoring uses the repository-root `.ares` project. Analytics saves
trigger-neutral routines under `.ares/routines`, while `.ares/autonomous-catalog.json` supplies
starting poses and Driver Station choices. After an edit, regenerate the Kotlin source compiled
into TeamCode:

```powershell
.\gradlew.bat :TeamCode:generateAresProject
.\gradlew.bat :TeamCode:verifyAresProject
```

The build fails if generated Kotlin is stale. See [Routines, autonomous selection, and
controls](ROUTINES_AND_CONTROLS.md) for the student workflow, action discovery, controller mapping,
and FTC runtime limitations.

`TeamCode/src/main/assets/paths/field.json` is the canonical FTC field contract used by Auto,
TeleOp localization, obstacle preflight, and simulation. `apriltags.fmap` is a checked derivative
for Limelight upload and is guarded by a unit test against the field document. There is no loose
PathPlanner/`.aresauto` deployment path or ADB push task.

## Deploy

Connect the development machine to the Robot Controller network, then:

```powershell
adb connect 192.168.43.1:5555
.\gradlew.bat :TeamCode:installDebug
```

For a manually built APK, find the debug artifact under `TeamCode/build/outputs/apk/debug/` and use `adb install -r <apk>`. Generated autonomous code and field assets are already packaged in the APK.

Before enabling a competition OpMode:

1. Confirm the Robot Controller hardware names against [ARCHITECTURE.md](ARCHITECTURE.md#hardware-configuration).
2. Place the robot on blocks and run `ARES Drivetrain Diagnostic` one motor at a time.
3. Confirm positive wheel/encoder directions and CCW-positive heading.
4. Verify emergency stop and OpMode close zero every mechanism.
5. Verify red and blue field-centric translation.
6. Run autonomous at reduced risk with a clear field and a second person ready to stop it.

## Adding a subsystem safely

Use the capability-oriented generator or follow the same boundaries by hand. The generator previews a
structured change set before it writes anything; it never silently replaces an editable starter.

```powershell
.\gradlew.bat :TeamCode:previewSubsystemChanges
.\gradlew.bat :TeamCode:generateSubsystemStarters
.\gradlew.bat :TeamCode:verifyAresProject
```

Generated plumbing is written below `TeamCode/build/generated/ares/` and is intentionally absent
from version control. Editable starters remain under `TeamCode/src/main/java`; hand-written tests
remain under `TeamCode/src/test/kotlin`. Read [Subsystem generator and hand-authoring guide](SUBSYSTEM_AUTHORING.md)
for artifact ownership, template selection, safety requirements, regeneration, manual templates,
and the complete verification checklist.

## Logs and dashboard connectivity

ARESLib runs the robot-side local services. The common addresses are:

- NT4: Robot Controller address on port `5810`;
- local log server: port `5002`;
- robot web/telemetry server: port `8082` where enabled.

The analytics desktop app connects over the local robot network and pulls logs. Do not place Firebase/GCS/API credentials on the Robot Controller or make robot behavior depend on internet connectivity.
