package org.aresfirst.marvin.sim.io

import com.areslib.hardware.actuator.ClimberIO
import org.aresfirst.marvin.Dyn4jSimulation

/**
 * Simulation boundary for climber mechanism rotations and voltage.
 *
 * Position control retains geometry while [setTargetPositionRotations] with an effort scale caps
 * the available voltage, matching the brownout contract of the TalonFX implementation.
 */
class SimulatedClimberIO(private val sim: Dyn4jSimulation) : ClimberIO {
    override fun setTargetPositionRotations(rotations: Double, maxEffortScale: Double) {
        val target = rotations.takeIf { it.isFinite() }?.coerceIn(0.0, 1.73) ?: 0.0
        val error = target - sim.simClimberPositionRotations
        val maxVolts = 12.0 * (maxEffortScale.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0)
        sim.simClimberVoltage = (error * 10.0).coerceIn(-maxVolts, maxVolts)
    }
    override fun setAppliedVoltage(volts: Double) {
        sim.simClimberVoltage = volts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0
    }
    override val positionRotations: Double get() = sim.simClimberPositionRotations
    override val positionValid: Boolean get() = positionRotations.isFinite()
    override val currentAmps: Double get() = Math.abs(sim.simClimberVoltage) * 0.25
}
