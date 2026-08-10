@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.hardware.configuration

/**
 * Object implementation for Lynx Constants.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
object LynxConstants {
    const val INITIAL_MOTOR_PORT = 0
    const val NUMBER_OF_MOTORS = 4
    const val INITIAL_SERVO_PORT = 0
    const val NUMBER_OF_SERVOS = 6
    fun isEmbeddedSerialNumber(serialNumber: com.qualcomm.robotcore.util.SerialNumber): Boolean = false
}
