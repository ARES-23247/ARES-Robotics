@file:Suppress("UNUSED_PARAMETER")
package org.firstinspires.ftc.robotcore.internal.usb.exception

/**
 * Class implementation for [RobotUsbException].
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators
 * into immutable Redux state representations.
 *
 * Thrown or propagated when communication across a robot USB endpoint encounters
 * framing errors, device disconnections, read/write timeouts, or packet corruption.
 *
 * Desktop test suites and simulated hardware managers catch this exception to verify
 * that robot subsystems safely neutralize actuator outputs, report descriptive diagnostic
 * telemetry, and execute fail-closed recovery policies when USB connectivity fails.
 *
 * Handling this exception properly prevents uncontrolled robot motion during disconnects.
 * Both parameterized and default no-argument constructors are supported for mock compatibility.
 *
 * @param message Diagnostic description of the USB hardware error condition.
 */
class RobotUsbException(message: String) : Exception(message) {
    /**
     * Constructs a [RobotUsbException] with an empty diagnostic message string.
     */
    constructor() : this("")
}
