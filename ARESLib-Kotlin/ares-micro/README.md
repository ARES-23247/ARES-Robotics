# ares-micro

ARES Robotics on-device MicroPython runtime for the Raspberry Pi Pico W and XRP robotics platform.

## Features

- **Kinematics**: Pure-Python forward and inverse kinematics for 2-wheel Differential and 4-wheel Mecanum drivebases.
- **SparkFun OTOS Driver**: Register-level I2C optical tracking odometry with automatic robot center offsets.
- **Autonomous Path Following**: Closed-loop PID waypoint follower (`PidPoseFollower`) and sequential autonomous routines.
- **ARES Studio Driver Station Tether**: Non-blocking TCP/WebSocket streaming of atomic `ARES/SimulatorPoseFrame` and reception of leased `ARES/Input/driveFrame` controls.
- **Zero-GC Conscious**: Pre-allocated structures and minimal heap allocations inside 50 Hz periodic execution loops.

## Installation on Raspberry Pi Pico W

Copy the `ares_micro` directory to `/lib/ares_micro` on your Pico W filesystem via `mpremote`:

```bash
mpremote mip install ares-micro
# OR manually copy files:
mpremote cp -r ares_micro :lib/ares_micro
```
