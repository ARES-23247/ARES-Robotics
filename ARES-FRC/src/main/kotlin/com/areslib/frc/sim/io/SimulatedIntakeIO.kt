package com.areslib.frc.sim.io

import com.areslib.hardware.actuator.IntakeIO
import com.areslib.frc.Dyn4jSimulation
import com.areslib.frc.marvin.MarvinConfig

/**
 * Simulation boundary for intake pivot degrees and roller voltage.
 *
 * Effort-scaled pivot commands cap voltage while retaining the requested angle, mirroring the
 * brownout contract of the hardware implementation.
 */
class SimulatedIntakeIO(private val sim: Dyn4jSimulation) : IntakeIO {
    override fun setPivotAngle(degrees: Double) {
        val error = safeTarget(degrees) - sim.intakePivotSim.angleDegrees
        sim.simIntakePivotVoltage = (error * 0.4).coerceIn(-12.0, 12.0)
    }
    override fun setPivotAngle(degrees: Double, maxEffortScale: Double) {
        val error = safeTarget(degrees) - sim.intakePivotSim.angleDegrees
        val safeScale = maxEffortScale.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val maxVolts = 12.0 * safeScale
        sim.simIntakePivotVoltage = (error * 0.4).coerceIn(-maxVolts, maxVolts)
    }
    override fun setPivotVoltage(volts: Double) {
        sim.simIntakePivotVoltage = volts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0
    }
    override fun setRollerVoltage(volts: Double) {
        sim.simIntakeRollerVoltage = volts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0
    }
    override val pivotAngleDegrees: Double get() = sim.intakePivotSim.angleDegrees
    override val pivotAngleValid: Boolean get() = pivotAngleDegrees.isFinite()
    override val pivotCurrentAmps: Double get() = Math.abs(sim.simIntakePivotVoltage) * 0.3
    override val rollerCurrentAmps: Double get() = Math.abs(sim.simIntakeRollerVoltage) * 0.2

    private fun safeTarget(degrees: Double): Double = degrees.takeIf { it.isFinite() }?.coerceIn(
        MarvinConfig.MechanismLimits.intakeStowedDegrees,
        MarvinConfig.MechanismLimits.intakeDeployedDegrees
    ) ?: MarvinConfig.MechanismLimits.intakeStowedDegrees
}
