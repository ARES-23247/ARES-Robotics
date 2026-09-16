@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.hardware.configuration

/**
 * Object implementation for [LynxConstants].
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators
 * into immutable Redux state representations.
 *
 * Exposes hardware topology boundaries and physical port constraints for REV Expansion
 * and Control Hub devices. In desktop simulation, these constants establish port validity
 * limits when parsing configuration files and instantiating simulated hardware mappings.
 */
object LynxConstants {
    /** Starting zero-based port index for DC motor channels on a Lynx module. */
    const val INITIAL_MOTOR_PORT: Int = 0

    /** Total number of DC motor channels supported by a single Lynx module (4). */
    const val NUMBER_OF_MOTORS: Int = 4

    /** Starting zero-based port index for PWM servo channels on a Lynx module. */
    const val INITIAL_SERVO_PORT: Int = 0

    /** Total number of PWM servo channels supported by a single Lynx module (6). */
    const val NUMBER_OF_SERVOS: Int = 6

    /**
     * Determines whether the supplied [serialNumber] corresponds to an internal embedded Control Hub.
     *
     * In desktop simulation, always returns `false` unless explicitly overridden by fixture harnesses.
     *
     * @param serialNumber The module serial number to evaluate.
     * @return `true` if embedded, `false` otherwise.
     */
    fun isEmbeddedSerialNumber(serialNumber: com.qualcomm.robotcore.util.SerialNumber): Boolean = false
}
