package com.areslib.frc.sim.io

import com.areslib.frc.hardware.FlywheelIO
import com.areslib.frc.Dyn4jSimulation
import com.areslib.simulation.SimulationFaultKind
import com.areslib.simulation.SimulationFaultTimeline

/**
 * Simulation boundary for flywheel commands in RPM and voltage.
 *
 * Healthy velocity comes from the in-process model and voltage commands are clamped to the nominal
 * 12 V bus. An optional deterministic timeline models CAN/device/power failures while preserving
 * this FRC adapter's units and fail-closed output semantics.
 */
class SimulatedFlywheelIO(
    private val sim: Dyn4jSimulation,
    private val faultTimeline: SimulationFaultTimeline = SimulationFaultTimeline(emptyList()),
) : FlywheelIO {
    private var lastHealthyVelocityRpm = 0.0
    var lastWriteAccepted: Boolean = true
        private set

    override fun setVelocityRpm(rpm: Double) {
        setVelocityRpm(rpm, 1.0)
    }

    override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) {
        if (!beginWrite()) return
        val target = rpm.takeIf { it.isFinite() }?.coerceIn(0.0, 6000.0) ?: 0.0
        val requestedScale = maxEffortScale.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val effortScale = if (active(POWER_TARGET, SimulationFaultKind.BROWNOUT)) {
            minOf(requestedScale, BROWNOUT_OUTPUT_SCALE)
        } else {
            requestedScale
        }
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
        if (!beginWrite()) return
        val maximumVoltage = if (active(POWER_TARGET, SimulationFaultKind.BROWNOUT)) {
            12.0 * BROWNOUT_OUTPUT_SCALE
        } else {
            12.0
        }
        sim.simFlywheelVoltage = volts.takeIf { it.isFinite() }
            ?.coerceIn(-maximumVoltage, maximumVoltage) ?: 0.0
    }
    override val velocityRpm: Double
        get() {
            if (inputUnavailable() || active(DEVICE_TARGET, SimulationFaultKind.INVALID_INPUT)) return Double.NaN
            if (active(DEVICE_TARGET, SimulationFaultKind.STALE_INPUT) ||
                active(DEVICE_TARGET, SimulationFaultKind.FROZEN_INPUT)
            ) {
                return lastHealthyVelocityRpm
            }
            return sim.flywheelSim.velocityRpm.also { if (it.isFinite()) lastHealthyVelocityRpm = it }
        }
    override val velocityValid: Boolean
        get() = !inputUnavailable() &&
            !active(DEVICE_TARGET, SimulationFaultKind.INVALID_INPUT) &&
            !active(DEVICE_TARGET, SimulationFaultKind.STALE_INPUT) &&
            !active(DEVICE_TARGET, SimulationFaultKind.FROZEN_INPUT) &&
            velocityRpm.isFinite()
    override val currentAmps: Double
        get() = if (inputUnavailable() || active(DEVICE_TARGET, SimulationFaultKind.INVALID_INPUT)) {
            Double.NaN
        } else {
            sim.flywheelSim.getCurrentAmps(sim.simFlywheelVoltage)
        }
    override val tempCelsius: Double get() = 30.0

    private fun beginWrite(): Boolean {
        lastWriteAccepted = !inputUnavailable() && !active(DEVICE_TARGET, SimulationFaultKind.WRITE_REJECTED)
        if (!lastWriteAccepted) sim.simFlywheelVoltage = 0.0
        return lastWriteAccepted
    }

    private fun inputUnavailable(): Boolean =
        active(DEVICE_TARGET, SimulationFaultKind.DEVICE_DISCONNECTED) ||
            active(BUS_TARGET, SimulationFaultKind.BUS_DISCONNECTED)

    private fun active(target: String, kind: SimulationFaultKind): Boolean = faultTimeline.isActive(target, kind)

    private companion object {
        const val VELOCITY_KP_VOLTS_PER_RPM = 0.003
        const val BROWNOUT_OUTPUT_SCALE = 0.35
        const val DEVICE_TARGET = "frc.flywheel"
        const val BUS_TARGET = "frc.can2"
        const val POWER_TARGET = "frc.power"
    }
}
