@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.hardware.lynx.commands.standard

import com.qualcomm.hardware.lynx.commands.LynxMessage
import com.qualcomm.hardware.lynx.LynxModule

/**
 * Class implementation for Lynx Ack.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
open class LynxAck : LynxMessage {
    constructor(module: LynxModule, isAttentionRequired: Boolean)
}
