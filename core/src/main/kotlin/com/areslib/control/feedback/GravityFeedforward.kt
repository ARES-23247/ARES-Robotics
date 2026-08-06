package com.areslib.control.feedback

import kotlin.math.cos

/**
 * Pure Mathematical Gravity Feedforward Calculators for Elevators and Rotating Arms.
 *
 * Provides static and adaptive gravitational compensation feedforward voltage calculations for linear vertical elevators
 * and single-joint rotational arms.
 *
 * ### Mathematical Formulations:
 * 1. **Linear Elevator Gravity Compensation**:
 *    $$u_{FF} = k_G$$
 * 2. **Payload-Adaptive Elevator Gravity Compensation**:
 *    $$u_{FF} = k_{G,base} \cdot \left(1.0 + \alpha_{piece} \cdot N_{pieces}\right)$$
 * 3. **Rotational Arm Gravity Compensation**:
 *    $$u_{FF} = k_G \cdot \cos(\theta_{arm})$$
 *    *(where $\theta = 0\text{ rad}$ corresponds to the horizontal arm position).*
 *
 * ### Physical Units & Properties:
 * - Gravity Constant ($k_G$): Volts ($V$) or duty-cycle percentage ($[-1.0, 1.0]$)
 * - Arm Angle ($\theta_{arm}$): Radians ($rad$) relative to horizontal ($0\text{ rad}$)
 * - Inventory Count ($N_{pieces}$): Integer count of loaded game elements
 * - Zero-GC Compliance: 100% pure static functions with zero allocations.
 */
object GravityFeedforward {

    /**
     * Calculates constant gravity feedforward voltage for a linear vertical elevator.
     *
     * @param kG Gravity compensation constant in Volts ($V$) or percent output ($[-1.0, 1.0]$).
     * @return Gravity feedforward compensation value ($V$ or percent).
     */
    fun calculateElevator(kG: Double): Double {
        if (!kG.isFinite()) return 0.0
        return kG
    }

    /**
     * Calculates adaptive gravity feedforward for a linear elevator scaled by current game piece payload count.
     *
     * @param baseKG Base gravity compensation constant when elevator is empty ($V$).
     * @param inventoryCount Current integer count of loaded game elements ($N \ge 0$).
     * @param factorPerPiece Fractional mass scaling factor added per loaded element (default: $0.1$ or +10% per piece).
     * @return Adaptive gravity feedforward compensation value ($V$).
     */
    fun calculateAdaptiveElevator(baseKG: Double, inventoryCount: Int, factorPerPiece: Double = 0.1): Double {
        if (!baseKG.isFinite() || !factorPerPiece.isFinite()) return 0.0
        val count = if (inventoryCount < 0) 0 else inventoryCount
        return baseKG * (1.0 + factorPerPiece * count)
    }

    /**
     * Calculates angle-dependent gravity feedforward for a single-joint rotating arm.
     *
     * @param angleRadians Current arm angular position relative to horizontal in radians ($rad$, $0\text{ rad} = \text{horizontal}$).
     * @param kG Maximum gravity compensation constant when arm is perfectly horizontal ($V$).
     * @return Angular gravity feedforward compensation value ($V$).
     */
    fun calculateArm(angleRadians: Double, kG: Double): Double {
        if (!angleRadians.isFinite() || !kG.isFinite()) return 0.0
        return kG * cos(angleRadians)
    }
}
