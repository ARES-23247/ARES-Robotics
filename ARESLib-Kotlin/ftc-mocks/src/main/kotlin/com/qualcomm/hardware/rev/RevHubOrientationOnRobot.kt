@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.hardware.rev

/**
 * Class implementation for [RevHubOrientationOnRobot].
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators
 * into immutable Redux state representations.
 *
 * Encapsulates the physical mounting orientation of a REV Control Hub or Expansion Hub
 * on a robot chassis defined by two orthogonal axes: [logoFacingDirection] and [usbFacingDirection].
 *
 * In desktop simulation, the physics engine supplies robot-frame IMU samples directly;
 * mounting transforms are therefore not required and throw descriptive exceptions if accessed.
 *
 * @property logoFacingDirection Direction the printed REV logo on top of the hub faces relative to the chassis.
 * @property usbFacingDirection Direction the USB ports on the side of the hub face relative to the chassis.
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

    /**
     * Cardinal directions for the REV Hub logo facing orientation.
     */
    enum class LogoFacingDirection {
        UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT
    }

    /**
     * Cardinal directions for the REV Hub USB ports facing orientation.
     */
    enum class UsbFacingDirection {
        UP, DOWN, FORWARD, BACKWARD, LEFT, RIGHT
    }
}
