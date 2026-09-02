package org.firstinspires.ftc.teamcode.opmodes.robot

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.FtcTeleopDriveFrame
import com.areslib.math.InputMath
import com.areslib.state.Alliance

/**
 * Converts driver intent into season drivetrain commands without touching hardware directly.
 *
 * Each axis is deadband-rescaled, exponent-shaped, and passed through a first-order EMA. Command
 * parameters follow ARES field axes: +X forward, +Y left, and CCW-positive rotation. The gamepad
 * adapter maps negated left-stick Y to field X and negated left-stick X to field Y. Blue
 * alliance negates both field-relative translation axes but never rotation. Robot-relative driving
 * is not alliance mirrored. Instances retain smoothing history and belong to one robot.
 */
class AresDriveController(private val base: FtcMecanumRobot) {
    private fun processAxis(input: Double): Double {
        val boundedInput = if (input.isFinite()) input.coerceIn(-1.0, 1.0) else 0.0
        val deadzoned = InputMath.applyDeadband(boundedInput, DEFAULT_DEADZONE)
        val exponent = base.store.state.tuning.driver.deadbandExponent
            .let { if (it > 0.0) it else DEFAULT_CURVE_EXPONENT }
        return InputMath.applyCurve(deadzoned, exponent)
    }

    private var smoothX = 0.0
    private var smoothY = 0.0
    private var smoothRot = 0.0

    private fun smoothTransition(x: Double, y: Double, rot: Double) {
        // Fixed EMA coefficient is loop-frequency dependent and intentionally allocation-free.
        smoothX = smoothX * EMA_RETENTION + x * EMA_ALPHA
        smoothY = smoothY * EMA_RETENTION + y * EMA_ALPHA
        smoothRot = smoothRot * EMA_RETENTION + rot * EMA_ALPHA
    }

    /** Drives from normalized field-relative axes after shaping and alliance transformation. */
    fun driveFieldCentric(x: Double, y: Double, rotation: Double) {
        val px = processAxis(x)
        val py = processAxis(y)
        val prot = processAxis(rotation)
        smoothTransition(px, py, prot)

        // Blue changes the driver station perspective by 180 degrees: mirror X and Y together.
        base.driveFieldCentric(mirrorXForBlue(), mirrorYForBlue(), smoothRot)
    }
    /** Drives from normalized robot-relative axes; alliance does not affect this frame. */
    fun driveRobotCentric(x: Double, y: Double, rotation: Double) {
        val px = processAxis(x)
        val py = processAxis(y)
        val prot = processAxis(rotation)
        smoothTransition(px, py, prot)

        base.driveRobotCentric(smoothX, smoothY, smoothRot)
    }

    /**
     * Reads normalized FTC gamepad axes and commands the frame selected by
     * [FtcMecanumRobot.teleopDriveFrame]. FTC stick Y and right-stick rotation are negative in SDK
     * coordinates, hence those two negations. Alliance mirroring applies only to field-relative
     * translation; robot-relative controls retain the robot's physical forward/left axes.
     */
    fun driveWithGamepad(driver: com.areslib.telemetry.AresGamepad, useHeadingLock: Boolean = true) {
        val px = processAxis(-driver.leftStickY.value.toDouble())
        val py = processAxis(-driver.leftStickX.value.toDouble())
        val prot = processAxis(-driver.rightStickX.value.toDouble())
        smoothTransition(px, py, prot)

        when (base.teleopDriveFrame) {
            FtcTeleopDriveFrame.FIELD_RELATIVE -> {
                base.mecanumDrive.driveFieldRelativeNormalized(
                    mirrorXForBlue(), mirrorYForBlue(), smoothRot, useHeadingLock
                )
            }
            FtcTeleopDriveFrame.ROBOT_RELATIVE -> {
                base.mecanumDrive.driveRobotRelativeNormalized(smoothX, smoothY, smoothRot)
            }
        }
    }

    /** Blue mirrors both field-relative translation axes for the driver's perspective. */
    private fun mirrorXForBlue(): Double =
        if (base.store.state.drive.alliance == Alliance.BLUE) -smoothX else smoothX

    /** See [mirrorXForBlue]; rotation is never alliance-mirrored. */
    private fun mirrorYForBlue(): Double =
        if (base.store.state.drive.alliance == Alliance.BLUE) -smoothY else smoothY

    /** Requests ARESLib target-space alignment to a specific AprilTag ID. */
    fun alignToTag(tagId: Int) {
        base.alignToTag(tagId)
    }
    /** Resets localization to the configured start pose for the current Redux alliance. */
    fun resetPoseForAlliance() {
        base.resetPoseForAlliance()
    }

    /** Resets the EKF pose; heading is CCW-positive radians. */
    fun resetPose(pose: com.areslib.math.geometry.Pose2d = com.areslib.math.geometry.Pose2d()) {
        base.resetPose(pose)
    }

    companion object {
        /** Joystick deadband threshold; values below this are treated as zero. */
        const val DEFAULT_DEADZONE = 0.05
        /** Fallback response-curve exponent when live tuning provides no valid value. */
        const val DEFAULT_CURVE_EXPONENT = 3.0
        /** Fixed EMA smoothing weight (alpha) for new input samples. */
        const val EMA_ALPHA = 0.4
        /** EMA retention factor (1 - alpha) for previous smoothed state. */
        const val EMA_RETENTION = 0.6
    }
}
