package org.aresfirst.marvin.sim.io

import com.areslib.hardware.actuator.CowlIO
import org.aresfirst.marvin.Dyn4jSimulation
import org.aresfirst.marvin.marvin.MarvinConfig

/**
 * Simulation boundary for cowl mechanism rotations.
 *
 * The visualization model stores degrees internally and uses 32 degrees per mechanism rotation;
 * callers remain insulated from that representation through [CowlIO]. Effort-scaled position
 * commands cap voltage without changing the requested geometry.
 */
class SimulatedCowlIO(private val sim: Dyn4jSimulation) : CowlIO {
    override fun setTargetAngle(rotations: Double, maxEffortScale: Double) {
        val targetDegrees = safeTarget(rotations) * DEGREES_PER_ROTATION
        val error = targetDegrees - sim.simCowlAngle
        val safeScale = maxEffortScale.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val maxVolts = 12.0 * safeScale
        sim.simCowlVoltage = (error * 0.5).coerceIn(-maxVolts, maxVolts)
    }
    override fun setAppliedVoltage(volts: Double) {
        sim.simCowlVoltage = volts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0
    }
    override val angleRotations: Double get() = sim.simCowlAngle / DEGREES_PER_ROTATION
    override val angleValid: Boolean get() = angleRotations.isFinite()
    override val currentAmps: Double get() = Math.abs(sim.simCowlVoltage) * 0.2

    private fun safeTarget(rotations: Double): Double =
        rotations.takeIf { it.isFinite() }?.coerceIn(0.0, MarvinConfig.cowlMaxRotations) ?: 0.0

    private companion object {
        const val DEGREES_PER_ROTATION = 32.0
    }
}
