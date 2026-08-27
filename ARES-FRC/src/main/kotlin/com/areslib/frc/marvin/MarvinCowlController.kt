package com.areslib.frc.marvin

import com.areslib.Store

/** Redux facade for the cowl's mechanism-rotation target and software travel clamp. */
class MarvinCowlController(store: Store) : MarvinControllerBase(store) {

    /** Commands mechanism rotations, clamped to the same limit configured in TalonFX IO. */
    fun setCowlAngleRotations(rotations: Double) {
        val clampedRotations = rotations.coerceIn(0.0, MarvinConfig.cowlMaxRotations)
        dispatchOnChange(store.state.superstructure.marvin.cowl.targetAngleRotations, clampedRotations, ::SetCowlAngle) {}
    }

    /** True only when this loop's cowl sample is valid and within the firing tolerance. */
    fun isAngleAligned(targetRotations: Double): Boolean {
        val cowl = store.state.superstructure.marvin.cowl
        return targetRotations.isFinite() &&
            cowl.angleValid &&
            cowl.angleRotations.isFinite() &&
            kotlin.math.abs(cowl.angleRotations - targetRotations) <= COWL_READY_TOLERANCE_ROTATIONS
    }

    private companion object {
        const val COWL_READY_TOLERANCE_ROTATIONS = 0.05
    }
}
