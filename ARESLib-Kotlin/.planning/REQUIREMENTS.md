# Requirements - Milestone v3.1: FTC EKF Localization Hardening & FRC E2E Match Simulation

Active requirements for Milestone v3.1.

## Active Requirements

### Category: Subsystem Modularity
* [ ] **FTC-SYS-02 (Student Loop Facade)**: Implement a clean vertical-slice subsystem modularity design and an intuitive student-facing loop facade to simplify state, action dispatching, and background telemetry.

### Category: EKF Localization & Lockout Hardening
* [ ] **FTC-EKF-03 (Reset Alignment & Yaw Gate)**: Correct FTC EKF estimated pose overrides when resetting offsets, and widen the Limelight yaw rejection gate to 30.0 degrees to prevent permanent EKF lockouts under drift.

### Category: E2E Match Trajectory Simulation
* [ ] **SYS-TEST-01 (Autonomous Match Simulation)**: Author a complete FRC Autonomous Sequence (`ShootAndMobility`) that triggers path-following checkpoints, transitions intake pivots, regulates flywheels at 4000 RPM, triggers feeder sensors, and shoots fuel targets entirely in simulation.

---

## Traceability

| Requirement ID | Description | Mapped Phase | Status |
|----------------|-------------|--------------|--------|
| **FTC-SYS-02** | Student Loop Facade | Phase 74.1   | Planned|
| **FTC-EKF-03** | EKF Reset Alignment & Yaw Gate | Phase 74.2   | Planned|
| **SYS-TEST-01**| Autonomous Match Simulation | Phase 75     | Planned|

---

## Future Requirements (Deferred)
- Automated battery-voltage current compensation on physical TalonFX loops.
