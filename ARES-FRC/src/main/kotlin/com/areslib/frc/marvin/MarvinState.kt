package com.areslib.frc.marvin

import com.areslib.state.SubsystemState
import com.areslib.state.SuperstructureState

/**
 * Immutable flywheel state.
 *
 * @property velocityRpm measured speed in RPM; zeroed by the reducer when invalid
 * @property velocityValid whether all master/follower velocity signals refreshed successfully
 * @property allMotorsAtTarget whether every available motor is individually within shot tolerance
 * @property targetVelocityRpm commanded speed in RPM
 * @property currentAmps cached aggregate current in amperes when supplied
 * @property tempCelsius cached mechanism temperature when supplied
 */
data class FlywheelState(
    val velocityRpm: Double = 0.0,
    /** Whether the velocity observation is fresh and valid this loop. */
    val velocityValid: Boolean = false,
    val allMotorsAtTarget: Boolean = false,
    val targetVelocityRpm: Double = 0.0,
    val currentAmps: Double = 0.0,
    val tempCelsius: Double = 0.0
)

/**
 * Immutable cowl state; both position fields are mechanism rotations, never degrees.
 */
data class CowlState(
    val angleRotations: Double = 0.0,
    val angleValid: Boolean = false,
    val targetAngleRotations: Double = 0.0,
    val currentAmps: Double = 0.0
)

/**
 * Immutable intake state. Pivot fields are degrees and roller fields are RPS.
 */
data class IntakeState(
    val pivotAngleDegrees: Double = 0.0,
    val pivotAngleValid: Boolean = false,
    val targetAngleDegrees: Double = 0.0,
    val rollerVelocityRps: Double = 0.0,
    val targetRollerVelocityRps: Double = 0.0,
    val isDeployed: Boolean = false
)

/**
 * Immutable feeder/transfer state.
 *
 * [gamePieceDetected] is meaningful only while [pieceDetectionValid] is true.
 * [previousGamePieceDetected] retains the last trusted edge across invalid intervals so
 * detector recovery cannot double-count the same held piece.
 */
data class FeederState(
    val velocityRps: Double = 0.0,
    val targetVelocityRps: Double = 0.0,
    val gamePieceDetected: Boolean = false,
    /** Whether a real or explicitly configured simulated detector exists. */
    val pieceDetectionValid: Boolean = false,
    val previousGamePieceDetected: Boolean = false
)

/** Selects which climber target field [MarvinSuperstructure] writes this loop. */
enum class ClimberControlMode {
    /** Open-loop voltage using [ClimberState.targetVoltage]. */
    VOLTAGE,

    /** Closed-loop mechanism rotations using [ClimberState.targetPositionRotations]. */
    POSITION_ROTATIONS
}

/**
 * Immutable climber state. Position fields are mechanism rotations after the 80:1 ratio.
 * Inactive-mode targets are retained; [controlMode] alone selects which one is emitted.
 */
data class ClimberState(
    /** Measured mechanism position in rotations. */
    val positionRotations: Double = 0.0,
    /** Whether the measured position is fresh and valid this loop. */
    val positionValid: Boolean = false,
    /** Closed-loop mechanism target in rotations. */
    val targetPositionRotations: Double = 0.0,
    val currentAmps: Double = 0.0,
    val targetVoltage: Double = 0.0,
    val controlMode: ClimberControlMode = ClimberControlMode.VOLTAGE
)

/**
 * Immutable floor-roller state. Velocity fields are revolutions per second (RPS).
 */
data class FloorState(
    val velocityRps: Double = 0.0,
    val targetVelocityRps: Double = 0.0,
    val currentAmps: Double = 0.0
)

/**
 * Complete immutable Marvin XIX superstructure slice stored in [SuperstructureState.custom].
 *
 * Command fields are intent; measured fields come only from the loop's cached
 * [SuperstructureSensorUpdate]. [flywheelActive] gates motor output, while
 * [transferActive] records an already-started feed so it may finish atomically.
 */
data class MarvinState(
    val flywheel: FlywheelState = FlywheelState(),
    val cowl: CowlState = CowlState(),
    val intake: IntakeState = IntakeState(),
    val feeder: FeederState = FeederState(),
    val climber: ClimberState = ClimberState(),
    val floor: FloorState = FloorState(),
    val slamtakeActive: Boolean = false,
    val slamtakeStartTimeMs: Long = 0L,
    /**
     * Monotonic slamtake phase counter advanced by elapsed-time thresholds in
     * MarvinSuperstructure.readSensors. Phases: 0 = inactive, 1 = deployed (intake
     * out), 2 = retracted (intake stowed). Using a counter instead of inferring the
     * phase from the intake pivot angle means a skipped [0.5,1.5)s window (loop stall,
     * GC, exception) can no longer deadlock the sequence.
     */
    val slamtakePhase: Int = 0,
    val flywheelActive: Boolean = false,
    val transferActive: Boolean = false,
    /** Monotonic start time supplied by [StartTransfer], or `-1` while inactive. */
    val transferStartedAtMs: Long = -1L,
    /** Prevents one held trigger cycle from authorizing repeated transfers. */
    val transferConsumedForTrigger: Boolean = false,
    /** Immediate output inhibit, including the normal temporary Disabled stop. */
    val mechanismSafetyInhibited: Boolean = false,
    /** Persistent fault latch; mode initialization cannot clear this flag. */
    val mechanismSafetyFaultLatched: Boolean = false,
    /** First/current persistent fault reason for Driver Station diagnosis. */
    val mechanismSafetyFaultReason: String = "",
    val inventoryCount: Int = 0
) : SubsystemState {
    /**
     * Fail-closed readiness gate: requires a fresh observation, nontrivial target and
     * measured speed, and less than 150 RPM absolute error.
     */
    val isFlywheelAtSpeed: Boolean
        get() = flywheel.velocityValid && flywheel.allMotorsAtTarget &&
            flywheel.targetVelocityRpm > 100.0 && flywheel.velocityRpm > 100.0 &&
            Math.abs(flywheel.velocityRpm - flywheel.targetVelocityRpm) < 150.0

    fun withFlywheelSpeed(rpm: Double) = copy(
        flywheel = flywheel.copy(targetVelocityRpm = rpm, allMotorsAtTarget = false)
    )
    fun withCowlAngle(rotations: Double) = copy(cowl = cowl.copy(targetAngleRotations = rotations))
    fun withIntakePivot(deployed: Boolean) = copy(intake = intake.copy(
        isDeployed = deployed,
        targetAngleDegrees = if (deployed) 90.0 else 0.0
    ))
    fun withIntakeRollers(speedRps: Double) = copy(intake = intake.copy(targetRollerVelocityRps = speedRps))
    fun withFeederSpeed(speedRps: Double) = copy(feeder = feeder.copy(targetVelocityRps = speedRps))
    fun withFloorSpeed(speedRps: Double) = copy(floor = floor.copy(targetVelocityRps = speedRps))
    fun withClimberVoltage(volts: Double) = copy(climber = climber.copy(
        targetVoltage = volts,
        controlMode = ClimberControlMode.VOLTAGE
    ))

    fun withClimberPositionRotations(rotations: Double) = copy(climber = climber.copy(
        targetPositionRotations = rotations.takeIf { it.isFinite() }?.coerceIn(
            MarvinConfig.MechanismLimits.climberMinRotations,
            MarvinConfig.MechanismLimits.climberMaxRotations
        ) ?: MarvinConfig.MechanismLimits.climberMinRotations,
        controlMode = ClimberControlMode.POSITION_ROTATIONS
    ))
}

/**
 * Retrieves the Marvin slice from the extensible platform state.
 *
 * The fallback supports generic/default [SuperstructureState] instances in tests and
 * startup code; production [ARESRobot][com.areslib.frc.ARESRobot] installs MarvinState.
 */
val SuperstructureState.marvin: MarvinState
    get() = this.custom as? MarvinState ?: MarvinState()
