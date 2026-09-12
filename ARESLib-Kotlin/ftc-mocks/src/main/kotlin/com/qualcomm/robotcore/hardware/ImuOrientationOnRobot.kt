package com.qualcomm.robotcore.hardware

import org.firstinspires.ftc.robotcore.external.navigation.Quaternion

/** FTC SDK constructor boundary; hardware adapters must compile against this interface. */
interface ImuOrientationOnRobot {
    fun imuCoordinateSystemOrientationFromPerspectiveOfRobot(): Quaternion
    fun imuRotationOffset(): Quaternion
    fun angularVelocityTransform(): Quaternion
}
