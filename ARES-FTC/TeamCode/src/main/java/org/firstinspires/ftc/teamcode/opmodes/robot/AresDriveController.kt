package org.firstinspires.ftc.teamcode.opmodes.robot

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.FtcTeleopDriveFrame
import com.areslib.math.InputMath
import com.areslib.state.Alliance

/**
 * Converts driver intent into season drivetrain commands without touching hardware directly.
 *
 * Each axis is deadband-rescaled, exponent-shaped, and passed through a first-order EMA. Explicit field commands use +X/+Y field axes and CCW-positive rotation. The gamepad
 * adapter maps forward to Red +Y and right to Red +X, away from the alliance wall. Blue
 * alliance negates both field-relative translation axes but never rotation. Robot-relative driving
 * is not alliance mirrored. Instances retain smoothing history and belong to one robot.
 */
class AresDriveController(private val base: FtcMecanumRobot) {
    private fun processAxis(input: Double, exponent: Double): Double {
        val boundedInput = input.coerceIn(-1.0, 1.0)
        val deadzoned = InputMath.applyDeadband(boundedInput, DEFAULT_DEADZONE)
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

    /** One immutable state snapshot and no residual smoothed command after an invalid input frame. */
    private fun updateInputs(x: Double, y: Double, rotation: Double): Alliance {
        val state = base.store.state
        val configuredExponent = state.tuning.driver.deadbandExponent
        val exponent = if (configuredExponent.isFinite() && configuredExponent > 0.0)
            configuredExponent else DEFAULT_CURVE_EXPONENT
        if (!x.isFinite() || !y.isFinite() || !rotation.isFinite()) {
            smoothX = 0.0
            smoothY = 0.0
            smoothRot = 0.0
        } else {
            smoothTransition(processAxis(x, exponent), processAxis(y, exponent), processAxis(rotation, exponent))
        }
        return state.drive.alliance
    }

    /** Drives from normalized field-relative axes after shaping and alliance transformation. */
    fun driveFieldCentric(x: Double, y: Double, rotation: Double) {
        val alliance = updateInputs(x, y, rotation)
        val direction = if (alliance == Alliance.BLUE) -1.0 else 1.0
        base.driveFieldCentric(direction * smoothX, direction * smoothY, smoothRot)
    }
    /** Drives from normalized robot-relative axes; alliance does not affect this frame. */
    fun driveRobotCentric(x: Double, y: Double, rotation: Double) {
        updateInputs(x, y, rotation)
        base.driveRobotCentric(smoothX, smoothY, smoothRot)
    }

    /**
     * Reads normalized FTC gamepad axes and commands the frame selected by
     * [FtcMecanumRobot.teleopDriveFrame]. Field-relative forward maps to Red +Y and right to Red +X;
     * CCW rotation is -rightStickX. Alliance mirroring applies only to field-relative
     * translation; robot-relative controls retain the robot's physical forward/left axes.
     */
    fun driveWithGamepad(driver: com.areslib.telemetry.AresGamepad, useHeadingLock: Boolean = true) {
        val alliance = updateInputs(-driver.leftStickY.value.toDouble(),
            -driver.leftStickX.value.toDouble(), -driver.rightStickX.value.toDouble())
        val direction = if (alliance == Alliance.BLUE) -1.0 else 1.0

        when (base.teleopDriveFrame) {
            FtcTeleopDriveFrame.FIELD_RELATIVE -> {
                base.mecanumDrive.driveFieldRelativeNormalized(
                    -direction * smoothY, direction * smoothX, smoothRot, useHeadingLock
                )
            }
            FtcTeleopDriveFrame.ROBOT_RELATIVE -> {
                base.mecanumDrive.driveRobotRelativeNormalized(smoothX, smoothY, smoothRot)
            }
        }
    }

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
