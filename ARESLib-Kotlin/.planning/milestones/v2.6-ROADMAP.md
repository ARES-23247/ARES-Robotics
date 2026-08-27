# Roadmap: ARESLib-Kotlin

## Milestones

- ✅ **v1.0 MVP** — Phases 1-5 (shipped 2026-05-16)
- ✅ **v1.1 Driveable Base** — Phases 6-9 (shipped 2026-05-16)
- ✅ **v1.2 Deployable Mecanum Base** — Phases 10-12 (shipped 2026-05-16)
- ✅ **v1.3 Deployable Autonomy Base** — Phases 13-15 (shipped 2026-05-16)
- ✅ **v1.4 Desktop Simulation & Visualization** — Phase 16 (shipped 2026-05-16)
- ✅ **v1.5 Trajectory Following in Simulation** — Phase 17 (shipped 2026-05-16)
- ✅ **v1.6 Advanced Path Generation** — Phases 18-20 (shipped 2026-05-16)
- ✅ **v1.7 Virtual Driver Station** — Phase 21 (shipped 2026-05-16)
- ✅ **v1.8 Vision & Localization** — Phases 22-25 (shipped 2026-05-17)
- ✅ **v1.9 Core Hardware IO Interfaces** — Phases 26-29 (shipped 2026-05-17)
- ✅ **v1.10 Match-Ready Telemetry & Hardware Integration** — Phases 30-33 (shipped 2026-05-17)
- ✅ **v2.0 Real Robot Deployment** — Phases 34-37 (shipped 2026-05-18)
- ✅ **v2.1 FRC CTRE Swerve Integration** — Phases 38-40 (shipped 2026-05-18)

- ✅ **v2.2 FRC Physics Simulation** — Phases 41-42 (shipped 2026-05-18)
- ✅ **v2.3 FRC Autonomous Trajectory Following** — Phases 43-44 (shipped 2026-05-18)
- ✅ **v2.4 FRC/FTC Vision & Multi-Sensor Kalman Filter Integration** — Phases 45-48 (shipped 2026-05-18)
- ✅ **v2.5 Hardened EKF Localization & Dynamic Sensor Fusion** — Phases 49-52 (shipped 2026-05-18)
- 📍 **v2.6 Dynamic Swerve Trajectory Optimization & Obstacle Avoidance** — Phases 53-56 (In Planning)

## Phases

## 📍 v2.6 Dynamic Swerve Trajectory Optimization & Obstacle Avoidance (Phases 53-56)

- [x] Phase 53: Centripetal Velocity Limiting & Swerve Rate Limiting
- [ ] Phase 54: Distance Sensor Local Costmap Integration
- [ ] Phase 55: Real-Time Vector Field Histogram (VFH+) Path Planning
- [ ] Phase 56: Closed-Loop Obstacle Avoidance Simulation & Verification

<details>
<summary>✅ v2.5 Hardened EKF Localization & Dynamic Sensor Fusion (Phases 49-52) — SHIPPED</summary>

- [x] Phase 49: State Expansion & IMU Pitch/Roll Telemetry
- [x] Phase 50: Advanced Outlier Filter (3D Boundaries, Angular Speed, Acceleration/Shock Lockouts)
- [x] Phase 51: Dynamic Odometry Covariance Scaling (Tilt/Beaching Protection)
- [x] Phase 52: Dynamic Vision Covariance Scaling (Distance-based SD & Multi-Tag Trust)

</details>

<details>
<summary>✅ v2.4 FRC/FTC Vision & Multi-Sensor Kalman Filter Integration (Phases 45-48) — SHIPPED</summary>

- [x] Phase 45: Chronological Asynchronous Vision Measurement Buffer
- [x] Phase 46: Pose Disambiguation and Outlier Filtering
- [x] Phase 47: Extended Kalman Filter Integration
- [x] Phase 48: High-Fidelity Vision Simulation & Noise Rejection

</details>

<details>
<summary>✅ v2.3 FRC Autonomous Trajectory Following (Phases 43-44) — SHIPPED</summary>

- [x] Phase 43: Autonomous Path Parsing & State Wiring
- [x] Phase 44: Trajectory Follower Integration & Simulation Verification

</details>

<details>
<summary>✅ v2.2 FRC Physics Simulation (Phases 41-42) — SHIPPED</summary>

- [x] Phase 41: Simulation Core & Field Obstacles
- [x] Phase 42: Fuel Dynamics & Telemetry

</details>

<details>
<summary>✅ v2.1 FRC CTRE Swerve Integration (Phases 38-40) — SHIPPED</summary>

- [x] Phase 38: Initialize FRC App & Telemetry
- [x] Phase 39: Swerve Hardware IO Bridge
- [x] Phase 40: FRC Robot Loop & Simulation

</details>

<details>
<summary>✅ v2.0 Real Robot Deployment (Phases 34-37) — SHIPPED</summary>

- [x] Phase 34: Build Chain Unification & Mock Isolation
- [x] Phase 35: Deployable TeleOp OpMode
- [x] Phase 36: Telemetry Hardening
- [x] Phase 37: Deployment & Smoke Test

</details>

<details>
<summary>✅ v1.10 Match-Ready Telemetry & Hardware Integration (Phases 30-33) — SHIPPED</summary>

- [x] Phase 30: Advanced Controller Architecture
- [x] Phase 31: NetworkTables 4 (NT4) Server
- [x] Phase 32: Physical Vision & Hardware Configuration
- [x] Phase 33: On-Device Data Logging

</details>

<details>
<summary>✅ v1.9 Core Hardware IO Interfaces (Phases 26-29) — SHIPPED</summary>

- [x] Phase 26: Core Hardware IO Interfaces
- [x] Phase 27: FTC REV Hub & Pinpoint Integration
- [x] Phase 28: I2C Auxiliary Wrappers
- [x] Phase 29: Add absolute encoder wrappers

</details>

<details>
<summary>✅ v1.8 Vision & Localization (Phases 22-25) — SHIPPED</summary>

- [x] Phase 22: 3D Geometry and Transformations
- [x] Phase 23: Vision State & Actions
- [x] Phase 24: Pose Estimator
- [x] Phase 25: Vision IO Layer Integration

</details>

## Progress

| Phase             | Milestone | Plans Complete | Status      | Completed  |
| ----------------- | --------- | -------------- | ----------- | ---------- |
| ... | | | | |
| 34. Build Chain Unification & Mock Isolation | v2.0 | 1/1 | Complete | 2026-05-18 |
| 35. Deployable TeleOp OpMode | v2.0 | 1/1 | Complete | 2026-05-18 |
| 36. Telemetry Hardening | v2.0 | 1/1 | Complete | 2026-05-18 |
| 37. Deployment & Smoke Test | v2.0 | 1/1 | Complete | 2026-05-18 |
| 38. Initialize FRC App & Telemetry | v2.1 | 1/1 | Complete | 2026-05-18 |
| 39. Swerve Hardware IO Bridge | v2.1 | 1/1 | Complete | 2026-05-18 |
| 40. FRC Robot Loop & Simulation | v2.1 | 1/1 | Complete | 2026-05-18 |
| 41. Simulation Core & Field Obstacles | v2.2 | 1/1 | Complete | 2026-05-18 |
| 42. Fuel Dynamics & Telemetry | v2.2 | 1/1 | Complete | 2026-05-18 |
| 43. Autonomous Path Parsing & State Wiring | v2.3 | 1/1 | Complete | 2026-05-18 |
| 44. Trajectory Follower Integration & Simulation Verification | v2.3 | 1/1 | Complete | 2026-05-18 |
| 45. Chronological Asynchronous Vision Measurement Buffer | v2.4 | 1/1 | Complete | 2026-05-18 |
| 46. Pose Disambiguation and Outlier Filtering | v2.4 | 1/1 | Complete | 2026-05-18 |
| 47. Extended Kalman Filter Integration | v2.4 | 1/1 | Complete | 2026-05-18 |
| 48. High-Fidelity Vision Simulation & Noise Rejection | v2.4 | 1/1 | Complete | 2026-05-18 |
| 49. State Expansion & IMU Pitch/Roll Telemetry | v2.5 | 1/1 | Complete | 2026-05-18 |
| 50. Advanced Outlier Filter (3D Boundaries, Angular Speed, Acceleration/Shock Lockouts) | v2.5 | 1/1 | Complete | 2026-05-18 |
| 51. Dynamic Odometry Covariance Scaling (Tilt/Beaching Protection) | v2.5 | 1/1 | Complete | 2026-05-18 |
| 52. Dynamic Vision Covariance Scaling (Distance-based SD & Multi-Tag Trust) | v2.5 | 1/1 | Complete | 2026-05-18 |
| 53. Centripetal Velocity Limiting & Swerve Rate Limiting | v2.6 | 1/1 | Complete | 2026-05-18 |
| 54. Distance Sensor Local Costmap Integration | v2.6 | 0/1 | Planned | - |
| 55. Real-Time Vector Field Histogram (VFH+) Path Planning | v2.6 | 0/1 | Planned | - |
| 56. Closed-Loop Obstacle Avoidance Simulation & Verification | v2.6 | 0/1 | Planned | - |


