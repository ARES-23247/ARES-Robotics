package com.areslib.action

import com.areslib.math.geometry.Vector3

/**
 * Represents an intent or hardware update event dispatched to the Redux store.
 *
 * Most concrete actions are immutable values. [PoseUpdate] and [JoystickDriveIntent] deliberately
 * use mutable fields so robot hot paths can reuse preallocated instances; asynchronous consumers
 * must snapshot those actions during dispatch rather than retaining their references.
 */
interface RobotAction {
    val timestampMs: Long
        get() = com.areslib.util.RobotClock.currentTimeMillis()

    // Hardware Updates

    /**
     * Reports raw odometry deltas and IMU readings from the drive hardware each control loop frame.
     *
     * @property xVelocity Robot's X velocity in meters/second (WPILib: +X = forward).
     * @property yVelocity Robot's Y velocity in meters/second (WPILib: +Y = left).
     * @property angularVelocity Robot's angular velocity in radians/second (CCW-positive).
     * @property deltaX Incremental X translation in meters since last frame.
     * @property deltaY Incremental Y translation in meters since last frame.
     * @property deltaHeading Incremental heading change in radians since last frame (CCW-positive).
     * @property pitchDegrees Robot pitch angle in degrees (nose-up positive).
     * @property rollDegrees Robot roll angle in degrees (right-side-down positive).
     * @property xAccelerationG X-axis acceleration in G-forces (forward positive).
     * @property yAccelerationG Y-axis acceleration in G-forces (left positive).
     * @property zAccelerationG Z-axis acceleration in G-forces (up positive).
     */
    data class DriveHardwareUpdate(
        val xVelocity: Double,
        val yVelocity: Double,
        val angularVelocity: Double,
        val deltaX: Double,
        val deltaY: Double,
        val deltaHeading: Double,
        override val timestampMs: Long,
        val pitchDegrees: Double = 0.0,
        val rollDegrees: Double = 0.0,
        val xAccelerationG: Double = 0.0,
        val yAccelerationG: Double = 0.0,
        val zAccelerationG: Double = 0.0
    ) : RobotAction
    
    /**
     * Reports all AprilTag fiducial detections from the vision subsystem in a single frame.
     *
     * @property measurements List of individual tag detections with pose estimates.
     * @property customVisionStdDevs Optional override for EKF vision measurement standard deviations
     *   as a [Vector3] of (x meters, y meters, heading radians). If null, defaults are used.
     * @property fuseIntoPoseEstimator False when an upstream estimator has already consumed
     *   these observations. The vision slice is still updated for diagnostics and dashboards,
     *   but the ARES EKF is left unchanged.
     */
    data class VisionMeasurementsReceived(
        val measurements: List<com.areslib.state.VisionMeasurement>,
        override val timestampMs: Long,
        val customVisionStdDevs: Vector3? = null,
        val fuseIntoPoseEstimator: Boolean = true
    ) : RobotAction

    /**
     * Reports an absolute pose update from the localization sensor (e.g., GoBilda Pinpoint).
     *
     * @property xMeters Absolute X position on the field in meters (WPILib: +X = toward Blue alliance wall).
     * @property yMeters Absolute Y position on the field in meters (WPILib: +Y = toward back wall).
     * @property headingRadians Absolute heading in radians (CCW-positive, 0 = facing +X).
     * @property pitchDegrees Robot pitch angle in degrees (nose-up positive).
     * @property rollDegrees Robot roll angle in degrees (right-side-down positive).
     * @property xAccelerationG X-axis acceleration in G-forces.
     * @property yAccelerationG Y-axis acceleration in G-forces.
     * @property zAccelerationG Z-axis acceleration in G-forces.
     * @property isReset If true, forces the EKF to hard-reset to this pose (e.g., re-initialization at match start).
     * @property isExternalEstimate If true, [xMeters], [yMeters], and [headingRadians]
     * are the output of an upstream pose estimator and must be mirrored directly rather
     * than treated as another odometry observation. This prevents estimator-on-estimator
     * feedback and correlated sensor measurements from being fused twice.
     */
    data class PoseUpdate(
        var xMeters: Double,
        var yMeters: Double,
        var headingRadians: Double,
        override var timestampMs: Long,
        var pitchDegrees: Double = 0.0,
        var rollDegrees: Double = 0.0,
        var pitchVelocityDegPerSec: Double = 0.0,
        var rollVelocityDegPerSec: Double = 0.0,
        var xAccelerationG: Double = 0.0,
        var yAccelerationG: Double = 0.0,
        var zAccelerationG: Double = 0.0,
        var isReset: Boolean = false,
        var angularVelocityRadiansPerSecond: Double = 0.0,
        var xVelocityMetersPerSecond: Double = 0.0,
        var yVelocityMetersPerSecond: Double = 0.0,
        var isExternalEstimate: Boolean = false,
        /** False when heading already comes from a fused source such as Pinpoint. */
        var applyControlHubGyroCorrection: Boolean = true
    ) : RobotAction

    /** Sets the active alliance color for field-centric driving and EKF initialization. */
    data class SetAlliance(
        val alliance: com.areslib.state.Alliance,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /** Sets the drive control mode (TELEOP, HEADING_HOLD, or X_BRAKE). */
    data class SetDriveMode(
        val mode: com.areslib.state.DriveMode,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /**
     * Sets or clears the heading lock target for automatic heading hold.
     * @property targetRadians Target heading in radians (CCW-positive), or null to disable heading lock.
     */
    data class SetHeadingLockTarget(
        val targetRadians: Double?,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /**
     * Triggers zero-offset calibration for all 4 swerve module CANcoders, saving the output to local roboRIO flash JSON and backups.
     */
    data class CalibrateSwerveOffsets(
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /**
     * Sets or clears the position lock target for automatic position hold.
     * When joystick inputs are released, the robot actively drives back to this latched position.
     * @property targetX Target X position in meters (field-relative), or null to disable position lock.
     * @property targetY Target Y position in meters (field-relative), or null to disable position lock.
     */
    data class SetPositionLockTarget(
        val targetX: Double?,
        val targetY: Double?,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    // Human Intent

    /**
     * Dispatches driver joystick commands as velocity intents.
     *
     * @property targetXVelocity Desired forward velocity in meters per second (WPILib: +X = forward).
     * @property targetYVelocity Desired lateral velocity in meters per second (WPILib: +Y = left).
     * @property targetAngularVelocity Desired rotational velocity in radians per second (CCW-positive).
     * @property isFieldCentric If true, X/Y are relative to the field; if false, relative to the robot chassis.
     * @property isXLock If true, locks X movement.
     */
    data class JoystickDriveIntent @kotlin.jvm.JvmOverloads constructor(
        var targetXVelocity: Double,
        var targetYVelocity: Double,
        var targetAngularVelocity: Double,
        override var timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis(),
        var isFieldCentric: Boolean = true,
        var fromHeadingHold: Boolean = false,
        var isXLock: Boolean = false
    ) : RobotAction

    // Autonomous Events

    /** Fired when the trajectory follower reaches a named event marker along the path. */
    data class PathEventTriggered(
        val eventName: String,
        override val timestampMs: Long
    ) : RobotAction

    /** Records a routine invocation request, including requests waiting in the manager queue. */
    data class RoutineRequested(
        val executionId: Long,
        val routineId: String,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /** Records the point at which a requested routine begins executing. */
    data class RoutineStarted(
        val executionId: Long,
        val routineId: String,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /** Exposes the currently entered document node to telemetry, replay, and GUI debugging. */
    data class RoutineStepEntered(
        val executionId: Long,
        val routineId: String,
        val stepPath: String,
        val stepKind: String,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /** Records successful routine completion. */
    data class RoutineCompleted(
        val executionId: Long,
        val routineId: String,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /** Records deterministic task/compilation failure with a student-readable reason. */
    data class RoutineFailed(
        val executionId: Long,
        val routineId: String,
        val reason: String,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /** Records an explicit cancellation after safe cleanup actions have been dispatched. */
    data class RoutineCancelled(
        val executionId: Long,
        val routineId: String,
        val reason: String,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    // Superstructure Actions

    /**
     * Replaces or registers a generic subsystem state implementation in the Redux store.
     *
     * @param state The newly updated immutable subsystem state object.
     */
    data class UpdateSubsystemState(
        val state: com.areslib.state.SubsystemState,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /** Replaces one independently named subsystem state without disturbing the season state. */
    data class UpdateNamedSubsystemState(
        val subsystemId: String,
        val state: com.areslib.state.SubsystemState,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /**
     * Sets a named indicator light to a specific PWM position (0.0 to 1.0).
     * Use [com.areslib.hardware.actuator.IndicatorLightColor.position] for predefined colors.
     * @param name The hardware map name of the indicator light.
     * @param position The servo position (0.0 to 1.0) on the color gradient.
     */
    data class SetIndicatorLight(
        val name: String,
        val position: Double,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    /**
     * Sets a named goBILDA Prism RGB LED Driver to a pulse width in microseconds (500–2500µs).
     * @param name Hardware map name of the Prism driver.
     * @param pulseWidthUs Pulse width in microseconds (500 to 2500).
     */
    data class SetPrismDriver(
        val name: String,
        val pulseWidthUs: Int,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction

    // Path Following and Switching Actions

    /**
     * Chains a sequence of paths for the trajectory follower to execute sequentially.
     *
     * @property paths Ordered list of [com.areslib.pathing.Path] segments to follow.
     * @property maxVelocityMps Maximum allowed velocity in meters/second along the path.
     * @property maxAccelerationMps2 Maximum allowed acceleration in meters/second².
     */
    data class ChainPaths(
        val paths: List<com.areslib.pathing.Path>,
        val maxVelocityMps: Double = 2.0,
        val maxAccelerationMps2: Double = 1.5,
        override val timestampMs: Long
    ) : RobotAction

    /**
     * Switches the active path being followed by the trajectory follower.
     *
     * @property path The new [com.areslib.pathing.Path] to follow.
     * @property isDetour If true, this is a reactive obstacle-avoidance detour rather than a planned path switch.
     */
    data class SwitchPath(
        val path: com.areslib.pathing.Path,
        val isDetour: Boolean = false,
        /** Arc-length distance to start tracking from. 0.0 = path start. Set via closest-point projection for placement error correction. */
        val startDistanceMeters: Double = 0.0,
        override val timestampMs: Long
    ) : RobotAction

    /**
     * Reports the trajectory follower's progress along the active path.
     * @property distanceProgressMeters Cumulative distance traveled along the path in meters.
     */
    data class UpdatePathProgress(
        val distanceProgressMeters: Double,
        val crossTrackErrorMeters: Double = 0.0,
        val alongTrackErrorMeters: Double = 0.0,
        val headingErrorRadians: Double = 0.0,
        override val timestampMs: Long
    ) : RobotAction

    /**
     * Updates the global tuning and PID constants dynamically from the dashboard.
     */
    data class UpdateTuningState(
        val tuning: com.areslib.state.TuningState,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis()
    ) : RobotAction
}
