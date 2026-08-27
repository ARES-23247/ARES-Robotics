package com.areslib.frc.sim.io

import com.areslib.frc.hardware.FlywheelIO
import com.areslib.frc.Dyn4jSimulation

/**
 * Simulation boundary for flywheel commands in RPM and voltage.
 *
 * Velocity is always valid because it is read from the in-process model rather than transported
 * across CAN; voltage commands are clamped to the nominal 12 V bus.
 */
class SimulatedFlywheelIO(private val sim: Dyn4jSimulation) : FlywheelIO {
    override fun setVelocityRpm(rpm: Double) {
        setVelocityRpm(rpm, 1.0)
    }

    override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) {
        val target = rpm.takeIf { it.isFinite() }?.coerceIn(0.0, 6000.0) ?: 0.0
        val effortScale = maxEffortScale.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        if (effortScale <= 0.0) {
            sim.simFlywheelVoltage = 0.0
            return
        }
        val targetRadPerSecond = target * 2.0 * Math.PI / 60.0
        val model = sim.flywheelSim
        // At steady state, electrical torque balances viscous friction. Supplying that model-matched
        // voltage eliminates the large target droop of a P-only controller; feedback corrects
        // transient/model error and the effort cap mirrors the real brownout/current budget path.
        val modelKv = model.ke + model.frictionCoeff * model.resistance / model.kt
        val feedforwardVolts = targetRadPerSecond * modelKv
        val error = target - sim.flywheelSim.velocityRpm
        val voltageLimit = 12.0 * effortScale
        sim.simFlywheelVoltage = (feedforwardVolts + error * VELOCITY_KP_VOLTS_PER_RPM)
            .coerceIn(-voltageLimit, voltageLimit)
    }
    override fun setAppliedVoltage(volts: Double) {
        sim.simFlywheelVoltage = volts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0
    }
    override val velocityRpm: Double get() = sim.flywheelSim.velocityRpm
    override val velocityValid: Boolean get() = true
    override val currentAmps: Double get() = sim.flywheelSim.getCurrentAmps(sim.simFlywheelVoltage)
    override val tempCelsius: Double get() = 30.0

    private companion object {
        const val VELOCITY_KP_VOLTS_PER_RPM = 0.003
    }
}
