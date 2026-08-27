package com.areslib.frc.marvin

import com.areslib.Store

/** Redux facade for RPM commands and the fail-closed flywheel readiness gate. */
class MarvinFlywheelController(store: Store) : MarvinControllerBase(store) {

    /** Enables flywheel output and records [targetRpm] in RPM. */
    fun spinUp(targetRpm: Double) {
        dispatchOnChange(store.state.superstructure.marvin.flywheel.targetVelocityRpm, targetRpm, ::SetFlywheelSpeed) {}
        dispatchOnChange(store.state.superstructure.marvin.flywheelActive, true, ::SetFlywheelActive) {}
    }

    /** Clears both the velocity target and active-output latch. */
    fun stop() {
        dispatchOnChange(store.state.superstructure.marvin.flywheel.targetVelocityRpm, 0.0, ::SetFlywheelSpeed) {}
        dispatchOnChange(store.state.superstructure.marvin.flywheelActive, false, ::SetFlywheelActive) {}
    }

    /** True only for a fresh sample within 150 RPM of a nontrivial target. */
    fun isRpmAligned(targetRpm: Double): Boolean {
        val flywheel = store.state.superstructure.marvin.flywheel
        return flywheel.velocityValid && flywheel.allMotorsAtTarget && targetRpm > 100.0 &&
            kotlin.math.abs(flywheel.velocityRpm - targetRpm) < 150.0
    }
}
