package org.aresfirst.marvin.sim.io

import com.areslib.hardware.actuator.FeederIO
import org.aresfirst.marvin.Dyn4jSimulation

/**
 * Simulation boundary for feeder voltage and optional piece detection.
 *
 * When [detectorConfigured] is false, [pieceDetectionValid] is false and `isBeamBroken == false`
 * must not be interpreted as a trusted no-piece observation. This matches fail-closed hardware
 * behavior when no detector is installed or its sample is stale.
 */
class SimulatedFeederIO(
    private val sim: Dyn4jSimulation,
    private val detectorConfigured: Boolean = false
) : FeederIO {
    override fun setAppliedVoltage(volts: Double) {
        sim.simFeederVoltage = volts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0
    }
    override val isBeamBroken: Boolean get() = detectorConfigured && sim.simFeederPieceDetected
    override val pieceDetectionValid: Boolean get() = detectorConfigured
    override val currentAmps: Double get() = Math.abs(sim.simFeederVoltage) * 0.1
}
