@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.hardware.lynx.commands.core

import com.qualcomm.hardware.lynx.commands.LynxCommand
import com.qualcomm.hardware.lynx.commands.standard.LynxAck

/**
 * Class implementation for Lynx Set Motor Constant Power Command.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
open class LynxSetMotorConstantPowerCommand : LynxCommand<LynxAck>()
/**
 * Class implementation for Lynx Set Servo Pulse Width Command.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
open class LynxSetServoPulseWidthCommand : LynxCommand<LynxAck>()
