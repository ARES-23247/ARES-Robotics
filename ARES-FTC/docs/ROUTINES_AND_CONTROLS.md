# FTC routines, autonomous selection, and controls

The FTC robot compiles the same project-authored routines and control schemes used by ARES
Analytics. Authors work on the laptop; neither the Control Hub nor the Driver Station needs to be
online while a routine or mapping is created.

## Source of truth

Canonical inputs live at the repository root:

```text
.ares/project.json
.ares/action-catalog.json
.ares/autonomous-catalog.json
.ares/routines/<id>.aresroutine
```

`project.json` is the shared source of truth for FTC coordinates, field dimensions, and the
robot's footprint. Analytics uses it for placement limits and TeamCode uses the same generated
values for autonomous preflight; neither side guesses dimensions from the current UI profile.

A routine contains behavior and drive goals but no controller button or mandatory starting pose.
The autonomous catalog adds selectable match entry points and starting poses; a control scheme can
invoke the same routine as a teleop macro. This is the supported replacement for creating a path
file and a separate auto file for one behavior.

The action catalog is automatically loaded by Analytics. Each key must have a matching typed
implementation in the FTC generated-project capabilities. At runtime, only actions backed by
hardware discovered on that robot instance are registered; a routine requiring absent hardware is
rejected instead of silently completing a no-op.

## Generate and verify Kotlin

After saving in Analytics, run:

```powershell
# Regenerate disposable project plumbing from canonical documents
.\gradlew.bat :TeamCode:generateAresProject

# Validate canonical documents and protected editable extension points
.\gradlew.bat :TeamCode:verifyAresProject

# Compile the APK and run TeamCode tests
.\gradlew.bat :TeamCode:testDebugUnitTest :TeamCode:assembleDebug
```

The generated project bridge lives below
`TeamCode/build/generated/ares/main/kotlin/`. It is disposable mechanical plumbing and is never
edited or committed. Every Kotlin compile regenerates and validates it from the pinned codegen
artifact, so a canonical-document error fails the build instead of silently deploying older
behavior. Generation runs on the development machine and is not an ADB or network operation.

Commit the canonical `.ares` documents and any explicitly USER-OWNED or reviewed GENERATED STARTER
extension source. Never commit the Gradle `build/generated` tree.

## Autonomous on the Driver Station

`AresAutoBase` builds its choices from the generated autonomous catalog during INIT:

- D-pad left/right selects an enabled entry;
- X toggles Red/Blue unless an OpMode locks the alliance;
- telemetry shows the display name, routine ID, alliance, and READY/BLOCKED state;
- START seeds localization from the alliance-adjusted catalog pose and starts the routine;
- a missing entry, validation error, hardware error, timeout, exception, or stop cancels the task
  tree and neutralizes registered outputs.

FTC autonomous has a 29.5-second default software deadline and never exceeds the 30-second match
limit. Hub command transport and the Limelight proxy are owned by reviewed `.ares/project.json`
runtime options. Generated constants feed the same policy to auto, teleop, diagnostics, and robot
construction; adding a library to the classpath cannot silently enable Photon.

Routines are authored in the repository's canonical field coordinate convention: meters,
CCW-positive radians, `0 = +X`. Alliance transformation happens once at the FTC runtime boundary;
do not add a second mirror in a routine or drivetrain controller.

## Teleop controls

This season project has no generated controller scheme. Competition TeleOps declare their FTC SDK
bindings directly in `controls { ... }`, and reusable autonomous behavior lives in `.ares/routines`.
There is no legacy generated-control runner or loose PathPlanner/`.aresauto` import path.

## Pre-match checklist

1. Open the FTC repository root in Analytics and confirm the action catalog is populated.
2. Save routines/controls and run `:TeamCode:generateAresProject`.
3. Run `:TeamCode:verifyAresProject` and `:TeamCode:testDebugUnitTest`.
4. Exercise the selected auto in the desktop simulator for both alliances.
5. Verify controller mappings on the actual Driver Station/Control Hub path, especially vendor
   extras and analog trigger thresholds.
6. On restrained hardware, confirm INIT selection, starting pose, cancellation, and safe outputs.
