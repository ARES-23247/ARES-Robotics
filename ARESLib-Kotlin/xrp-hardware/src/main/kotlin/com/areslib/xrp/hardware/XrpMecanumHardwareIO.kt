package com.areslib.xrp.hardware

import com.areslib.kinematics.MecanumKinematics
import com.areslib.math.geometry.ChassisSpeeds

/**
 * High-level hardware IO contract for a 4-wheel Mecanum XRP Drivetrain.
 */
interface XrpMecanumHardwareIO {
    val frontLeftMotor: XrpMotorIO
    val frontRightMotor: XrpMotorIO
    val backLeftMotor: XrpMotorIO
    val backRightMotor: XrpMotorIO
    val kinematics: MecanumKinematics
    val wheelRadiusMeters: Double

    fun setPowers(fl: Double, fr: Double, bl: Double, br: Double) {
        frontLeftMotor.effort = fl.coerceIn(-1.0, 1.0)
        frontRightMotor.effort = fr.coerceIn(-1.0, 1.0)
        backLeftMotor.effort = bl.coerceIn(-1.0, 1.0)
        backRightMotor.effort = br.coerceIn(-1.0, 1.0)
    }

    fun drive(chassisSpeeds: ChassisSpeeds, maxLinearSpeedMps: Double = 0.85) {
        val speeds = kinematics.toWheelSpeeds(chassisSpeeds)
        val flP = if (maxLinearSpeedMps > 0.0) speeds.frontLeftMetersPerSecond / maxLinearSpeedMps else 0.0
        val frP = if (maxLinearSpeedMps > 0.0) speeds.frontRightMetersPerSecond / maxLinearSpeedMps else 0.0
        val blP = if (maxLinearSpeedMps > 0.0) speeds.backLeftMetersPerSecond / maxLinearSpeedMps else 0.0
        val brP = if (maxLinearSpeedMps > 0.0) speeds.backRightMetersPerSecond / maxLinearSpeedMps else 0.0
        setPowers(flP, frP, blP, brP)
    }

    fun stop() {
        frontLeftMotor.stop()
        frontRightMotor.stop()
        backLeftMotor.stop()
        backRightMotor.stop()
    }

    fun update() {
        frontLeftMotor.update()
        frontRightMotor.update()
        backLeftMotor.update()
        backRightMotor.update()
    }
}

open class StandardXrpMecanumHardwareIO(
    override val frontLeftMotor: XrpMotorIO = XrpMotorDouble(1),
    override val frontRightMotor: XrpMotorIO = XrpMotorDouble(2),
    override val backLeftMotor: XrpMotorIO = XrpMotorDouble(3),
    override val backRightMotor: XrpMotorIO = XrpMotorDouble(4),
    trackWidthMeters: Double = 0.155,
    wheelBaseMeters: Double = 0.140,
    override val wheelRadiusMeters: Double = 0.030
) : XrpMecanumHardwareIO {
    override val kinematics: MecanumKinematics = MecanumKinematics(trackWidthMeters, wheelBaseMeters)
}
