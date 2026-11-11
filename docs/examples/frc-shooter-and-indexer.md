# FRC Example: Velocity Flywheel Shooter + Beam-Break Indexer

In FRC games, a shooter flywheel must spin up to a target surface velocity (or RPM) **before** the indexer feeds a game piece into the wheel. Feeding too early causes a weak shot or a jam!

This recipe shows how to build a **Shooter + Indexer** pair in **ARES Robotics Studio** and write a clean, student-friendly readiness helper.

---

## Part 1: Setting Up in the Studio GUI

### A. Create the `Shooter` Subsystem (Velocity Controlled)
1. Open **Subsystems** and click **Add a mechanism**.
2. In **1. Purpose & Template**:
   - **Mechanism name:** `Shooter`
   - **Template:** `Velocity Mechanism` (or `Flywheel`)
3. In **2. Hardware & IO**:
   - Configure `leftFlywheelMotor` (CAN ID `21`) and `rightFlywheelMotor` (CAN ID `22`, inverted follower).
   - Attach integrated CAN encoder velocity feedback.
4. In **3. Stateflow & Control**:
   - Verify the target state field `targetVelocityRpm` and the **Velocity PID + Feedforward (`kV`, `kS`, `kP`)** control rule.
5. In **4. Tuning & Review**:
   - Click **Save & create files**.

### B. Create the `Indexer` Subsystem (With Beam-Break Sensor)
1. Add a second subsystem named `Indexer` using the `Simple Actuator` template.
2. In **2. Hardware & IO**:
   - Add `indexerMotor` (CAN ID `23`) and a digital sensor `noteBeamBreak` (roboRIO DIO Port `0`).
3. Click **Save & create files**.

---

## Part 2: Writing Safe Student Extension Helpers (`USER-OWNED`)

Create `ShooterAndIndexerExtensions.kt` in your project's `src/main/kotlin/` source directory:

```kotlin
// File: src/main/kotlin/org/aresfirst/frc/subsystems/ShooterAndIndexerExtensions.kt
// Ownership: USER-OWNED (Safe to edit — ARES Studio never overwrites this file)

package org.aresfirst.frc.subsystems

import kotlin.math.abs

object ShooterTuningDefaults {
    const val SPEAKER_SHOT_RPM = 4200.0
    const val AMP_LOB_RPM = 1800.0
    const val SPINUP_TOLERANCE_RPM = 75.0
}

/**
 * Student Helper 1: Is the flywheel spun up and stable at its target RPM?
 *
 * Notice how this function is 100% pure: it reads the cached immutable state values
 * from the current 20 ms loop without making any extra CAN bus calls!
 */
fun isFlywheelSpunUp(
    shooterEnabled: Boolean,
    shooterFaulted: Boolean,
    measuredRpm: Double,
    targetRpm: Double,
): Boolean {
    if (!shooterEnabled || shooterFaulted) return false
    if (targetRpm <= 100.0) return false // Flywheel is idle, not spinning up for a shot
    return abs(measuredRpm - targetRpm) <= ShooterTuningDefaults.SPINUP_TOLERANCE_RPM
}

/**
 * Student Helper 2: Compute the safe indexer feeder voltage.
 *
 * Rules:
 * 1. If the driver requests SHOOT and the flywheel is spun up -> feed full speed (+9.0 V).
 * 2. If the driver requests INTAKE and the beam-break sensor does NOT see a note yet -> stage slowly (+4.0 V).
 * 3. As soon as the beam-break sensor detects the note (hasGamePiece == true), stop staging (0.0 V)
 *    until the flywheel is spun up!
 */
fun computeIndexerFeederVoltage(
    driverRequestShoot: Boolean,
    driverRequestStage: Boolean,
    hasGamePieceAtBeamBreak: Boolean,
    flywheelReady: Boolean,
): Double = when {
    driverRequestShoot && flywheelReady -> 9.0
    driverRequestStage && !hasGamePieceAtBeamBreak -> 4.0
    else -> 0.0
}
```

---

## Part 3: Testing in the Local FRC Simulator & Swerve Visualizer

1. Open the **Dashboard** tab and select **Drive Practice** or **PID Tuning**.
2. Click **Launch simulator** in the `Local FRC Simulator` bar (ARES manages the simulation Driver Station state automatically—no separate WPILib Driver Station window required).
3. Click **Start driving** and **Arm control**.
4. In the **Swerve Module Diagnostics** widget, click **"View: Chassis 2D"** in the top-right corner of the card to watch your 4 swerve module velocity/angle vectors update live while testing your shooter telemetry!
