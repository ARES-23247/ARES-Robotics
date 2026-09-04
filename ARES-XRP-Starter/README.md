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
├── deploy/deploy_to_pico.py # Verified mpremote deployment
├── hardware.py            # Official XRPLib adapter boundary
└── main.py                # Small physical-controller entry point
```

## Getting Started

1. Open this project in **ARES Robotics Studio**.
2. Author the stock two-motor differential drivetrain—or a four-motor mecanum drivetrain using
   XRP motor ports 1–4—plus XRP devices, controls, and autonomous routines.
3. Select **Verify & build**, or run `ares.bat verify` (`./ares verify` on macOS/Linux).
4. Launch the simulator from Studio, or run `ares.bat simulate`.
5. Plug in your Raspberry Pi Pico W via USB and deploy:
   ```bash
   ares.bat deploy
   ```
   This verifies the project, installs the exact bundled `ares_micro` runtime, and uploads the generated Python, hardware boundary, extensions, secrets (when configured), and `main.py`.
6. Connect your laptop to the robot's Wi-Fi Access Point and control the robot with ARES Studio.

The `.ares` documents are canonical for GUI-owned behavior. `ares generate` compiles them directly
to deterministic MicroPython and generated safety tests under `build/generated/ares`; those files
are disposable and never edited. This is not Kotlin transpilation and Studio does not attempt to
reverse-engineer arbitrary Python.

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

Desktop results are **Simulation verified** only. Before physical use, verify firmware/XRPLib,
motor direction (including every mecanum wheel), encoder polarity and scale, safe neutral, Wi-Fi behavior, measured brownout
threshold, mechanism limits, and deadman stop on a supported XRP while lifted safely off the floor.
