@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.hardware.rev

/**
 * Class implementation for Rev Hub Orientation On Robot.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class RevHubOrientationOnRobot(
    val logoFacingDirection: LogoFacingDirection,
    val usbFacingDirection: UsbFacingDirection
) : com.qualcomm.robotcore.hardware.ImuOrientationOnRobot {
    // The simulator supplies robot-frame IMU samples directly; mounting transforms are not modeled.
    override fun imuCoordinateSystemOrientationFromPerspectiveOfRobot(): org.firstinspires.ftc.robotcore.external.navigation.Quaternion =
        throw UnsupportedOperationException("Simulated IMU samples are already in the robot frame")
    override fun imuRotationOffset(): org.firstinspires.ftc.robotcore.external.navigation.Quaternion =
        throw UnsupportedOperationException("Simulated IMU samples are already in the robot frame")
    override fun angularVelocityTransform(): org.firstinspires.ftc.robotcore.external.navigation.Quaternion =
        throw UnsupportedOperationException("Simulated IMU samples are already in the robot frame")
    enum class LogoFacingDirection {
        UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT
    }
    enum class UsbFacingDirection {
        UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT
    }
}
