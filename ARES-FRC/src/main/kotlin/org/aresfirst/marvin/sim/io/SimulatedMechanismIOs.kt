package org.aresfirst.marvin.sim.io

import com.areslib.hardware.actuator.ClimberIO
import com.areslib.hardware.actuator.CowlIO
import com.areslib.hardware.actuator.FeederIO
import com.areslib.hardware.actuator.FloorIO
import com.areslib.hardware.actuator.IntakeIO
import org.aresfirst.marvin.Dyn4jSimulation
import org.aresfirst.marvin.marvin.MarvinConfig

/** Simulation boundary exposing floor voltage and mechanism velocity in rotations per second. */
class SimulatedFloorIO(private val sim: Dyn4jSimulation) : FloorIO {
    override fun setAppliedVoltage(volts: Double) {
        sim.simFloorVoltage = volts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0
    }
    override val velocityRps: Double get() = sim.simFloorVelocityRps
    override val currentAmps: Double get() = Math.abs(sim.simFloorVoltage) * 0.15
}

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

/**
 * Simulation boundary for climber mechanism rotations and voltage.
 *
 * Position control retains geometry while [setTargetPositionRotations] with an effort scale caps
 * the available voltage, matching the brownout contract of the TalonFX implementation.
 */
class SimulatedClimberIO(private val sim: Dyn4jSimulation) : ClimberIO {
    override fun setTargetPositionRotations(rotations: Double, maxEffortScale: Double) {
        val measuredRotations = sim.simClimberPositionRotations
        if (!measuredRotations.isFinite()) {
            sim.simClimberVoltage = 0.0
            return
        }
        val target = rotations.takeIf { it.isFinite() }?.coerceIn(0.0, 1.73) ?: 0.0
        val error = target - measuredRotations
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

/**
 * Simulation boundary for cowl mechanism rotations.
 *
 * The visualization model stores degrees internally and uses 32 degrees per mechanism rotation;
 * callers remain insulated from that representation through [CowlIO]. Effort-scaled position
 * commands cap voltage without changing the requested geometry.
 */
class SimulatedCowlIO(private val sim: Dyn4jSimulation) : CowlIO {
    override fun setTargetAngle(rotations: Double, maxEffortScale: Double) {
        val measuredDegrees = sim.simCowlAngle
        if (!measuredDegrees.isFinite()) {
            sim.simCowlVoltage = 0.0
            return
        }
        val targetDegrees = safeTarget(rotations) * DEGREES_PER_ROTATION
        val error = targetDegrees - measuredDegrees
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

/**
 * Simulation boundary for intake pivot degrees and roller voltage.
 *
 * Effort-scaled pivot commands cap voltage while retaining the requested angle, mirroring the
 * brownout contract of the hardware implementation.
 */
class SimulatedIntakeIO(private val sim: Dyn4jSimulation) : IntakeIO {
    override fun setPivotAngle(degrees: Double, maxEffortScale: Double) {
        val measuredDegrees = sim.intakePivotSim.angleDegrees
        if (!measuredDegrees.isFinite()) {
            sim.simIntakePivotVoltage = 0.0
            return
        }
        val error = safeTarget(degrees) - measuredDegrees
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
    override fun setRollerVelocityRps(rps: Double) {
        val safeRps = rps.takeIf { it.isFinite() }?.coerceIn(-10.0, 10.0) ?: 0.0
        setRollerVoltage(safeRps / 10.0 * 12.0)
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
