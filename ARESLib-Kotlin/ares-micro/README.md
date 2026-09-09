# ares-micro

ARES Robotics on-device MicroPython runtime for the Raspberry Pi Pico W and XRP robotics platform.

## Features

- **Kinematics**: Pure-Python forward and inverse kinematics for 2-wheel Differential and 4-wheel Mecanum drivebases.
- **SparkFun OTOS Driver**: Register-level I2C optical tracking odometry with explicit configurable sensor offsets.
- **Autonomous Path Following**: Proportional waypoint follower (`PidPoseFollower`) and sequential autonomous routines. Differential-style forward/reverse and turning also works on mecanum; this follower does not command lateral velocity.
- **ARES Studio Driver Station Tether**: Dedicated non-blocking `ares-xrp/1` newline-delimited JSON over TCP 5811 with explicit sessions, monotonic sequences, request revisions, arming, and a deadman lease. XRP does not impersonate FTC/FRC NT4.
- **Loop work**: Sensor inputs are read during the control cycle. Disconnected robots skip telemetry state snapshots, and unconstrained robots skip simulator pose tuples. JSON messages and controller return values still allocate; this is not a zero-allocation loop.

## Control and lifecycle

Pass the measured, finite positive period in seconds to `XrpRobot.step(dt)`. Invalid
timing, invalid odometry, and runtime exceptions latch a fault and attempt neutral output
for every mechanism. Brownout thresholds must be finite and in 3.0..6.0 V.
`shutdown()` is terminal for that robot instance and releases owned sockets even when
one cleanup operation fails. Construct a new instance to run again.

Waypoint speed is a positive ceiling in meters per second. Completion requires both
position tolerance (default 0.04 m) and heading tolerance (default 0.05 rad, configurable
as `heading_tolerance_rad`). Translation slows while turning toward the target bearing.
Autonomous completion keeps the drive neutral and retains mechanism control under the
existing autonomous lease. Completion does not switch modes; an explicit Teleop request
is required to enter teleop. Stop or lease expiry neutralizes every mechanism. Completed
routines are not repeatedly updated. WAIT/ACTION sequences advance at most one step per cycle.

The link bounds pending input/output to 16,384 bytes each and reads at most 512 bytes
per poll. Large field payloads exceeding this budget disconnect. A stale, invalid, or
disarmed control request requires a newer Start revision before motion can resume;
an ordinary heartbeat cannot renew a lease after its deadline has passed. Deadman
timeouts are integer milliseconds in 100..1000, matching generated project validation.

## Mechanism control

Position/velocity PID resets its integral and derivative history on stop, wraps continuous
angular error and derivative deltas, filters derivatives with the declared time constant,
and limits integral accumulation during saturation. Bang-bang control supports signed
outputs, restart hysteresis, and one neutral tick during reversal. A raw hardware source
mapped to several measurements is sampled once per cycle before applying each transform.
All loop results are checked before output writes begin. The declared field types and
numeric limits apply to target updates; missing feedback required by a control loop is an
unhealthy configuration, even when the underlying device is marked optional.

Descriptor topology is fixed when a subsystem is constructed. Reconstruct it after
changing hardware, measurement, field, or loop identities. This runtime still allocates
Python numeric results. Profiled setpoints, feedforward, and the remaining advanced
descriptor safety features are open implementation/audit work; ordinary PID execution
does not establish that those declarations are implemented.

## Installation on Raspberry Pi Pico W

Copy the `ares_micro` directory to `/lib/ares_micro` on your Pico W filesystem via `mpremote`:

```bash
mpremote cp -r ares_micro :lib/ares_micro
```

Normal users do not install this module independently. The official standalone XRP starter bundles
the pinned runtime and its `ares deploy` command verifies then copies the exact project/runtime
files to the board.
