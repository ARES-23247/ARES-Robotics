@file:Suppress("UNUSED_PARAMETER")
package org.firstinspires.ftc.robotcore.internal.usb.exception

/**
 * Class implementation for Robot Usb Exception.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
class RobotUsbException(message: String) : Exception(message) {
    constructor() : this("")
}
