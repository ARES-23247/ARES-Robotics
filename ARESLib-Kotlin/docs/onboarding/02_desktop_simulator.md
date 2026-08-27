# Onboarding 2: Desktop physics simulator

The `simulator` module runs FTC OpModes against desktop mocks and a Dyn4j physics world. It also supplies deterministic mock time, a virtual driver station, NT4 telemetry, and the local log API.

## Start the simulator

From the ARESLib repository root:

```powershell
# OpMode discovery/server mode
.\gradlew.bat :simulator:run

# A specific season OpMode, headless
.\gradlew.bat :simulator:run -PappArgs="--headless --opmode org.example.MyOpMode"
```

The actual season OpMode must be on the simulator runtime classpath. In normal development, run the simulator task exposed by ARES-FTC so its `TeamCode` sources are included.

Supported launcher arguments include `--opmode <class>`, `--headless`, and `--field-config <file-or-id>`. Live field revisions arrive through the canonical `ARES/Input/fieldConfig` NT4 topic; the retired `--watch` flag never reloaded a document and is no longer accepted. The Gradle `appArgs` property splits arguments on spaces, so avoid paths containing spaces when using that property.

## Local controls

When the virtual driver-station window is active:

| Keys | Action |
|---|---|
| `W` / `S` | Robot X translation |
| `A` / `D` | Robot Y translation |
| `Q` / `E` | CCW / CW rotation |
| Space | Toggle teleop mode |
| `C` | Toggle field-centric mode |
| `R` | Toggle alliance |
| Shift | Toggle intake |
| `F` | Toggle flywheel |
| Enter | Hold transfer/shoot |
| `Y` | Hold pose reset |
| `1`, `2`, `3` | Virtual A, B, X buttons |

A connected gamepad is also supported. Controls are converted to the same simulated gamepad/input state consumed by the OpMode.

## Network services

- NT4 WebSocket: port `5810`
- Local log page/API: port `5002`

ARES Analytics connects as a passive NT4 client and may publish `ARES/Input/*` control topics back to the simulator. Topic names do not use a leading slash. The simulator's alliance defaults to red, and the corresponding NT4 input defaults to `true`.

## Deterministic state

`DesktopSimLauncher` advances `RobotClock` in fixed simulation steps. Timeouts, actions, logs, and controller timestamps must use that clock. The physics world's `currentPose` is ground truth. An OpMode's Redux estimator state is a separate estimate; do not substitute a launcher-local default `RobotState` for either one.

## Quick verification

```powershell
.\gradlew.bat :simulator:test
```

The simulator integration suite exercises telemetry updates, input topics, and vision field-of-view behavior. For an end-to-end season test, also run the season repository's simulator tests.

Next: [Pathing integration](03_pathing_and_analytics.md).
