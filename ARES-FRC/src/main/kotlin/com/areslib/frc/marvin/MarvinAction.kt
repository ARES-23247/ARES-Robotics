package com.areslib.frc.marvin

import com.areslib.action.RobotAction

/** Commands the flywheel target in revolutions per minute (RPM). */
data class SetFlywheelSpeed @kotlin.jvm.JvmOverloads constructor(
    val rpm: Double,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Commands cowl position in mechanism rotations, not degrees. */
data class SetCowlAngle @kotlin.jvm.JvmOverloads constructor(
    val rotations: Double,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Enables or disables closed-loop flywheel output. */
data class SetFlywheelActive @kotlin.jvm.JvmOverloads constructor(
    val active: Boolean,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Latches whether a game-piece transfer has begun. */
/** Starts one bounded feeder transfer using the action's deterministic monotonic timestamp. */
data class StartTransfer @kotlin.jvm.JvmOverloads constructor(
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Completes or fails closed one transfer and consumes the current trigger cycle. */
data class CompleteTransfer @kotlin.jvm.JvmOverloads constructor(
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Ends the trigger cycle so a later press may authorize one new transfer. */
data class ResetTransferCycle @kotlin.jvm.JvmOverloads constructor(
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/**
 * Atomically zeros and optionally latches every drivetrain and Marvin mechanism command.
 *
 * While [inhibited] is true the reducer ignores later mechanism setpoint actions, so a
 * controller exception cannot be undone by a stale command on the next frame.
 * This action represents a temporary inhibit (for example normal Disabled entry). A distinct
 * [LatchMechanismSafetyFault] survives mode initialization until an explicit Disabled recovery.
 */
data class SetMechanismSafetyInhibit @kotlin.jvm.JvmOverloads constructor(
    val inhibited: Boolean,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/**
 * Atomically stops every output and records a fault that normal mode initialization cannot clear.
 * [reason] is retained for Driver Station and telemetry diagnosis.
 */
data class LatchMechanismSafetyFault @kotlin.jvm.JvmOverloads constructor(
    val reason: String,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/**
 * Clears only the persistent fault metadata after an explicit, health-checked Disabled recovery.
 * The temporary safety inhibit remains asserted until the platform reapplies its health policy.
 */
data class ClearMechanismSafetyFault @kotlin.jvm.JvmOverloads constructor(
    val recoverySource: String,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Replaces the simulated/estimated game-piece inventory count. */
data class SetInventoryCount @kotlin.jvm.JvmOverloads constructor(
    val count: Int,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Selects the intake's calibrated stowed (`false`) or deployed (`true`) target. */
data class SetIntakePivot @kotlin.jvm.JvmOverloads constructor(
    val deployed: Boolean,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Commands intake roller speed in revolutions per second (RPS). */
data class SetIntakeRollers @kotlin.jvm.JvmOverloads constructor(
    val speedRps: Double,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Commands feeder speed in revolutions per second (RPS). */
data class SetFeederSpeed @kotlin.jvm.JvmOverloads constructor(
    val speedRps: Double,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Commands floor-roller speed in revolutions per second (RPS). */
data class SetFloorSpeed @kotlin.jvm.JvmOverloads constructor(
    val speedRps: Double,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Selects climber voltage mode and commands volts. */
data class SetClimberVoltage @kotlin.jvm.JvmOverloads constructor(
    val volts: Double,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Selects climber position mode and commands mechanism rotations. */
data class SetClimberPositionRotations @kotlin.jvm.JvmOverloads constructor(
    val rotations: Double,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/**
 * One loop's cached Marvin mechanism observations.
 *
 * This action is created after `HardwareRegistry.refreshAll()`. Validity flags are
 * independent of numeric samples: a failed flywheel refresh or absent piece detector
 * must remain invalid even when its cached number looks plausible.
 *
 * @property flywheelRpm measured flywheel speed in RPM
 * @property cowlAngleRotations measured cowl position in mechanism rotations
 * @property intakeAngle measured intake pivot angle in degrees
 * @property pieceDetected detector state; meaningful only when [pieceDetectionValid]
 * @property flywheelVelocityValid whether flywheel RPM was refreshed successfully
 * @property flywheelAllMotorsAtTarget whether every available flywheel motor is within tolerance
 * @property cowlAngleValid whether cowl position was refreshed successfully
 * @property intakeAngleValid whether intake position was refreshed successfully
 * @property pieceDetectionValid whether a physical/configured detector exists and is trustworthy
 * @property floorVelocityRps measured floor speed in RPS
 * @property climberPositionRotations measured climber position in mechanism rotations
 * @property climberPositionValid whether climber position was refreshed successfully
 * @property floorCurrentAmps cached floor stator current in amperes
 */
data class SuperstructureSensorUpdate @kotlin.jvm.JvmOverloads constructor(
    val flywheelRpm: Double,
    val cowlAngleRotations: Double,
    val intakeAngle: Double,
    val pieceDetected: Boolean,
    val flywheelVelocityValid: Boolean = false,
    val flywheelAllMotorsAtTarget: Boolean = false,
    val cowlAngleValid: Boolean = false,
    val intakeAngleValid: Boolean = false,
    val pieceDetectionValid: Boolean = false,
    val floorVelocityRps: Double = 0.0,
    val climberPositionRotations: Double = 0.0,
    val climberPositionValid: Boolean = false,
    val floorCurrentAmps: Double = 0.0,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Starts the timed deploy/retract slamtake sequence. */
data class StartSlamtake(
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Cancels the slamtake state machine. */
data class StopSlamtake(
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction

/** Internal elapsed-time transition for slamtake phase 1 or 2. */
data class SlamtakeTimerExpired(
    val phase: Int,
    override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
) : RobotAction
