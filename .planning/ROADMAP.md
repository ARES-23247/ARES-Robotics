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
- ✅ **v2.6 Dynamic Swerve Trajectory Optimization & Obstacle Avoidance** — Phases 53-55 (shipped 2026-05-18)
- ✅ **v2.7 Path Execution & Dynamic Task Planning** — Phases 56-59 (shipped 2026-05-18)
- ✅ **v2.8 Deterministic Input Replay & "What-If" Ghost Simulation** — Phases 60-63 (shipped 2026-05-18)
- ✅ **v2.9 Physical Deployment & FRC Redux Superstructure Architecture** — Phases 64-71 (shipped 2026-05-18)
- ✅ **v3.0 FRC Unified Robot Integration & Full System Verification** — Phases 72-74 (shipped 2026-06-15)
- ℹ️ **v3.1 historical planning line** — superseded as release numbering advanced through v9.x
- 🚧 **v9.3.6 Runtime Reliability, Logging Governance & Soak Validation** — Phase 76 (in progress)

## Phases

### 🚧 v9.3.6 Runtime Reliability, Logging Governance & Soak Validation (Phase 76)

The historical v3.1 tasks are no longer a trustworthy description of the live workspace. Current
release work starts from the published 9.3.5 baseline and closes the failure modes discovered during
extended simulator driving.

- [x] LOG-GOV-01: Competition, simulation, and forensic sampling profiles
- [x] LOG-GOV-02: Streaming gzip, bounded rotation, retention, and stale-active quarantine
- [x] OBS-LOG-01: Robot logger queue/drop/rate/storage health in NT4 and Analytics
- [x] IMPORT-GZ-01: Bounded `.csv.gz` import and automatic discovery
- [x] REPLAY-LIVE-01: Atomic live-pose rewind with committed persistence and one clock domain
- [x] SOAK-FRC-01: Five simulated minutes of leased dashboard translation and rotation
- [x] SOAK-FTC-01: One continuous real NT4 FTC simulator hour with zero control stalls,
  bounded telemetry smoothness, and verified live rewind
- [ ] RELEASE-GATE-01: Full library suite, API check, isolated repository, and all consumers

### Phase 76: Runtime Reliability, Logging Governance & Soak Validation

**Goal**: Make extended robot/simulator sessions boring: bounded disk use, observable writer
backpressure, responsive leased controls, time-aligned estimator telemetry, and repeatable release
evidence across FTC, FRC, Analytics, and ARESLib.

<details>
<summary>✅ Legacy Milestones (v1.0 to v3.0) — SHIPPED</summary>

- [x] Phases 1-74 completed successfully and archived.

</details>

## Progress

| Phase             | Milestone | Plans Complete | Status      | Completed  |
| ----------------- | --------- | -------------- | ----------- | ---------- |
| 76. Runtime Reliability, Logging Governance & Soak Validation | v9.3.6 | 7/8 | In progress | - |
