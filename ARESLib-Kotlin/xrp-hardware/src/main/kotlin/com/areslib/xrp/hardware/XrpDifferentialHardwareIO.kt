package com.areslib.xrp.hardware

import com.areslib.kinematics.DifferentialDriveKinematics
import com.areslib.kinematics.DifferentialWheelSpeeds
import com.areslib.math.geometry.ChassisSpeeds

/**
 * High-level hardware IO contract for an XRP Differential Drivetrain.
 */
interface XrpDifferentialHardwareIO {
    val leftMotor: XrpMotorIO
    val rightMotor: XrpMotorIO
    val kinematics: DifferentialDriveKinematics
    val wheelRadiusMeters: Double

    fun setPowers(leftPower: Double, rightPower: Double) {
        leftMotor.effort = leftPower.coerceIn(-1.0, 1.0)
        rightMotor.effort = rightPower.coerceIn(-1.0, 1.0)
    }

    fun drive(chassisSpeeds: ChassisSpeeds, maxLinearSpeedMps: Double = 0.85) {
        val wheelSpeeds = kinematics.toWheelSpeeds(chassisSpeeds)
        val leftPower = if (maxLinearSpeedMps > 0.0) wheelSpeeds.leftMetersPerSecond / maxLinearSpeedMps else 0.0
        val rightPower = if (maxLinearSpeedMps > 0.0) wheelSpeeds.rightMetersPerSecond / maxLinearSpeedMps else 0.0
        setPowers(leftPower, rightPower)
    }

    fun stop() {
        leftMotor.stop()
        rightMotor.stop()
    }

    fun update() {
        leftMotor.update()
        rightMotor.update()
    }

    fun getWheelDistances(): Pair<Double, Double> {
        val leftMeters = leftMotor.positionRadians * wheelRadiusMeters
        val rightMeters = rightMotor.positionRadians * wheelRadiusMeters
        return Pair(leftMeters, rightMeters)
    }
}

/**
 * Standard implementation of [XrpDifferentialHardwareIO] with configurable track width and wheel radius.
 */
open class StandardXrpDifferentialHardwareIO(
    override val leftMotor: XrpMotorIO = XrpMotorDouble(1),
    override val rightMotor: XrpMotorIO = XrpMotorDouble(2),
    trackWidthMeters: Double = 0.155,
    override val wheelRadiusMeters: Double = 0.030
) : XrpDifferentialHardwareIO {
    override val kinematics: DifferentialDriveKinematics = DifferentialDriveKinematics(trackWidthMeters)
}
