# Roadmap: ARESLib-Kotlin

## Milestones

- ✅ **v1.0 MVP** — Phases 1-5 (shipped 2026-05-16)
- ✅ **v1.1 Driveable Base** — Phases 6-9 (shipped 2026-05-16)
- ✅ **v1.2 Deployable Mecanum Base** — Phases 10-12 (shipped 2026-05-16)

- ✅ **v1.3 Deployable Autonomy Base** — Phases 13-15 (shipped 2026-05-16)
- ✅ **v1.4 Desktop Simulation & Visualization** — Phase 16 (shipped 2026-05-16)
- ✅ **v1.5 Trajectory Following in Simulation** — Phase 17 (shipped 2026-05-16)

- ✅ **v1.7 Virtual Driver Station** — Phase 21 (shipped 2026-05-16)

## Phases

<details>
<summary>✅ v1.7 Virtual Driver Station (Phase 21) — SHIPPED 2026-05-16</summary>

- [x] Phase 21: Virtual Driver Station UI and Gamepad Overhaul (completed 2026-05-16)

</details>

<details>
<summary>✅ v1.6 Advanced Path Generation (Phases 18-20) — SHIPPED 2026-05-16</summary>

- [x] Phase 18: Spline Mathematics & Interpolation (1/1 plan) — completed 2026-05-16
- [x] Phase 19: Motion Profiling (1/1 plan) — completed 2026-05-16
- [x] Phase 20: Event Marker State Machine Integration (1/1 plan) — completed 2026-05-16

</details>

<details>
<summary>✅ v1.5 Trajectory Following in Simulation (Phase 17) — SHIPPED 2026-05-16</summary>

- [x] Phase 17: Holonomic Controller & Path Following Physics Loop (1 plan)

</details>

<details>
<summary>✅ v1.4 Desktop Simulation & Visualization (Phase 16) — SHIPPED 2026-05-16</summary>

- [x] Phase 16: Dyn4j Physics & AdvantageScope Telemetry (1 plan)

</details>

<details>
<summary>✅ v1.3 Deployable Autonomy Base (Phases 13-15) — SHIPPED 2026-05-16</summary>

- [x] Phase 13: PathPlanner JSON Parsing (1 plan)
- [x] Phase 14: Pure Holonomic Drive Controller (1 plan)
- [x] Phase 15: FTC Autonomous OpMode Integration (1 plan)

</details>

<details>
<summary>✅ v1.1 Driveable Base (Phases 6-9) — SHIPPED 2026-05-16</summary>

- [x] Phase 6: Gamepad Input Mapping (1 plan)
- [x] Phase 7: Hardware Odometry Bridge (1 plan)
- [x] Phase 8: Field-Centric Drivetrain (1 plan)
- [x] Phase 9: FTC Dashboard & Telemetry (1 plan)

</details>

<details>
<summary>✅ v1.2 Deployable Mecanum Base (Phases 10-12) — SHIPPED 2026-05-16</summary>

- [x] Phase 10: Mecanum Kinematics Engine (1 plan)
- [x] Phase 11: Mecanum Hardware IO (1 plan)
- [x] Phase 12: Deployable TeleOp Integration (1 plan)

</details>

## Progress

| Phase             | Milestone | Plans Complete | Status      | Completed  |
| ----------------- | --------- | -------------- | ----------- | ---------- |
| 1. Functional Core Scaffold | v1.0 | 1/1            | Complete    | 2026-05-15 |
| 2. FRC Bridge | v1.0      | 1/1            | Complete    | 2026-05-15 |
| 3. FTC Bridge | v1.0      | 1/1            | Complete    | 2026-05-16 |
| 4. Kinematics | v1.0      | 1/1            | Complete    | 2026-05-16 |
| 5. Autonomy | v1.0      | 1/1            | Complete    | 2026-05-16 |
| 6. Gamepad Input | v1.1 | 1/1 | Complete | 2026-05-16 |
| 7. Hardware Odom | v1.1 | 1/1 | Complete | 2026-05-16 |
| 8. Field-Centric Drive | v1.1 | 1/1 | Complete | 2026-05-16 |
| 9. Dashboard | v1.1 | 1/1 | Complete | 2026-05-16 |
| 10. Mecanum Math | v1.2 | 1/1 | Complete | 2026-05-16 |
| 11. Mecanum IO | v1.2 | 1/1 | Complete | 2026-05-16 |
| 12. TeleOp OpMode | v1.2 | 1/1 | Complete | 2026-05-16 |
| 13. Path Parsing | v1.3 | 1/1 | Complete | 2026-05-16 |
| 14. Drive Controller | v1.3 | 1/1 | Complete | 2026-05-16 |
| 15. Auto OpMode | v1.3 | 1/1 | Complete | 2026-05-16 |
| 16. Desktop Simulation | v1.4 | 1/1 | Complete | 2026-05-16 |
| 17. Trajectory Following in Sim | v1.5 | 1/1 | Complete | 2026-05-16 |
| 18. Spline Mathematics | v1.6 | 1/1 | Complete | 2026-05-16 |
| 19. Motion Profiling | v1.6 | 1/1 | Complete | 2026-05-16 |
| 20. Event Marker State Machine Integration | v1.6 | 1/1 | Complete | 2026-05-16 |

| 21. Virtual Driver Station | v1.7 | 1/1 | Complete | 2026-05-16 |
| 22. 3D Math | v1.8 | 1/1 | Complete | 2026-05-17 |
| 23. Vision State & Actions | v1.8 | 1/1 | Complete | 2026-05-17 |
| 24. Pose Estimator | v1.8 | 1/1 | Complete | 2026-05-17 |
| 25. IO Layer Integrations | v1.8 | 1/1 | Complete | 2026-05-17 |
| 26. Core Hardware IO Interfaces | v1.9 | 1/1 | Complete | 2026-05-17 |
| 27. FTC REV Hub & Pinpoint Integration | v1.9 | 1/1 | Complete | 2026-05-17 |
| 28. I2C Auxiliary Wrappers | v1.9 | 1/1 | Complete | 2026-05-17 |
| 29. Add absolute encoder wrappers | v1.9 | 1/1 | Complete | 2026-05-17 |

### Phase 22: 3D Geometry and Transformations

**Goal:** Implement pure `Pose3d`, `Transform3d`, and `Rotation3d` data classes for coordinate transformations.
**Requirements**: REQ-VIS-01
**Depends on:** Phase 21
**Plans:** 1 plans

Plans:
- [x] TBD (run /gsd-plan-phase 22 to break down)

### Phase 23: Vision State & Actions

**Goal:** Add immutable `VisionMeasurement` collections and `VisionReducer` to the Redux state.
**Requirements**: REQ-VIS-02
**Depends on:** Phase 22
**Plans:** 1 plans

Plans:
- [x] TBD (run /gsd-plan-phase 23 to break down)

### Phase 24: Pose Estimator

**Goal:** Build a matrix-free / primitive-array chronological Kalman Filter equivalent for vision-odometry fusion.
**Requirements**: REQ-VIS-03
**Depends on:** Phase 23
**Plans:** 1 plans

Plans:
- [x] TBD (run /gsd-plan-phase 24 to break down)

### Phase 25: Vision IO Layer Integration

**Goal:** Implement `VisionIO` interfaces to support Limelight 3A (FTC) and Limelight 4 / PhotonVision (FRC).
**Requirements**: REQ-VIS-04
**Depends on:** Phase 24
**Plans:** 1 plans

Plans:
- [x] TBD (run /gsd-plan-phase 25 to break down)

### Phase 26: Core Hardware IO Interfaces

**Goal:** Define abstract hardware interfaces in `:core` (`MotorIO`, `ServoIO`, `OdometryIO`, `ImuIO`).
**Requirements**: REQ-HW-01
**Depends on:** Phase 25
**Plans:** 1 plans

Plans:
- [x] Implement interfaces.

### Phase 27: FTC REV Hub & Pinpoint Integration

**Goal:** Implement FTC SDK wrappers (`FtcRevHubIO`, `PinpointOdometryIO`) in the new `:ftc-hardware` module.
**Requirements**: REQ-HW-02
**Depends on:** Phase 26
**Plans:** 1 plans

Plans:
- [x] Create module and wrappers.

### Phase 28: I2C Auxiliary Wrappers

**Goal:** Implement OctoQuad and SRS Hub modular wrappers.
**Requirements**: REQ-HW-03
**Depends on:** Phase 27
**Plans:** 1 plans

Plans:
- [x] Implement `OctoquadIO` and `SrsHubIO`.

### Phase 29: Add absolute encoder wrappers

**Goal:** [To be planned]
**Requirements**: TBD
**Depends on:** Phase 28
**Plans:** 0 plans

Plans:
- [x] TBD (run /gsd-plan-phase 29 to break down)
