# FTC Example: Roller Intake + Position-Controlled Arm

This recipe shows how an FTC team builds a two-mechanism scoring robot in **ARES Robotics Studio**:
1. **`Intake`** — A single-motor roller that spins inward (`+10.0 V`) to collect a sample and outward (`-8.0 V`) to eject a jammed piece.
2. **`Arm`** — A position-controlled pivot arm with an encoder, soft angle limits (`0°` to `115°`), and three preset scoring angles (`INTAKE = 5°`, `CARRY = 35°`, `SCORE_HIGH = 105°`).

---

## Part 1: Setting Up Both Subsystems in the Studio GUI

### A. Create the `Intake` Subsystem
1. Open **Subsystems** and click **Add a mechanism**.
2. In **1. Purpose & Template**:
   - **Mechanism name:** `Intake`
   - **Template:** `Simple Actuator`
3. In **2. Hardware & IO**:
   - Select the motor, name it `intakeRoller`, and assign it to **Control Hub Motor Port `0`**.
4. In **3. Stateflow & Control**:
   - Keep the default writable target (`targetVoltage`) and **Open-Loop Voltage** rule.
5. In **4. Tuning & Review**:
   - Click **Save & create files**.

### B. Create the `Arm` Subsystem
1. Click **Add a mechanism** to create a second subsystem.
2. In **1. Purpose & Template**:
   - **Mechanism name:** `Arm`
   - **Template:** `Position Mechanism`
3. In **2. Hardware & IO**:
   - Assign `armPivotMotor` to **Control Hub Motor Port `1`** with its quadrature encoder enabled.
   - Set soft limits: **Minimum = `0.0 deg`**, **Maximum = `115.0 deg`**.
4. In **3. Stateflow & Control**:
   - Inspect the `targetPosition` state field and the **PID / Profiled Position** control rule (`kP = 0.08`, `kD = 0.002`).
5. In **4. Tuning & Review**:
   - Click **Save & create files**.

---

## Part 2: Writing Safe Student Extension Helpers (`USER-OWNED`)

ARES generates the read-only state and controller plumbing inside `build/generated/ares/`. To add preset angles and a safety check that prevents running the intake roller unless the arm is lowered, create a student-owned Kotlin file in `src/main/kotlin/`:

```kotlin
// File: src/main/kotlin/org/aresfirst/ftc/subsystems/IntakeAndArmExtensions.kt
// Ownership: USER-OWNED (ARES Studio will NEVER overwrite this file!)

package org.aresfirst.ftc.subsystems

import kotlin.math.abs

/**
 * Named angle presets (in degrees) for the FTC scoring arm.
 * Putting constants in one student file makes them easy to adjust during pit tuning!
 */
object ArmScoringPresets {
    const val INTAKE_DOWN_DEG = 5.0
    const val SAFE_CARRY_DEG = 35.0
    const val SCORE_BASKET_DEG = 105.0
    const val READY_TOLERANCE_DEG = 3.0
}

/**
 * Student Helper 1: Is the arm low enough to safely spin the intake rollers?
 *
 * Why this matters: If the arm is raised to 105 degrees, spinning the intake isn't useful
 * and could catch on mechanism cables.
 */
fun isArmReadyForIntake(
    armEnabled: Boolean,
    armFaulted: Boolean,
    armCurrentAngleDeg: Double,
): Boolean {
    if (!armEnabled || armFaulted) return false
    return abs(armCurrentAngleDeg - ArmScoringPresets.INTAKE_DOWN_DEG) <= ArmScoringPresets.READY_TOLERANCE_DEG
}

/**
 * Student Helper 2: Compute the safe intake roller voltage based on driver buttons and arm position.
 *
 * - If the driver holds EJECT (reverse), always allow reversing to clear a jam (-8.0 V).
 * - If the driver holds INTAKE (forward), only spin inward (+10.0 V) when the arm is down!
 * - Otherwise, return 0.0 V (safe neutral).
 */
fun computeSafeIntakeVoltage(
    driverRequestCollect: Boolean,
    driverRequestEject: Boolean,
    armEnabled: Boolean,
    armFaulted: Boolean,
    armCurrentAngleDeg: Double,
): Double = when {
    driverRequestEject -> -8.0
    driverRequestCollect && isArmReadyForIntake(armEnabled, armFaulted, armCurrentAngleDeg) -> 10.0
    else -> 0.0
}
```

---

## Part 3: Testing in the Local FTC Simulator

1. Open the **Dashboard** tab and select the **Starter** or **PID Tuning** layout preset.
2. Click **Launch simulator** in the `Local FTC Simulator` bar.
3. Choose your TeleOp mode, click **Start driving**, and click **Arm control**.
4. In the **Telemetry Line Chart**, click the topic chips for `Arm/PositionDeg` and `Arm/TargetPositionDeg` to watch the simulated arm track your preset angles in real time!
