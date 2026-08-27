package com.areslib.frc.sim.io

import com.areslib.frc.hardware.FloorIO
import com.areslib.frc.Dyn4jSimulation

/** Simulation boundary exposing floor voltage and mechanism velocity in rotations per second. */
class SimulatedFloorIO(private val sim: Dyn4jSimulation) : FloorIO {
    override fun setAppliedVoltage(volts: Double) {
        sim.simFloorVoltage = volts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0
    }
    override val velocityRps: Double get() = sim.simFloorVelocityRps
    override val currentAmps: Double get() = Math.abs(sim.simFloorVoltage) * 0.15
}
