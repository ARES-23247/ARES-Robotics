# ares-micro

ARES Robotics on-device MicroPython runtime for the Raspberry Pi Pico W and XRP robotics platform.

## Features

- **Kinematics**: Pure-Python forward and inverse kinematics for 2-wheel Differential and 4-wheel Mecanum drivebases.
- **SparkFun OTOS Driver**: Register-level I2C optical tracking odometry with automatic robot center offsets.
- **Autonomous Path Following**: Closed-loop PID waypoint follower (`PidPoseFollower`) and sequential autonomous routines.
- **ARES Studio Driver Station Tether**: Dedicated non-blocking `ares-xrp/1` newline-delimited JSON over TCP 5811 with explicit sessions, monotonic sequences, request revisions, arming, and a deadman lease. XRP does not impersonate FTC/FRC NT4.
- **Zero-GC Conscious**: Pre-allocated structures and minimal heap allocations inside 50 Hz periodic execution loops.

## Installation on Raspberry Pi Pico W

Copy the `ares_micro` directory to `/lib/ares_micro` on your Pico W filesystem via `mpremote`:

```bash
mpremote cp -r ares_micro :lib/ares_micro
```

Normal users do not install this module independently. The official standalone XRP starter bundles
the pinned runtime and its `ares deploy` command verifies then copies the exact project/runtime
files to the board.
