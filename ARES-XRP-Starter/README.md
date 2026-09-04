# ARES XRP Starter

The official starter project for XRP robots running with ARES Robotics.

## Project Structure

```
ARES-XRP-Starter/
├── .ares/
│   ├── project.json       # Canonical XRP identity, Wi-Fi, link, and brownout policy
│   ├── drivetrains/       # GUI-authored drivetrain
│   ├── subsystems/        # GUI-authored mechanisms and sensors
│   ├── controls/          # Controller bindings and chords
│   └── routines/          # Autonomous routines
├── ares / ares.bat        # Python-native project wrapper (no Gradle required)
├── build/generated/ares/  # Disposable generated Python and safety tests
├── extensions/            # Optional USER-OWNED Python mechanisms
├── tests/                 # Handwritten project/runtime integration tests
├── simulator/             # Desktop XRP simulator
├── device/                 # Pinned official firmware/XRPLib identity manifest
├── deploy/xrp_device.py    # Preflight, verified image download, A/B deploy, rollback
├── hardware.py            # Official XRPLib adapter boundary
└── main.py                # Small physical-controller entry point
```

## Getting Started

1. Open this project in **ARES Robotics Studio**.
2. Author the stock two-motor differential drivetrain—or a four-motor mecanum drivetrain using
   XRP motor ports 1–4—plus XRP devices, controls, and autonomous routines.
3. Select **Verify & build**, or run `ares.bat verify` (`./ares verify` on macOS/Linux).
4. Launch the simulator from Studio, or run `ares.bat simulate`.
5. Prepare the verified official device image for the exact controller model. This downloads both
   the UF2 and XRPLib source archive and rejects either unless its byte length and SHA-256 match:
   ```bash
   ares.bat prepare-image
   ```
   Choose either **SparkFun XRP (RP2350)** or **SparkFun XRP Beta (RP2040)** in Studio's Project
   Identity screen first. ARES downloads the matching pinned official UF2 and refuses to prepare
   or deploy the other board. Flash the resulting UF2 to the matching BOOTSEL volume, then install
   pinned XRPLib `2026.08.2` with the official loader.
6. Plug in the running controller via USB and perform a read-only preflight:
   ```bash
   ares.bat device-preflight
   ```
   Preflight checks the selected board identity, its motor and servo ports, MicroPython `1.28.0`,
   XRPLib `2026.08.2`, and every API required by the generated robot. A mismatch fails closed before
   files are changed.
7. Deploy:
   ```bash
   ares.bat deploy
   ```
   This verifies the project, stages the bundled `ares_micro` runtime, generated Python, hardware
   boundary, extensions, and optional secrets into an inactive content-addressed slot, compiles
   every staged Python file on the controller, and only then atomically activates it. An interrupted
   or invalid upload leaves the previous slot active. Run `ares.bat rollback` over USB to reactivate
   the prior slot.
   To inspect the exact content-addressed deployment without a controller, run
   `ares.bat plan-deploy`; it stages and compiles the same payload but performs no device mutation.
8. Connect your laptop to the robot's Wi-Fi Access Point and control the robot with ARES Studio.

The `.ares` documents are canonical for GUI-owned behavior. `ares generate` compiles them directly
to deterministic MicroPython and generated safety tests under `build/generated/ares`; those files
are disposable and never edited. This is not Kotlin transpilation and Studio does not attempt to
reverse-engineer arbitrary Python.

Robot Builder provides typed XRP templates for mechanism motors on ports 3/4, servos on ports 1–4,
the rangefinder, left/middle/right reflectance channels, the user button, green and RGB LEDs, the
buzzer, full IMU orientation/rate/acceleration, and explicit digital, PWM, and ADC expansion I/O.
The generated verification suite exercises applicable startup/stop, input validity, output-write,
target-limit, action, recovery, control, simulator, project-identity, and autonomous graph contracts.
It emits standard JUnit XML under `build/test-results/test` for Studio's unified Verification view.

For a hybrid project, register a USER-OWNED module under `extensions/` with explicit physical and
simulation factory names plus its action, telemetry, tuning, and safety metadata. After export, the
repository can be built, tested, simulated, and deployed entirely from an IDE or terminal.

## Connection and safety

- The dedicated newline-delimited JSON protocol is `ares-xrp/1` on TCP 5811. It is intentionally
  separate from FTC/FRC NT4 on 5810.
- Every control frame has a session, monotonic sequence, one-shot request revision, explicit arm
  state, and a bounded deadman lease. Invalid, stale, disconnected, or non-finite input commands
  neutral output.
- AP mode uses the generated SSID and a starter password. STATION mode reads `WIFI_PASSWORD` from
  ignored `xrp_secrets.py`; copy `xrp_secrets.example.py` and never commit the secret.
- The physical runtime reads battery voltage from the official XRPLib `board` adapter and latches a
  brownout fault below the configured threshold.

Desktop results are **Simulation verified** only. A successful device preflight is **Configuration
reviewed**, not physical validation. Before physical use, verify firmware/XRPLib,
motor direction (including every mecanum wheel), encoder polarity and scale, safe neutral, Wi-Fi behavior, measured brownout
threshold, mechanism limits, and deadman stop on a supported XRP while lifted safely off the floor.
The exact completed and pending gates are recorded in [docs/PHYSICAL_READINESS.md](docs/PHYSICAL_READINESS.md).
