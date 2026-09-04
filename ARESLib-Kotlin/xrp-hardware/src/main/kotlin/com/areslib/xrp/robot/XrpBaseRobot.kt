package com.areslib.xrp.robot

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.xrp.hardware.StandardXrpDifferentialHardwareIO
import com.areslib.xrp.hardware.XrpDifferentialHardwareIO
import com.areslib.xrp.hardware.XrpLineSensorDouble
import com.areslib.xrp.hardware.XrpLineSensorIO
import com.areslib.xrp.hardware.XrpUltrasonicDouble
import com.areslib.xrp.hardware.XrpUltrasonicIO

enum class XrpRobotMode {
    DISABLED,
    INIT,
    AUTO,
    TELEOP
}

/**
 * Standard ARES Base Robot for XRP platforms.
 * Manages drivetrain IO, sensors, mode lifecycle, and periodic loop ticks.
 */
open class XrpBaseRobot(
    val drivetrain: XrpDifferentialHardwareIO = StandardXrpDifferentialHardwareIO(),
    val ultrasonic: XrpUltrasonicIO = XrpUltrasonicDouble(),
    val lineSensor: XrpLineSensorIO = XrpLineSensorDouble()
) {
    var mode: XrpRobotMode = XrpRobotMode.INIT
        protected set

    var currentPose: Pose2d = Pose2d(0.0, 0.0, Rotation2d(0.0))
        protected set

    var batteryVoltage: Double = 6.0
        protected set

    open fun onInit() {
        mode = XrpRobotMode.INIT
        drivetrain.stop()
    }

    open fun onStartAuto() {
        mode = XrpRobotMode.AUTO
    }

    open fun onStartTeleop() {
        mode = XrpRobotMode.TELEOP
    }

    open fun onStop() {
        mode = XrpRobotMode.DISABLED
        drivetrain.stop()
    }

    open fun periodic(dt: Double = 0.02) {
        // Update hardware readings
        drivetrain.update()
        ultrasonic.update()
        lineSensor.update()

        if (mode == XrpRobotMode.DISABLED) {
            drivetrain.stop()
        }
    }

    fun resetPose(x: Double, y: Double, headingRad: Double) {
        currentPose = Pose2d(x, y, Rotation2d(headingRad))
    }
}
