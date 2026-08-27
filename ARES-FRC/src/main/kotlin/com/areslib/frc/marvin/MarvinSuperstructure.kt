package com.areslib.frc.marvin

import com.areslib.subsystem.Subsystem
import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.frc.hardware.FlywheelIO
import com.areslib.frc.hardware.CowlIO
import com.areslib.frc.hardware.IntakeIO
import com.areslib.frc.hardware.FeederIO
import com.areslib.frc.hardware.FloorIO
import com.areslib.frc.hardware.ClimberIO

/**
 * Season-specific subsystem implementation managing the Marvin 19 superstructure hardware.
 *
 * Implements [Subsystem] to register with the robot lifecycle, reading hardware sensors,
 * dispatching [SuperstructureSensorUpdate] actions, and applying voltage/closed-loop outputs
 * with brownout power scaling applied. ARESLib refreshes registered IO before [readSensors],
 * so this class consumes cached observations only; it never initiates hardware reads.
 *
 * Invalid flywheel velocity and absent/untrusted piece detection stay explicit in the
 * sensor action. Position targets retain their geometry during brownout, while velocity,
 * voltage, and closed-loop effort are scaled.
 */
class MarvinSuperstructure(
    val flywheelIO: FlywheelIO,
    val cowlIO: CowlIO,
    val intakeIO: IntakeIO,
    val feederIO: FeederIO,
    val floorIO: FloorIO,
    val climberIO: ClimberIO
) : Subsystem {

    /** Dispatches one coherent cached sensor snapshot and advances slamtake timers. */
    override fun readSensors(store: Store, timestampMs: Long) {
        val pieceDetectionValid = feederIO.pieceDetectionValid
        val pieceDetected = pieceDetectionValid && feederIO.isBeamBroken
        val flywheelVelocityValid = flywheelIO.velocityValid
        val targetFlywheelRpm = store.state.superstructure.marvin.flywheel.targetVelocityRpm
        val allFlywheelMotorsAtTarget = flywheelVelocityValid && when (flywheelIO) {
            is com.areslib.frc.hardware.FrcFlywheelPerMotorReadiness ->
                flywheelIO.allMotorsAtTarget(targetFlywheelRpm, FLYWHEEL_READY_TOLERANCE_RPM)
            else -> kotlin.math.abs(flywheelIO.velocityRpm - targetFlywheelRpm) <
                FLYWHEEL_READY_TOLERANCE_RPM
        }
        store.dispatch(SuperstructureSensorUpdate(
            flywheelRpm = flywheelIO.velocityRpm,
            flywheelVelocityValid = flywheelVelocityValid,
            flywheelAllMotorsAtTarget = allFlywheelMotorsAtTarget,
            cowlAngleRotations = cowlIO.angleRotations,
            cowlAngleValid = cowlIO.angleValid,
            intakeAngle = intakeIO.pivotAngleDegrees,
            intakeAngleValid = intakeIO.pivotAngleValid,
            pieceDetected = pieceDetected,
            pieceDetectionValid = pieceDetectionValid,
            floorVelocityRps = floorIO.velocityRps,
            climberPositionRotations = climberIO.positionRotations,
            climberPositionValid = climberIO.positionValid,
            floorCurrentAmps = floorIO.currentAmps,
            timestampMs = timestampMs
        ))
        
        val marvin = store.state.superstructure.marvin
        if (!marvin.slamtakeActive || (pieceDetectionValid && pieceDetected)) return

        val elapsed = (timestampMs - marvin.slamtakeStartTimeMs) / 1000.0
        when (marvin.slamtakePhase) {
            DEPLOYED_PHASE -> if (elapsed >= RETRACT_AT_SECONDS) {
                store.dispatch(SlamtakeTimerExpired(1, timestampMs))
            }
            RETRACTED_PHASE -> if (elapsed >= FINISH_AT_SECONDS) {
                store.dispatch(SlamtakeTimerExpired(2, timestampMs))
            }
        }
    }

    /** Emits outputs from immutable state using [scale] as effort/velocity power budget. */
    override fun writeOutputs(state: RobotState, scale: Double) {
        val marvin = state.superstructure.marvin
        if (marvin.mechanismSafetyInhibited || marvin.mechanismSafetyFaultLatched) {
            flywheelIO.setAppliedVoltage(0.0)
            cowlIO.setAppliedVoltage(0.0)
            intakeIO.setPivotVoltage(0.0)
            intakeIO.setRollerVoltage(0.0)
            feederIO.setAppliedVoltage(0.0)
            floorIO.setAppliedVoltage(0.0)
            climberIO.setAppliedVoltage(0.0)
            return
        }
        val effortScale = scale.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val flywheelTargetRpm = if (marvin.flywheelActive) {
            finiteOrZero(marvin.flywheel.targetVelocityRpm).coerceIn(0.0, MAX_FLYWHEEL_RPM) * effortScale
        } else {
            0.0
        }
        flywheelIO.setVelocityRpm(flywheelTargetRpm)
        // Position targets describe mechanism geometry and must not move when
        // brownout scaling changes. Velocity and voltage commands are scaled below.
        if (marvin.cowl.angleValid && marvin.cowl.angleRotations.isFinite()) {
            cowlIO.setTargetAngle(
                finiteOrZero(marvin.cowl.targetAngleRotations).coerceIn(0.0, MarvinConfig.cowlMaxRotations),
                effortScale
            )
        } else {
            cowlIO.setAppliedVoltage(0.0)
        }

        val climberTarget = finiteOrZero(marvin.climber.targetPositionRotations).coerceIn(
            MarvinConfig.MechanismLimits.climberMinRotations,
            MarvinConfig.MechanismLimits.climberMaxRotations
        )
        val climberVoltage = finiteOrZero(marvin.climber.targetVoltage).coerceIn(-NOMINAL_VOLTAGE, NOMINAL_VOLTAGE)
        val climberPositionValid = marvin.climber.positionValid &&
            marvin.climber.positionRotations.isFinite()
        val climberMotionRequested = when (marvin.climber.controlMode) {
            ClimberControlMode.VOLTAGE -> kotlin.math.abs(climberVoltage) > OUTPUT_EPSILON
            ClimberControlMode.POSITION_ROTATIONS ->
                !climberPositionValid ||
                    kotlin.math.abs(climberTarget - marvin.climber.positionRotations) > POSITION_EPSILON_ROTATIONS
        }
        val climberBlocksIntake = !climberPositionValid ||
            marvin.climber.positionRotations > MarvinConfig.MechanismLimits.climberClearanceRotations ||
            climberTarget > MarvinConfig.MechanismLimits.climberClearanceRotations || climberMotionRequested
        val requestedPivot = finiteOrZero(marvin.intake.targetAngleDegrees).coerceIn(
            MarvinConfig.MechanismLimits.intakeStowedDegrees,
            MarvinConfig.MechanismLimits.intakeDeployedDegrees
        )
        val safePivot = if (climberBlocksIntake) MarvinConfig.MechanismLimits.intakeStowedDegrees else requestedPivot
        if (marvin.intake.pivotAngleValid && marvin.intake.pivotAngleDegrees.isFinite()) {
            intakeIO.setPivotAngle(safePivot, effortScale)
        } else {
            intakeIO.setPivotVoltage(0.0)
        }

        val targetRollerSpeed = finiteOrZero(marvin.intake.targetRollerVelocityRps) * effortScale
        intakeIO.setRollerVelocityRps(targetRollerSpeed)

        val targetFeederSpeed = finiteOrZero(marvin.feeder.targetVelocityRps)
        feederIO.setAppliedVoltage(feederOutputVolts(targetFeederSpeed, effortScale))

        val targetFloorSpeed = finiteOrZero(marvin.floor.targetVelocityRps)
        floorIO.setAppliedVoltage(
            (FLOOR_KV_VOLTS_PER_RPS * targetFloorSpeed * effortScale).coerceIn(-NOMINAL_VOLTAGE, NOMINAL_VOLTAGE)
        )

        val intakeClear = marvin.intake.pivotAngleValid && marvin.intake.pivotAngleDegrees.isFinite() &&
            marvin.intake.pivotAngleDegrees <= MarvinConfig.MechanismLimits.intakeClearanceDegrees
        if (climberMotionRequested && (!climberPositionValid || !intakeClear)) {
            climberIO.setAppliedVoltage(0.0)
        } else {
            when (marvin.climber.controlMode) {
                ClimberControlMode.VOLTAGE -> climberIO.setAppliedVoltage(climberVoltage * effortScale)
                ClimberControlMode.POSITION_ROTATIONS -> {
                    if (climberPositionValid) {
                        climberIO.setTargetPositionRotations(climberTarget, effortScale)
                    } else {
                        climberIO.setAppliedVoltage(0.0)
                    }
                }
            }
        }
    }

    private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0

    companion object {
        private const val DEPLOYED_PHASE = 1
        private const val RETRACTED_PHASE = 2
        private const val RETRACT_AT_SECONDS = 0.5
        private const val FINISH_AT_SECONDS = 1.5

        /** Feeder feed-forward gain: applied volts per commanded output-shaft RPS. */
        const val FEEDER_KV_VOLTS_PER_RPS = 0.12

        /**
         * Simulation spin gate: the feeder is modelled as running above this |voltage|.
         * The production shot feed commands [MarvinConfig.FEEDER_SHOOT_SPEED_RPS] RPS, i.e.
         * `FEEDER_KV_VOLTS_PER_RPS * FEEDER_SHOOT_SPEED_RPS` = 1.2 V — this threshold must
         * stay below that value or the simulated robot can never launch a note through the
         * production control path. Pinned by test in Dyn4jSimulationTest.
         */
        const val FEEDER_SPIN_THRESHOLD_VOLTS = 0.5

        /** Feeder output voltage: KV feed-forward, effort-scaled and bus-limited. */
        fun feederOutputVolts(targetFeederRps: Double, effortScale: Double): Double =
            (FEEDER_KV_VOLTS_PER_RPS * targetFeederRps * effortScale).coerceIn(-NOMINAL_VOLTAGE, NOMINAL_VOLTAGE)

        private const val FLOOR_KV_VOLTS_PER_RPS = 0.12
        private const val NOMINAL_VOLTAGE = 12.0
        private const val MAX_FLYWHEEL_RPM = 6000.0
        private const val FLYWHEEL_READY_TOLERANCE_RPM = 150.0
        private const val OUTPUT_EPSILON = 1e-4
        private const val POSITION_EPSILON_ROTATIONS = 0.01
    }
}
