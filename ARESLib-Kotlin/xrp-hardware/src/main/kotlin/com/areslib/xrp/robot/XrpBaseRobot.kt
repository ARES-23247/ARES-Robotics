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
 * JVM XRP IO lifecycle foundation. Each successful tick refreshes every device once.
 * Inactive ticks neutralize before refresh; failed ticks disable and attempt neutral before
 * propagating. Active mode transitions require a successful neutral boundary.
 *
 * This class does not implement odometry/EKF, battery measurement or a leased output controller.
 * [currentPose] is a manually reset pose, not a sensor estimate. Concrete integrations own
 * feedback validity, output gating and any overridden lifecycle methods. The exported
 * MicroPython robot runtime and the full XRP physics simulator are separate implementations.
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

    /** Unknown until a concrete integration supplies a measurement. */
    var batteryVoltage: Double = Double.NaN
        protected set

    open fun onInit() {
        enterMode(XrpRobotMode.INIT)
    }

    open fun onStartAuto() {
        enterMode(XrpRobotMode.AUTO)
    }

    open fun onStartTeleop() {
        enterMode(XrpRobotMode.TELEOP)
    }

    open fun onStop() {
        mode = XrpRobotMode.DISABLED
        drivetrain.stop()
    }

    /**
     * [dt] must be finite and positive. IO refresh has no time argument: this is not a variable
     * step integrator. The default motor double advances one fixed 20 ms fixture step per refresh.
     */
    open fun periodic(dt: Double = 0.02) {
        try {
            require(dt.isFinite() && dt > 0.0) { "dt must be finite and positive" }
            if (mode == XrpRobotMode.DISABLED || mode == XrpRobotMode.INIT) drivetrain.stop()
            drivetrain.update()
            ultrasonic.update()
            lineSensor.update()
        } catch (failure: Throwable) {
            mode = XrpRobotMode.DISABLED
            try { drivetrain.stop() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /** Validates the entire manually supplied pose before replacing the owned snapshot. */
    fun resetPose(x: Double, y: Double, headingRad: Double) {
        require(x.isFinite() && y.isFinite() && headingRad.isFinite()) { "Pose components must be finite" }
        currentPose = Pose2d(x, y, Rotation2d(headingRad))
    }

    private fun enterMode(target: XrpRobotMode) {
        mode = XrpRobotMode.DISABLED
        drivetrain.stop()
        mode = target
    }
}
