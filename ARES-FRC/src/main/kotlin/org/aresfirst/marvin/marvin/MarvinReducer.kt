package org.aresfirst.marvin.marvin

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.reducer.rootReducer

/**
 * Redux Reducer responsible for managing the Marvin superstructure state transitions.
 *
 * Composes over the core [rootReducer] (which handles drive, vision, pathing, costmap,
 * and the generic FSM) and then applies Marvin-specific state updates for each season
 * action. The reducer is pure: it records commanded targets and cached sensor observations but
 * performs no IO. Physical bounds (e.g. joint travel limits) are enforced downstream by controller facades
 * (e.g. [MarvinCowlController]) and by TalonFX soft limits in the hardware IO layer, not
 * here.
 *
 * **Physical Units & Conventions:**
 * - Angles: Degrees ($^\circ$) for intake and rotations for cowl.
 * - Climber position: Mechanism rotations.
 * - Velocities: RPM for flywheel, RPS for rollers/feeders.
 *
 * Sensor observations use deadbands to avoid copying the state tree for insignificant
 * changes. Freshness transitions always bypass those numeric deadbands so invalid data
 * cannot remain authoritative.
 */
object MarvinReducer {

    /** Applies the core reducer first, then the Marvin-specific state transition. */
    fun reduce(state: RobotState, action: RobotAction): RobotState {
        // First run standard core reducer (handles drive, vision, path, costmap, and generic FSM)
        var nextState = rootReducer(state, action)

        // Then apply Marvin specific state updates
        val currentMarvin = nextState.superstructure.marvin
        val nextMarvin = when {
            action is LatchMechanismSafetyFault -> currentMarvin.withAllOutputsStopped(
                faultLatched = true,
                faultReason = if (
                    currentMarvin.mechanismSafetyFaultLatched &&
                    currentMarvin.mechanismSafetyFaultReason.isNotBlank()
                ) {
                    currentMarvin.mechanismSafetyFaultReason
                } else {
                    action.reason.ifBlank { "Unspecified mechanism safety fault" }
                }
            )
            action is ClearMechanismSafetyFault -> currentMarvin.copy(
                mechanismSafetyInhibited = true,
                mechanismSafetyFaultLatched = false,
                mechanismSafetyFaultReason = ""
            )
            action is SetMechanismSafetyInhibit -> when {
                action.inhibited -> currentMarvin.withAllOutputsStopped()
                currentMarvin.mechanismSafetyFaultLatched -> null
                else -> currentMarvin.copy(mechanismSafetyInhibited = false)
            }
            (currentMarvin.mechanismSafetyInhibited || currentMarvin.mechanismSafetyFaultLatched) &&
                action.isMechanismSetpointAction() -> null
            else -> when (action) {
            is SetFlywheelSpeed -> currentMarvin.withFlywheelSpeed(action.rpm)
            is SetCowlAngle -> currentMarvin.withCowlAngle(action.rotations)
            is SetIntakePivot -> currentMarvin.withIntakePivot(action.deployed)
            is SetIntakeRollers -> currentMarvin.withIntakeRollers(action.speedRps).copy(slamtakeActive = false, slamtakePhase = 0)
            is SetFeederSpeed -> currentMarvin.withFeederSpeed(action.speedRps)
            is SetFloorSpeed -> currentMarvin.withFloorSpeed(action.speedRps)
            is SetClimberVoltage -> currentMarvin.withClimberVoltage(action.volts)
            is SetFlywheelActive -> currentMarvin.copy(
                flywheelActive = action.active,
                flywheel = if (action.active) {
                    currentMarvin.flywheel
                } else {
                    currentMarvin.flywheel.copy(allMotorsAtTarget = false)
                }
            )
            is StartTransfer -> if (
                currentMarvin.transferActive || currentMarvin.transferConsumedForTrigger
            ) {
                null
            } else {
                currentMarvin.copy(
                    transferActive = true,
                    transferStartedAtMs = action.timestampMs
                )
            }
            is CompleteTransfer -> currentMarvin.copy(
                transferActive = false,
                transferStartedAtMs = -1L,
                transferConsumedForTrigger = true
            )
            is ResetTransferCycle -> currentMarvin.copy(
                transferActive = false,
                transferStartedAtMs = -1L,
                transferConsumedForTrigger = false
            )
            is SetInventoryCount -> currentMarvin.copy(inventoryCount = action.count.coerceIn(0, MarvinConfig.INVENTORY_CAPACITY))
            is SetClimberPositionRotations -> currentMarvin.withClimberPositionRotations(action.rotations)
            is StartSlamtake -> {
                currentMarvin.copy(
                    slamtakeActive = true,
                    slamtakePhase = 1,
                    slamtakeStartTimeMs = action.timestampMs,
                    intake = currentMarvin.intake.copy(isDeployed = true, targetAngleDegrees = 90.0, targetRollerVelocityRps = 10.0),
                    floor = currentMarvin.floor.copy(targetVelocityRps = 10.0),
                    feeder = currentMarvin.feeder.copy(targetVelocityRps = 0.0),
                    transferActive = false,
                    transferStartedAtMs = -1L,
                    transferConsumedForTrigger = false
                )
            }
            is StopSlamtake -> {
                currentMarvin.copy(
                    slamtakeActive = false,
                    slamtakePhase = 0,
                    intake = currentMarvin.intake.copy(targetRollerVelocityRps = 0.0),
                    floor = currentMarvin.floor.copy(targetVelocityRps = 0.0),
                    feeder = currentMarvin.feeder.copy(targetVelocityRps = 0.0),
                    transferActive = false,
                    transferStartedAtMs = -1L,
                    transferConsumedForTrigger = false
                )
            }
            is SlamtakeTimerExpired -> {
                if (!currentMarvin.slamtakeActive || action.timestampMs < currentMarvin.slamtakeStartTimeMs ||
                    action.phase !in 1..2 || (action.phase == 1 && currentMarvin.slamtakePhase != 1)) {
                    null
                } else if (action.phase == 1) {
                    currentMarvin.copy(
                        slamtakePhase = 2,
                        intake = currentMarvin.intake.copy(isDeployed = false, targetAngleDegrees = 0.0, targetRollerVelocityRps = 10.0),
                        floor = currentMarvin.floor.copy(targetVelocityRps = 10.0),
                        feeder = currentMarvin.feeder.copy(targetVelocityRps = 0.0)
                    )
                } else {
                    currentMarvin.copy(
                        slamtakeActive = false,
                        slamtakePhase = 0,
                        intake = currentMarvin.intake.copy(targetRollerVelocityRps = 0.0),
                        floor = currentMarvin.floor.copy(targetVelocityRps = 0.0)
                    )
                }
            }
            is SuperstructureSensorUpdate -> {
                var flywheel = currentMarvin.flywheel
                var cowl = currentMarvin.cowl
                var intake = currentMarvin.intake
                var feeder = currentMarvin.feeder
                var floor = currentMarvin.floor
                var climber = currentMarvin.climber
                var inventory = currentMarvin.inventoryCount.coerceIn(0, MarvinConfig.INVENTORY_CAPACITY)

                val flywheelValid = action.flywheelVelocityValid && action.flywheelRpm.isFinite()
                val flywheelRpm = if (flywheelValid) action.flywheelRpm else 0.0
                val atTarget = flywheelValid && action.flywheelAllMotorsAtTarget
                if (sampleChanged(flywheel.velocityRpm, flywheelRpm, 2.0) ||
                    flywheel.velocityValid != flywheelValid || flywheel.allMotorsAtTarget != atTarget) {
                    flywheel = flywheel.copy(velocityRpm = flywheelRpm, velocityValid = flywheelValid, allMotorsAtTarget = atTarget)
                }
                val cowlValid = action.cowlAngleValid && action.cowlAngleRotations.isFinite()
                val cowlAngle = if (cowlValid) action.cowlAngleRotations else 0.0
                if (sampleChanged(cowl.angleRotations, cowlAngle, 0.005) || cowl.angleValid != cowlValid) {
                    cowl = cowl.copy(angleRotations = cowlAngle, angleValid = cowlValid)
                }
                val intakeValid = action.intakeAngleValid && action.intakeAngle.isFinite()
                val intakeAngle = if (intakeValid) action.intakeAngle else 0.0
                if (sampleChanged(intake.pivotAngleDegrees, intakeAngle, 0.005) || intake.pivotAngleValid != intakeValid) {
                    intake = intake.copy(pivotAngleDegrees = intakeAngle, pivotAngleValid = intakeValid)
                }
                if (!action.pieceDetectionValid) {
                    if (feeder.pieceDetectionValid || feeder.gamePieceDetected) {
                        feeder = feeder.copy(gamePieceDetected = false,
                            previousGamePieceDetected = feeder.gamePieceDetected, pieceDetectionValid = false)
                    }
                } else if (!feeder.pieceDetectionValid || feeder.gamePieceDetected != action.pieceDetected) {
                    // Preserve the last trusted edge across invalid intervals; recovery cannot recount a held piece.
                    val wasDetected = if (feeder.pieceDetectionValid) feeder.gamePieceDetected else feeder.previousGamePieceDetected
                    feeder = feeder.copy(gamePieceDetected = action.pieceDetected,
                        previousGamePieceDetected = wasDetected, pieceDetectionValid = true)
                    if (!wasDetected && action.pieceDetected) {
                        inventory = (inventory + 1).coerceAtMost(MarvinConfig.INVENTORY_CAPACITY)
                    } else if (wasDetected && !action.pieceDetected && currentMarvin.transferActive) {
                        inventory = (inventory - 1).coerceAtLeast(0)
                    }
                }
                val velocity = action.floorVelocityRps.takeIf { it.isFinite() } ?: 0.0
                val current = action.floorCurrentAmps.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                val velocityChanged = sampleChanged(floor.velocityRps, velocity, 0.005)
                val currentChanged = sampleChanged(floor.currentAmps, current, 0.05)
                if (velocityChanged || currentChanged) {
                    floor = floor.copy(velocityRps = if (velocityChanged) velocity else floor.velocityRps,
                        currentAmps = if (currentChanged) current else floor.currentAmps)
                }
                val climberValid = action.climberPositionValid && action.climberPositionRotations.isFinite()
                val climberPosition = if (climberValid) action.climberPositionRotations else 0.0
                if (sampleChanged(climber.positionRotations, climberPosition, 0.005) || climber.positionValid != climberValid) {
                    climber = climber.copy(positionRotations = climberPosition, positionValid = climberValid)
                }
                val stopSlamtake = currentMarvin.slamtakeActive && action.pieceDetectionValid && action.pieceDetected
                if (stopSlamtake) {
                    intake = intake.copy(targetRollerVelocityRps = 0.0)
                    floor = floor.copy(targetVelocityRps = 0.0)
                    feeder = feeder.copy(targetVelocityRps = 0.0)
                }
                if (flywheel === currentMarvin.flywheel && cowl === currentMarvin.cowl &&
                    intake === currentMarvin.intake && feeder === currentMarvin.feeder &&
                    floor === currentMarvin.floor && climber === currentMarvin.climber &&
                    inventory == currentMarvin.inventoryCount && !stopSlamtake) {
                    currentMarvin
                } else {
                    currentMarvin.copy(flywheel = flywheel, cowl = cowl, intake = intake, feeder = feeder,
                        floor = floor, climber = climber, inventoryCount = inventory,
                        slamtakeActive = if (stopSlamtake) false else currentMarvin.slamtakeActive,
                        slamtakePhase = if (stopSlamtake) 0 else currentMarvin.slamtakePhase,
                        transferActive = if (stopSlamtake) false else currentMarvin.transferActive)
                }
            }
            else -> null
            }
        }

        val outputsInhibited = nextMarvin?.let {
            it.mechanismSafetyInhibited || it.mechanismSafetyFaultLatched
        } ?: (currentMarvin.mechanismSafetyInhibited || currentMarvin.mechanismSafetyFaultLatched)
        if (outputsInhibited && nextState.drive.let {
                it.xVelocityMetersPerSecond != 0.0 || it.yVelocityMetersPerSecond != 0.0 ||
                    it.angularVelocityRadiansPerSecond != 0.0 || it.driveMode != com.areslib.state.DriveMode.X_BRAKE ||
                    it.headingLockTargetRadians != null || it.positionLockX != null || it.positionLockY != null || !it.isXLock
            }) {
            nextState = nextState.copy(
                drive = nextState.drive.copy(
                    xVelocityMetersPerSecond = 0.0,
                    yVelocityMetersPerSecond = 0.0,
                    angularVelocityRadiansPerSecond = 0.0,
                    driveMode = com.areslib.state.DriveMode.X_BRAKE,
                    headingLockTargetRadians = null,
                    positionLockX = null,
                    positionLockY = null,
                    isXLock = true
                )
            )
        }

        if (nextMarvin != null && nextMarvin !== currentMarvin) {
            nextState = nextState.copy(
                superstructure = nextState.superstructure.copy(custom = nextMarvin)
            )
        }

        return nextState
    }

    private fun sampleChanged(current: Double, next: Double, tolerance: Double): Boolean =
        !current.isFinite() || kotlin.math.abs(current - next) > tolerance

    private fun RobotAction.isMechanismSetpointAction(): Boolean = when (this) {
        is SetFlywheelSpeed,
        is SetCowlAngle,
        is SetIntakePivot,
        is SetIntakeRollers,
        is SetFeederSpeed,
        is SetFloorSpeed,
        is SetClimberVoltage,
        is SetClimberPositionRotations,
        is SetFlywheelActive,
        is StartTransfer,
        is CompleteTransfer,
        is ResetTransferCycle,
        is StartSlamtake,
        is SlamtakeTimerExpired -> true
        else -> false
    }

    private fun MarvinState.withAllOutputsStopped(
        faultLatched: Boolean = mechanismSafetyFaultLatched,
        faultReason: String = mechanismSafetyFaultReason
    ): MarvinState = copy(
        flywheel = flywheel.copy(
            targetVelocityRpm = 0.0,
            allMotorsAtTarget = false
        ),
        cowl = cowl.copy(targetAngleRotations = 0.0),
        intake = intake.copy(
            targetAngleDegrees = 0.0,
            targetRollerVelocityRps = 0.0,
            isDeployed = false
        ),
        feeder = feeder.copy(targetVelocityRps = 0.0),
        climber = climber.copy(
            targetPositionRotations = 0.0,
            targetVoltage = 0.0,
            controlMode = ClimberControlMode.VOLTAGE
        ),
        floor = floor.copy(targetVelocityRps = 0.0),
        slamtakeActive = false,
        slamtakePhase = 0,
        flywheelActive = false,
        transferActive = false,
        transferStartedAtMs = -1L,
        transferConsumedForTrigger = true,
        mechanismSafetyInhibited = true,
        mechanismSafetyFaultLatched = faultLatched,
        mechanismSafetyFaultReason = faultReason
    )
}
