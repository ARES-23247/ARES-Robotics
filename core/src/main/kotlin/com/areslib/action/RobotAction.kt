package com.areslib.action

import com.areslib.math.geometry.Vector3

/**
 * Represents an immutable intent or hardware update event dispatched to the Redux store.
 * All concrete subtypes are data classes with `val` fields, ensuring immutability.
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
    /**
     * Class implementation for Drive Hardware Update.
     *
     * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
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
     * Reports a simple 2D vision target detection (legacy single-target tracking).
     *
     * @property hasTarget Whether a target is currently detected.
     * @property targetX Target X coordinate in the camera frame (meters).
     * @property targetY Target Y coordinate in the camera frame (meters).
     */
    data class VisionUpdate(
        val hasTarget: Boolean,
        val targetX: Double,
        val targetY: Double,
        override val timestampMs: Long
    ) : RobotAction

    /**
     * Reports all AprilTag fiducial detections from the vision subsystem in a single frame.
     *
     * @property measurements List of individual tag detections with pose estimates.
     * @property customVisionStdDevs Optional override for EKF vision measurement standard deviations
     *   as a [Vector3] of (x meters, y meters, heading radians). If null, defaults are used.
     */
    data class VisionMeasurementsReceived(
        val measurements: List<com.areslib.state.VisionMeasurement>,
        override val timestampMs: Long,
        val customVisionStdDevs: Vector3? = null
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
     */
    /**
     * Class implementation for Pose Update.
     *
     * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
     */
    data class PoseUpdate(
        val xMeters: Double,
        val yMeters: Double,
        val headingRadians: Double,
        override val timestampMs: Long,
        val pitchDegrees: Double = 0.0,
        val rollDegrees: Double = 0.0,
        val xAccelerationG: Double = 0.0,
        val yAccelerationG: Double = 0.0,
        val zAccelerationG: Double = 0.0,
        val isReset: Boolean = false,
        val angularVelocityRadiansPerSecond: Double = 0.0,
        val xVelocityMetersPerSecond: Double = 0.0,
        val yVelocityMetersPerSecond: Double = 0.0
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
     * @property targetXVelocity Desired forward velocity as a normalized value [-1.0, 1.0] (WPILib: +X = forward).
     * @property targetYVelocity Desired lateral velocity as a normalized value [-1.0, 1.0] (WPILib: +Y = left).
     * @property targetAngularVelocity Desired rotational velocity as a normalized value [-1.0, 1.0] (CCW-positive).
     * @property isFieldCentric If true, X/Y are relative to the field; if false, relative to the robot chassis.
     * @property isXLock If true, locks X movement.
     */
    /**
     * Class implementation for Joystick Drive Intent.
     *
     * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
     */
    data class JoystickDriveIntent @kotlin.jvm.JvmOverloads constructor(
        val targetXVelocity: Double,
        val targetYVelocity: Double,
        val targetAngularVelocity: Double,
        override val timestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis(),
        val isFieldCentric: Boolean = true,
        val fromHeadingHold: Boolean = false,
        val isXLock: Boolean = false
    ) : RobotAction

    // Autonomous Events

    /** Fired when the trajectory follower reaches a named event marker along the path. */
    data class PathEventTriggered(
        val eventName: String,
        override val timestampMs: Long
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
