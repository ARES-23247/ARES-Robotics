# ARESLib-Kotlin Requirements

## Milestone v1.8 Vision & AprilTags

### Vision Math & Geometry
- [x] **REQ-VIS-01**: The system must provide immutable 3D coordinate data structures (`Pose3d`, `Transform3d`, `Rotation3d`).
- [x] **REQ-VIS-02**: The system must accurately transform 3D camera offsets and target detections into robot field space.

### State & Actions
- [x] **REQ-VIS-03**: The central `RobotState` must maintain a rolling history of recent Vision Measurements, containing timestamp, target pose, tag ID, and ambiguity.
- [x] **REQ-VIS-04**: The `VisionReducer` must correctly evaluate `RobotAction.VisionMeasurementsReceived` and filter out statistically impossible jumps or extremely high-ambiguity tags.

### Odometry Fusion (Pose Estimator)
- [x] **REQ-VIS-05**: A pure functional Kalman Filter (or equivalent state-estimator) must exist to fuse high-frequency wheel odometry with low-frequency, delayed vision poses.
- [x] **REQ-VIS-06**: The Pose Estimator must not produce large garbage collection overhead on Android ART (meaning no heavy allocations of intermediate object matrices per loop).
- [x] **REQ-VIS-07**: The Pose Estimator must retroactively apply vision corrections into the odometry buffer using the exact timestamp of the camera frame, smoothly adjusting the current active pose without jarring jumps.

### IO Layer Integration
- [x] **REQ-VIS-08**: Provide an abstraction (`VisionIO`) that works uniformly for FTC Limelight 3A NetworkTables and FRC Limelight 4 / PhotonVision data sources.

## Milestone v1.9 FTC Hardware Abstraction Layer

### Core Hardware Interfaces
- [ ] **REQ-HW-01**: Define `MotorIO`, `ServoIO`, `OdometryIO`, and `ImuIO` interfaces in the `:core` module.

### FTC SDK Implementations
- [ ] **REQ-HW-02**: Implement `FtcRevHubIO` wrapping `DcMotorEx`, `Servo`, `CRServo`, `AnalogInput` (including goBILDA Floodgate), `DigitalChannel`, and `BNO055IMU`.
- [ ] **REQ-HW-03**: Implement `PinpointOdometryIO` wrapping the goBILDA Pinpoint driver.

### Auxiliary I2C Wrappers
- [ ] **REQ-HW-04**: Port `OctoQuadFWv3.java` to a native Kotlin `OctoquadIO` implementation.
- [ ] **REQ-HW-05**: Implement modular wrappers for the SRS Hub (e.g. `SrsHubAnalogIO`, `SrsHubPwmIO`).
