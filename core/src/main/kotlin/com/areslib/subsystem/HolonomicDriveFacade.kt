package com.areslib.subsystem

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.filter.LowPassFilter
import com.areslib.pathing.Path
import com.areslib.math.wrapAngle
import com.areslib.control.feedback.PIDController
import com.areslib.control.tuning.PIDFCoefficients

/**
 * Shared base class containing common mathematical algorithms and properties for holonomic drive facades.
 * Standardizes joystick driving, field-relative coordinate rotations, heading locking, and path following.
 */
abstract class HolonomicDriveFacade @kotlin.jvm.JvmOverloads constructor(
    protected val store: Store,
    headingGains: PIDFCoefficients = PIDFCoefficients(1.8, 0.0, 0.08),
    headingDeadzoneDeg: Double = 1.0
) {
    /**
     * The maximum linear speed of the robot in meters per second.
     * Used to normalize angular velocity output for heading hold PID.
     */
    var maxSpeedMps: Double = 3.5

    /**
     * The maximum angular speed of the robot in radians per second.
     * Used to normalize angular velocity output for heading hold PID.
     */
    var maxAngularSpeedRps: Double = 9.5

    /**
     * The current estimated longitudinal (X-axis) velocity of the robot on the field in meters per second.
     */
    val xVelocity: Double
        get() = store.state.drive.xVelocityMetersPerSecond

    /**
     * The current estimated lateral (Y-axis) velocity of the robot on the field in meters per second.
     */
    val yVelocity: Double
        get() = store.state.drive.yVelocityMetersPerSecond

    /**
     * The current estimated angular velocity of the robot in radians per second.
     */
    val angularVelocity: Double
        get() = store.state.drive.measuredAngularVelocityRadiansPerSecond

    /**
     * The current 2D spatial pose of the robot ([Pose2d]) on the coordinate field, estimated via EKF.
     */
    val pose: Pose2d
        get() = store.state.drive.poseEstimator.estimatedPose

    /**
     * The raw X coordinate of the odometry system computer in meters.
     */
    val odometryX: Double
        get() = store.state.drive.odometryX

    /**
     * The raw Y coordinate of the odometry system computer in meters.
     */
    val odometryY: Double
        get() = store.state.drive.odometryY

    /**
     * The raw heading of the odometry system computer in radians.
     */
    val odometryHeading: Double
        get() = store.state.drive.odometryHeading

    protected val headingPID = com.areslib.control.feedback.PIDController(headingGains.kP, headingGains.kI, headingGains.kD).apply {
        enableContinuousInput(-Math.PI, Math.PI)
        setOutputLimits(-2.0, 2.0)
        deadzone = Math.toRadians(headingDeadzoneDeg)
    }

    protected val headingErrorFilter = com.areslib.math.filter.LowPassFilter(0.0)

    /** Pre-allocated PID controller for position hold X-axis correction (field-relative). */
    protected val positionPidX = com.areslib.control.feedback.PIDController(1.5, 0.0, 0.1).apply {
        setOutputLimits(-1.4, 1.4)  // ~40% of maxSpeedMps
        deadzone = 0.02
    }

    /** Pre-allocated PID controller for position hold Y-axis correction (field-relative). */
    protected val positionPidY = com.areslib.control.feedback.PIDController(1.5, 0.0, 0.1).apply {
        setOutputLimits(-1.4, 1.4)
        deadzone = 0.02
    }

    private val reusableDriveIntent = com.areslib.action.RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0)

    /**
     * Executes robot-relative drivetrain movement effort.
     *
     * Coordinates are specified relative to the robot's local frame.
     *
     * @param vx Longitudinal velocity effort scaled between [-1.0, 1.0].
     * @param vy Lateral strafe velocity effort scaled between [-1.0, 1.0].
     * @param omega Angular rotational velocity effort scaled between [-1.0, 1.0].
     */
    fun robotRelativeDrive(vx: Double, vy: Double, omega: Double, fromHeadingHold: Boolean = false) {
        reusableDriveIntent.targetXVelocity = vx
        reusableDriveIntent.targetYVelocity = vy
        reusableDriveIntent.targetAngularVelocity = omega
        reusableDriveIntent.isFieldCentric = false
        reusableDriveIntent.timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
        reusableDriveIntent.fromHeadingHold = fromHeadingHold
        reusableDriveIntent.isXLock = false
        store.dispatch(reusableDriveIntent)
    }

    /**
     * Executes field-relative drivetrain movement effort.
     *
     * Coordinates are translated automatically using the current EKF-estimated heading
     * to keep controls consistent regardless of which way the robot is facing.
     *
     * @param vx Field-centric X-axis velocity effort scaled between [-1.0, 1.0].
     * @param vy Field-centric Y-axis velocity effort scaled between [-1.0, 1.0].
     * @param omega Angular rotational velocity effort scaled between [-1.0, 1.0].
     * @param useHeadingLock Enables active IMU closed-loop heading lock to stabilize the robot's orientation.
     * @param usePositionHold Enables active EKF closed-loop position hold when joystick inputs are released.
     * @param dtSeconds Timestep delta duration in seconds.
     */
    fun fieldRelativeDrive(vx: Double, vy: Double, omega: Double, useHeadingLock: Boolean = false, usePositionHold: Boolean = false, dtSeconds: Double = 0.02) {
        val headingRad = pose.heading.radians
        val cos = kotlin.math.cos(headingRad)
        val sin = kotlin.math.sin(headingRad)

        // Translate field-relative to robot-relative velocities
        var finalRobotVx = vx * cos + vy * sin
        var finalRobotVy = -vx * sin + vy * cos

        var finalOmega = omega
        var fromHeadingHold = false
        
        val isRotating = kotlin.math.abs(omega) > 0.05
        val target = store.state.drive.headingLockTargetRadians
        val driveMode = store.state.drive.driveMode

        when {
            !useHeadingLock && target != null -> {
                store.dispatch(RobotAction.SetHeadingLockTarget(null))
            }
            useHeadingLock && isRotating && (target != null || driveMode != com.areslib.state.DriveMode.TELEOP) -> {
                store.dispatch(RobotAction.SetHeadingLockTarget(null))
                store.dispatch(RobotAction.SetDriveMode(com.areslib.state.DriveMode.TELEOP))
            }
            useHeadingLock && !isRotating && target == null -> {
                val physicalAngularVelocity = angularVelocity

                if (kotlin.math.abs(physicalAngularVelocity) < 0.03) {
                    store.dispatch(RobotAction.SetHeadingLockTarget(headingRad))
                    store.dispatch(RobotAction.SetDriveMode(com.areslib.state.DriveMode.HEADING_HOLD))
                    headingErrorFilter.reset(0.0)
                    headingPID.reset()
                } else {
                    // Let the robot's physical rotation coast/decelerate to a stop before locking heading target
                    finalOmega = 0.0
                }
            }
            useHeadingLock && !isRotating && target != null -> {
                val tuning = store.state.tuning
                headingPID.p = tuning.headingGains.kP
                headingPID.i = tuning.headingGains.kI
                headingPID.d = tuning.headingGains.kD
                headingPID.deadzone = Math.toRadians(tuning.headingDeadzoneDeg)

                // Clamp heading hold correction effort to max power to prevent oscillation and snapping
                val maxEffort = maxAngularSpeedRps * tuning.headingMaxOutputLimit
                headingPID.setOutputLimits(-maxEffort, maxEffort)

                // Compute PID correction using real loop dtSeconds
                finalOmega = headingPID.calculate(headingRad, target, dtSeconds) / maxAngularSpeedRps
                fromHeadingHold = true
            }
        }

        // --- Position Hold (mirrors heading lock pattern) ---
        val posLockX = store.state.drive.positionLockX
        val posLockY = store.state.drive.positionLockY
        val hasLinearInput = kotlin.math.abs(vx) > 0.05 || kotlin.math.abs(vy) > 0.05

        when {
            !usePositionHold && posLockX != null -> {
                // Position hold was disabled — release lock
                store.dispatch(RobotAction.SetPositionLockTarget(null, null))
            }
            usePositionHold && hasLinearInput && posLockX != null -> {
                // Driver is moving — release lock
                store.dispatch(RobotAction.SetPositionLockTarget(null, null))
                if (store.state.drive.driveMode == com.areslib.state.DriveMode.POSITION_HOLD) {
                    store.dispatch(RobotAction.SetDriveMode(
                        if (target != null) com.areslib.state.DriveMode.HEADING_HOLD
                        else com.areslib.state.DriveMode.TELEOP
                    ))
                }
            }
            usePositionHold && !hasLinearInput && posLockX == null -> {
                // Driver released joystick — latch target pose immediately
                store.dispatch(RobotAction.SetPositionLockTarget(pose.x, pose.y))
                store.dispatch(RobotAction.SetDriveMode(com.areslib.state.DriveMode.POSITION_HOLD))
                positionPidX.reset()
                positionPidY.reset()
            }
            usePositionHold && !hasLinearInput && posLockX != null -> {
                // Actively correct back to locked position
                val tuning = store.state.tuning
                positionPidX.p = tuning.positionHoldGains.kP
                positionPidX.i = tuning.positionHoldGains.kI
                positionPidX.d = tuning.positionHoldGains.kD
                positionPidX.deadzone = tuning.positionHoldDeadzoneMeters
                positionPidY.p = tuning.positionHoldGains.kP
                positionPidY.i = tuning.positionHoldGains.kI
                positionPidY.d = tuning.positionHoldGains.kD
                positionPidY.deadzone = tuning.positionHoldDeadzoneMeters

                // Clamp correction to max position hold speed limit
                val maxCorrection = maxSpeedMps * tuning.positionHoldMaxOutputLimit
                positionPidX.setOutputLimits(-maxCorrection, maxCorrection)
                positionPidY.setOutputLimits(-maxCorrection, maxCorrection)

                val errX = posLockX - pose.x
                val errY = posLockY!! - pose.y
                val distError = kotlin.math.hypot(errX, errY)

                if (distError > tuning.positionHoldDeadzoneMeters) {
                    val rawCorrVx = positionPidX.calculate(pose.x, posLockX, dtSeconds)
                    val rawCorrVy = positionPidY.calculate(pose.y, posLockY, dtSeconds)

                    // Apply minimum static friction feedforward from tuning state (tuning.driveFeedforward.kS)
                    // so small position errors overcome wheel breakout friction and drive the robot back
                    val kS = tuning.driveFeedforward.kS
                    val normX = errX / distError
                    val normY = errY / distError

                    val fieldVx = (rawCorrVx / maxSpeedMps) + (normX * kS)
                    val fieldVy = (rawCorrVy / maxSpeedMps) + (normY * kS)

                    finalRobotVx = fieldVx * cos + fieldVy * sin
                    finalRobotVy = -fieldVx * sin + fieldVy * cos
                } else {
                    finalRobotVx = 0.0
                    finalRobotVy = 0.0
                }
            }
        }

        robotRelativeDrive(finalRobotVx, finalRobotVy, finalOmega, fromHeadingHold)
    }

    /**
     * Configures the active target autonomous navigation [Path] for the robot.
     *
     * @param path The target [Path] to follow.
     */
    fun followPath(path: Path) {
        store.dispatch(RobotAction.PoseUpdate(
            xMeters = path.points.firstOrNull()?.pose?.x ?: pose.x,
            yMeters = path.points.firstOrNull()?.pose?.y ?: pose.y,
            headingRadians = path.points.firstOrNull()?.pose?.heading?.radians ?: pose.heading.radians,
            timestampMs = com.areslib.util.RobotClock.currentTimeMillis(),
            isReset = true
        ))
    }

    /**
     * Executes field-relative drivetrain movement effort based on standard Gamepad input,
     * automatically handling field-centric coordinate inversion based on the robot's Alliance color.
     *
     * @param driver The GamepadState object containing the driver's joystick inputs.
     * @param useHeadingLock Enables active IMU closed-loop heading lock to stabilize the robot's orientation.
     * @param usePositionHold Enables active EKF closed-loop position hold when joystick inputs are released.
     * @param dtSeconds Timestep delta duration in seconds.
     */
    @kotlin.jvm.JvmOverloads
    fun driveWithGamepad(driver: com.areslib.telemetry.AresGamepad, useHeadingLock: Boolean = true, usePositionHold: Boolean = false, dtSeconds: Double = 0.02) {
        val isTurbo = driver.rightBumper.isPressed
        val isSlow = driver.leftBumper.isPressed

        val speedMult = when {
            isTurbo -> 1.0
            isSlow -> 0.40
            else -> 0.65
        }

        val turnScale = when {
            isTurbo -> 0.85
            isSlow -> 0.30
            else -> store.state.tuning.teleOpTurnScale
        }

        val joystickForward = -driver.leftStickY.value.toDouble() * speedMult
        val joystickRight = driver.leftStickX.value.toDouble() * speedMult
        val rotate = -driver.rightStickX.value.toDouble() * turnScale
        
        val isBlueAlliance = store.state.drive.alliance == com.areslib.state.Alliance.BLUE
        val fieldVx = if (isBlueAlliance) -joystickRight else joystickRight
        val fieldVy = if (isBlueAlliance) -joystickForward else joystickForward
        
        fieldRelativeDrive(
            vx = fieldVx, 
            vy = fieldVy, 
            omega = rotate,
            useHeadingLock = useHeadingLock,
            usePositionHold = usePositionHold,
            dtSeconds = dtSeconds
        )
    }
}
