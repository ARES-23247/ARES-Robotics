# Onboarding 4: Pit operations and hardware

This checklist covers library-level behavior. Exact buttons and dashboard screens belong to the platform product and ARES Robotics Studio documentation.

## Before enabling

- Confirm the expected hardware registry/topology matches connected devices.
- Rotate the robot CCW and verify odometry/EKF heading increases.
- Push the robot forward and left and verify positive local X/Y motion.
- Check battery voltage is finite and plausible; verify brownout/current limiting is not already faulted.
- Confirm vision capture timestamps, tag IDs, and ambiguity are updating.
- Confirm every mechanism has a tested stop state and stale sensors fail closed.
- Open `http://<robot-ip>:5002/api/logs` from the laptop to verify local log access.

## Swerve offsets

`SwerveOffsetManager` resolves offsets in this order:

1. Runtime `swerve_offsets_runtime.json`.
2. Deployed `deploy/swerve_offsets.json` or `assets/swerve_offsets.json`.
3. Newest `backups/swerve_offsets_*.json`.
4. Code defaults.

Saving a calibration writes the runtime file, makes a timestamped backup, prunes old backups, and optionally publishes `ARES/Swerve/Offsets*` telemetry. After calibration, disable/re-enable and verify the runtime file is the source actually loaded. Keep a known-good deployed baseline in the season repository.

## Pinpoint verification

Pinpoint heading polarity is configured at `PinpointIO`. Set `isHeadingCcwPositive` for the physical mounting so a CCW turn produces increasing heading. Once it is correct there, all downstream consumers remain CCW-positive. Do not compensate in the estimator, path follower, or dashboard.

## Hardware cache discipline

Each loop refreshes each device once and stores the sample in cached fields/input objects. During pit diagnosis, avoid adding telemetry getters that read CAN/I2C devices directly; doing so changes bus timing and can hide the original problem. Log the cached value and its freshness timestamp instead.

## Logs after a run

1. Stop the OpMode cleanly so the logger drains and closes its file.
2. Check `droppedFrameCount` if data is missing.
3. Pull logs through ARES Robotics Studio or `GET /api/download?file=<name>` on port `5002`.
4. Do not add robot-side cloud upload as a workaround for laptop import problems.
5. Delete robot logs only after the pulled copy has been verified.

## Focused hardware tests

```powershell
.\gradlew.bat :ftc-hardware:test
.\gradlew.bat :frc-hardware:test
```

Desktop mocks validate state transitions and fault handling, but they do not prove wiring, CAN IDs, encoder direction, camera mounting, or current limits. Complete those checks on the disabled robot before mechanism testing.
