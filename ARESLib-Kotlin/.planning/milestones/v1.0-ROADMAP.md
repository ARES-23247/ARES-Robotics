# Roadmap: ARESLib-Kotlin

**Mode:** standard
**Granularity:** coarse
**Project mode:** standard

## Overview
**5 phases** | **13 requirements mapped** | All v1 requirements covered ✓

| # | Phase | Goal | Requirements | Success Criteria |
|---|-------|------|--------------|------------------|
| ✓ | Functional Core Scaffold | Establish Redux-style store and pure state boundaries | 4 | 3 |
| ✓ | FRC Hardware Bridge & Logging | CTRE CANivore integration and native AdvantageScope serialization | 2 | 2 |
| ✓ | FTC Hardware Bridge | Hollow wrapper LinearOpMode and hardware map integration | 2 | 2 |
| ✓ | Kinematics Engines | Implement pure control logic for Holonomic & Differential drives | 3 | 3 |
| ✓ | Functional Autonomy | PathPlanner JSON parsing and pure trajectory following | 2 | 2 |

## Phase Details

### ~~Phase 1: Functional Core Scaffold~~
**Goal:** Establish Redux-style store and pure state boundaries
**Requirements:** CORE-01, CORE-02, CORE-03, CORE-04
**Success Criteria:**
1. `RobotState` and nested structures instantiate without hardware coupling.
2. `rootReducer` correctly returns a copy of the state when processing a generic `RobotAction`.
3. Zero mutable variables exist within the core state models.

### ~~Phase 2: FRC Hardware Bridge & Logging~~
**Goal:** CTRE CANivore integration and native AdvantageScope serialization
**Requirements:** FRC-01, FRC-02
**Success Criteria:**
1. `SwerveModuleIOPhoenix6` correctly implements `waitForUpdate` to block and return synchronized primitive data.
2. `RobotState` serializes to a raw byte array via WPILib Struct without throwing exceptions.

### ~~Phase 3: FTC Hardware Bridge~~
**Goal:** Hollow wrapper LinearOpMode and hardware map integration
**Requirements:** FTC-01, FTC-02
**Success Criteria:**
1. Sample FTC opmode correctly passes a `HardwareMap` to the IO layer.
2. Output commands correctly reflect in motor telemetry within an `opModeIsActive` loop.

### ~~Phase 4: Kinematics Engines~~
**Goal:** Implement pure control logic for Holonomic & Differential drives
**Requirements:** KIN-01, KIN-02, KIN-03
**Success Criteria:**
1. Swerve, Mecanum, and Differential calculations map immutable state to valid OutputCommands.
2. Kinematics classes contain zero `com.qualcomm` or `edu.wpi` imports.
3. Module state correctly responds to translational/rotational input intent.

### ~~Phase 5: Functional Autonomy~~
**Goal:** PathPlanner JSON parsing and pure trajectory following
**Requirements:** AUTO-01, AUTO-02
**Success Criteria:**
1. A raw PathPlanner JSON file successfully inflates into the `Trajectory` data class.
2. `calculateTrajectoryOutput` correctly yields target `ChassisSpeeds` when provided with a current pose and elapsed time.

---
*Roadmap generated: 2026-05-15*
