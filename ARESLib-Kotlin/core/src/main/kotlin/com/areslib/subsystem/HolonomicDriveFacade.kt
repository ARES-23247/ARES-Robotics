package com.areslib.subsystem

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.control.feedback.PIDController
import com.areslib.control.tuning.PIDFCoefficients
import com.areslib.math.geometry.Pose2d
import com.areslib.math.filter.LowPassFilter
import com.areslib.math.wrapAngle
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.pathing.Path
import com.areslib.sequencer.FollowPathTask
import com.areslib.sequencer.Task
import com.areslib.state.DriveMode
import com.areslib.telemetry.AresGamepad
import com.areslib.util.RobotClock
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Single-loop holonomic command facade. Translation is projected onto a circular speed limit;
 * field transforms use one immutable EKF snapshot. Holding produces physical velocity targets,
 * while motor feedforward, feedback freshness and enable gates remain in the platform pipeline.
 * Reused actions must be snapshotted by observers that retain them after synchronous dispatch.
 * Constructor heading gains apply until a new Redux drive-tuning object is supplied.
 */
abstract class HolonomicDriveFacade @kotlin.jvm.JvmOverloads constructor(
    protected val store: Store,
    headingGains: PIDFCoefficients = PIDFCoefficients(1.8, 0.0, 0.08),
    headingDeadzoneDeg: Double = 1.0
) {
    /** Maximum translation magnitude in m/s; nonpositive/nonfinite limits neutralize commands. */
    var maxSpeedMps: Double = 3.5
    /** Maximum angular speed in rad/s; nonpositive/nonfinite limits neutralize commands. */
    var maxAngularSpeedRps: Double = 9.5

    /** Cached measured field-forward velocity in m/s, distinct from commanded velocity. */
    val xVelocity: Double get() = store.state.drive.measuredFieldXVelocityMetersPerSecond
    /** Cached measured field-left velocity in m/s, distinct from commanded velocity. */
    val yVelocity: Double get() = store.state.drive.measuredFieldYVelocityMetersPerSecond
    /** Cached measured CCW angular velocity in rad/s. */
    val angularVelocity: Double get() = store.state.drive.measuredAngularVelocityRadiansPerSecond
    /** Allocating convenience view of the EKF estimate; periodic calculations use primitives. */
    val pose: Pose2d get() = store.state.drive.poseEstimator.estimatedPose
    val odometryX: Double get() = store.state.drive.odometryX
    val odometryY: Double get() = store.state.drive.odometryY
    val odometryHeading: Double get() = store.state.drive.odometryHeading

    private var lastDriveTuning = store.state.tuning.drive
    protected val headingPID = PIDController(headingGains.kP, headingGains.kI, headingGains.kD).apply {
        enableContinuousInput(-Math.PI, Math.PI)
        setOutputLimits(-2.0, 2.0)
        deadzone = Math.toRadians(headingDeadzoneDeg)
    }
    /** Retained for subclass compatibility; heading PID already filters its measurement derivative. */
    protected val headingErrorFilter = LowPassFilter(0.0)
    protected val positionPidX = PIDController(lastDriveTuning.positionHoldGains.kP,
        lastDriveTuning.positionHoldGains.kI, lastDriveTuning.positionHoldGains.kD).apply { setOutputLimits(-1.4, 1.4) }
    protected val positionPidY = PIDController(lastDriveTuning.positionHoldGains.kP,
        lastDriveTuning.positionHoldGains.kI, lastDriveTuning.positionHoldGains.kD).apply { setOutputLimits(-1.4, 1.4) }
    private var lastHeadingTarget = Double.NaN
    private var lastPositionX = Double.NaN
    private var lastPositionY = Double.NaN
    private val reusableDriveIntent = RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0)

    /** Clears controller history when braking or abandoning a hold, without allocating. */
    protected fun resetHoldControllers() {
        headingPID.reset()
        positionPidX.reset()
        positionPidY.reset()
        lastHeadingTarget = Double.NaN
        lastPositionX = Double.NaN
        lastPositionY = Double.NaN
    }

    /**
     * Dispatches robot-relative normalized effort. Translation retains direction and is bounded
     * to the unit disk; angular effort is bounded to [-1, 1]. Invalid inputs neutralize the frame.
     * Repeated commands refresh RobotClock time even when their numeric values have not changed.
     */
    fun driveRobotRelativeNormalized(vx: Double, vy: Double, omega: Double, fromHeadingHold: Boolean = false) {
        if (!fromHeadingHold) resetHoldControllers()
        dispatchNormalized(vx, vy, omega, fromHeadingHold, false, RobotClock.currentTimeMillis())
    }

    private fun dispatchNormalized(
        vx: Double, vy: Double, omega: Double, headingHold: Boolean, positionHold: Boolean,
        timestampMs: Long, xLock: Boolean = false,
    ) {
        val valid = reusableDriveIntent.setLimitedVelocities(vx, vy, omega, 1.0, 1.0) &&
            validDriveLimits(maxSpeedMps, maxAngularSpeedRps)
        if (valid) {
            reusableDriveIntent.targetXVelocity *= maxSpeedMps
            reusableDriveIntent.targetYVelocity *= maxSpeedMps
            reusableDriveIntent.targetAngularVelocity *= maxAngularSpeedRps
        } else {
            reusableDriveIntent.targetXVelocity = 0.0
            reusableDriveIntent.targetYVelocity = 0.0
            reusableDriveIntent.targetAngularVelocity = 0.0
        }
        reusableDriveIntent.isFieldCentric = false
        reusableDriveIntent.timestampMs = timestampMs
        reusableDriveIntent.fromHeadingHold = headingHold
        reusableDriveIntent.fromPositionHold = positionHold
        reusableDriveIntent.isXLock = xLock
        store.dispatch(reusableDriveIntent)
    }

    /**
     * Field-relative normalized driving with optional heading and position holds. Holds ignore
     * stick noise up to 0.05, release on deliberate input, and preserve each other's targets.
     * Invalid pose/input/limits/time neutralize and release holds. Position correction uses m/s
     * and a circular limit; static motor feedforward is applied only by the hardware controller.
     */
    fun driveFieldRelativeNormalized(
        vx: Double, vy: Double, omega: Double, useHeadingLock: Boolean = false,
        usePositionHold: Boolean = false, dtSeconds: Double = 0.02,
    ) {
        val state = store.state
        val drive = state.drive
        val estimate = drive.poseEstimator
        val timestampMs = RobotClock.currentTimeMillis()
        val validInput = reusableDriveIntent.setLimitedVelocities(vx, vy, omega, 1.0, 1.0)
        if (!validInput || !validDriveLimits(maxSpeedMps, maxAngularSpeedRps) ||
            !dtSeconds.isFinite() || dtSeconds <= 0.0 || !estimate.estimatedPoseX.isFinite() ||
            !estimate.estimatedPoseY.isFinite() || !estimate.estimatedPoseHeading.isFinite()) {
            resetHoldControllers()
            val mode = if (drive.driveMode == DriveMode.X_BRAKE) DriveMode.X_BRAKE else DriveMode.TELEOP
            publishHoldState(null, null, null, mode, timestampMs)
            dispatchNormalized(0.0, 0.0, 0.0, false, false, timestampMs, mode == DriveMode.X_BRAKE)
            return
        }
        val inputX = reusableDriveIntent.targetXVelocity
        val inputY = reusableDriveIntent.targetYVelocity
        val inputOmega = reusableDriveIntent.targetAngularVelocity
        val hasLinearInput = abs(inputX) > 0.05 || abs(inputY) > 0.05
        val isRotating = abs(inputOmega) > 0.05
        if (drive.driveMode == DriveMode.X_BRAKE && !hasLinearInput && !isRotating) {
            resetHoldControllers()
            publishHoldState(null, null, null, DriveMode.X_BRAKE, timestampMs)
            dispatchNormalized(0.0, 0.0, 0.0, false, false, timestampMs, true)
            return
        }

        val tuning = state.tuning.drive
        if (tuning !== lastDriveTuning) {
            headingPID.p = tuning.headingGains.kP
            headingPID.i = tuning.headingGains.kI
            headingPID.d = tuning.headingGains.kD
            headingPID.deadzone = Math.toRadians(tuning.headingDeadzoneDeg)
            positionPidX.p = tuning.positionHoldGains.kP
            positionPidX.i = tuning.positionHoldGains.kI
            positionPidX.d = tuning.positionHoldGains.kD
            positionPidY.p = tuning.positionHoldGains.kP
            positionPidY.i = tuning.positionHoldGains.kI
            positionPidY.d = tuning.positionHoldGains.kD
            lastDriveTuning = tuning
        }
        val x = estimate.estimatedPoseX
        val y = estimate.estimatedPoseY
        val heading = wrapAngle(estimate.estimatedPoseHeading)
        val cosH = cos(heading)
        val sinH = sin(heading)
        var fieldX = inputX
        var fieldY = inputY
        var finalOmega = inputOmega
        var headingTarget = drive.headingLockTargetRadians
        var positionX = drive.positionLockX
        var positionY = drive.positionLockY
        var headingHold = false
        var positionHold = false

        if (!useHeadingLock || isRotating) {
            headingTarget = null
            headingPID.reset()
            lastHeadingTarget = Double.NaN
        } else {
            finalOmega = 0.0
            headingHold = true
            if (headingTarget != null && !headingTarget.isFinite()) {
                headingTarget = null
                headingPID.reset()
                lastHeadingTarget = Double.NaN
            } else if (headingTarget == null) {
                headingPID.reset()
                lastHeadingTarget = Double.NaN
                if (drive.measuredAngularVelocityRadiansPerSecond.isFinite() &&
                    abs(drive.measuredAngularVelocityRadiansPerSecond) < 0.03) {
                    headingTarget = heading
                    lastHeadingTarget = heading
                }
            } else {
                if (lastHeadingTarget != headingTarget) headingPID.reset()
                lastHeadingTarget = headingTarget
                val limit = tuning.headingMaxOutputLimit
                if (limit.isFinite() && limit in 0.0..1.0) {
                    val maxCorrection = maxAngularSpeedRps * limit
                    headingPID.setOutputLimits(-maxCorrection, maxCorrection)
                    finalOmega = headingPID.calculate(heading, headingTarget, dtSeconds) / maxAngularSpeedRps
                } else headingPID.reset()
            }
        }

        if (!usePositionHold || hasLinearInput) {
            positionX = null
            positionY = null
            positionPidX.reset()
            positionPidY.reset()
            lastPositionX = Double.NaN
            lastPositionY = Double.NaN
        } else {
            fieldX = 0.0
            fieldY = 0.0
            positionHold = true
            if (positionX == null && positionY == null) {
                positionX = x
                positionY = y
                positionPidX.reset()
                positionPidY.reset()
                lastPositionX = x
                lastPositionY = y
            } else if (positionX == null || positionY == null || !positionX.isFinite() || !positionY.isFinite() ||
                !(positionX - x).isFinite() || !(positionY - y).isFinite()) {
                positionX = null
                positionY = null
                positionPidX.reset()
                positionPidY.reset()
                lastPositionX = Double.NaN
                lastPositionY = Double.NaN
            } else {
                if (lastPositionX != positionX || lastPositionY != positionY) {
                    positionPidX.reset()
                    positionPidY.reset()
                }
                lastPositionX = positionX
                lastPositionY = positionY
                val deadzone = tuning.positionHoldDeadzoneMeters
                val limit = tuning.positionHoldMaxOutputLimit
                if (deadzone.isFinite() && deadzone >= 0.0 && limit.isFinite() && limit in 0.0..1.0 &&
                    hypot(positionX - x, positionY - y) > deadzone && limit > 0.0) {
                    val maxCorrection = maxSpeedMps * limit
                    positionPidX.setOutputLimits(-maxCorrection, maxCorrection)
                    positionPidY.setOutputLimits(-maxCorrection, maxCorrection)
                    val correctionX = positionPidX.calculate(x, positionX, dtSeconds)
                    val correctionY = positionPidY.calculate(y, positionY, dtSeconds)
                    if (positionPidX.lastCalculationValid && positionPidY.lastCalculationValid &&
                        reusableDriveIntent.setLimitedVelocities(correctionX, correctionY, 0.0, maxCorrection, maxAngularSpeedRps)) {
                        fieldX = reusableDriveIntent.targetXVelocity / maxSpeedMps
                        fieldY = reusableDriveIntent.targetYVelocity / maxSpeedMps
                    }
                } else {
                    positionPidX.reset()
                    positionPidY.reset()
                }
            }
        }
        val mode = when {
            positionX != null && positionY != null -> DriveMode.POSITION_HOLD
            headingTarget != null -> DriveMode.HEADING_HOLD
            else -> DriveMode.TELEOP
        }
        publishHoldState(headingTarget, positionX, positionY, mode, timestampMs)
        dispatchNormalized(fieldX * cosH + fieldY * sinH, -fieldX * sinH + fieldY * cosH,
            finalOmega, headingHold, positionHold, timestampMs)
    }

    private fun publishHoldState(heading: Double?, x: Double?, y: Double?, mode: DriveMode, timestampMs: Long) {
        val drive = store.state.drive
        if (drive.headingLockTargetRadians != heading) store.dispatch(RobotAction.SetHeadingLockTarget(heading, timestampMs))
        if (drive.positionLockX != x || drive.positionLockY != y) store.dispatch(RobotAction.SetPositionLockTarget(x, y, timestampMs))
        if (drive.driveMode != mode) store.dispatch(RobotAction.SetDriveMode(mode, timestampMs))
    }

    private class PathExecution(val follower: HolonomicPathFollower, val submit: (Task) -> Unit)
    private var pathExecution: PathExecution? = null

    /**
     * Binds this facade to the robot's existing path follower and task lifecycle during setup.
     * [submit] must queue the task on that robot's executor/runtime, which owns updates and stop.
     * No background loop is created. Supply a follower for the same robot/state as this facade.
     */
    fun configurePathFollowing(follower: HolonomicPathFollower, submit: (Task) -> Unit) {
        pathExecution = PathExecution(follower, submit)
    }

    /**
     * Queues a path on the configured execution owner, preserving the current EKF pose and the
     * caller's field coordinates. Alliance transforms must be applied explicitly before this call.
     * Unconfigured or empty requests fail before scheduling. The task validates path contents and
     * owns progress, marker execution, interruption and neutral output through the existing runtime.
     */
    fun followPath(path: Path) {
        val execution = checkNotNull(pathExecution) { "Configure path following with a follower and task submission owner first" }
        require(path.points.isNotEmpty()) { "Cannot follow an empty path" }
        execution.submit(FollowPathTask(execution.follower, path, mirrorForAlliance = false))
    }

    /**
     * Executes field-relative drivetrain movement effort based on standard Gamepad input,
     * automatically handling field-centric coordinate inversion based on the robot's Alliance color.
     *
     * Uses the shaped two-axis stick values, so [AresGamepad.BindableStick.withDeadband],
     * [AresGamepad.BindableStick.withExponentialCurve], and
     * [AresGamepad.BindableStick.withSlewRateLimit] apply directly to standard driving.
     *
     * @param driver The gamepad containing the driver's sampled and shaped joystick inputs.
     * @param useHeadingLock Enables active IMU closed-loop heading lock to stabilize the robot's orientation.
     * @param usePositionHold Enables active EKF closed-loop position hold when joystick inputs are released.
     * @param dtSeconds Timestep delta duration in seconds.
     */
    @kotlin.jvm.JvmOverloads
    fun driveWithGamepad(driver: AresGamepad, useHeadingLock: Boolean = true, usePositionHold: Boolean = false, dtSeconds: Double = 0.02) {
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
            else -> store.state.tuning.drive.teleOpTurnScale
        }

        val joystickForward = -driver.leftStick.shapedY * speedMult
        val joystickLeft = -driver.leftStick.shapedX * speedMult
        val rotate = -driver.rightStick.shapedX * turnScale
        
        val isBlueAlliance = store.state.drive.alliance == com.areslib.state.Alliance.BLUE
        val fieldVx = if (isBlueAlliance) -joystickForward else joystickForward
        val fieldVy = if (isBlueAlliance) -joystickLeft else joystickLeft
        
        driveFieldRelativeNormalized(
            vx = fieldVx, 
            vy = fieldVy, 
            omega = rotate,
            useHeadingLock = useHeadingLock,
            usePositionHold = usePositionHold,
            dtSeconds = dtSeconds
        )
    }
}

/**
 * A highly simplified, student-facing modular facade for a Mecanum drivetrain subsystem.
 *
 * Inherits all coordinate transformation, heading holding, path following, and drive logic
 * from [HolonomicDriveFacade].
 */
class MecanumDriveFacade @kotlin.jvm.JvmOverloads constructor(
    store: Store,
    headingGains: PIDFCoefficients = PIDFCoefficients(2.2, 0.0, 0.12),
    headingDeadzoneDeg: Double = 0.75
) : HolonomicDriveFacade(store, headingGains, headingDeadzoneDeg)

/**
 * A highly simplified, student-facing modular facade for a Swerve drivetrain subsystem.
 *
 * Inherits standard coordinate transformations, heading locking, and driving math from [HolonomicDriveFacade],
 * and adds swerve-specific features like active braking configuration.
 */
class SwerveDriveFacade(store: Store) : HolonomicDriveFacade(store) {
    /**
     * Commands all swerve modules to lock into an "X" configuration (orthogonal angles)
     * with exactly 0.0 speed. This resists pushes from opponent robots.
     */
    fun brake() {
        resetHoldControllers()
        store.dispatch(RobotAction.SetDriveMode(com.areslib.state.DriveMode.X_BRAKE))
    }
}
