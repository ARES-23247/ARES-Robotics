@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.hardware.lynx.commands.core

import com.qualcomm.hardware.lynx.commands.LynxCommand
import com.qualcomm.hardware.lynx.commands.standard.LynxAck

/**
 * Class implementation for [LynxSetMotorConstantPowerCommand].
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators
 * into immutable Redux state representations.
 *
 * Transmits a raw open-loop PWM duty cycle command to an Expansion Hub motor channel.
 * In desktop simulation, this command double allows low-level motor controller logic
 * to be verified against packet decoders without physical motor hardware.
 */
open class LynxSetMotorConstantPowerCommand : LynxCommand<LynxAck> {
    /** Constructs a default [LynxSetMotorConstantPowerCommand]. */
    constructor()
}

/**
 * Class implementation for [LynxSetServoPulseWidthCommand].
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators
 * into immutable Redux state representations.
 *
 * Sets the commanded PWM microsecond pulse width on an Expansion Hub servo channel.
 * In desktop simulation, this command double enables servo positioning checks
 * and range clamping verification in headless test runs.
 */
open class LynxSetServoPulseWidthCommand : LynxCommand<LynxAck> {
    /** Constructs a default [LynxSetServoPulseWidthCommand]. */
    constructor()
}
