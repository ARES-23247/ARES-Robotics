@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.hardware.lynx.commands.standard

import com.qualcomm.hardware.lynx.commands.LynxMessage
import com.qualcomm.hardware.lynx.LynxModule

/**
 * Class implementation for [LynxAck].
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators
 * into immutable Redux state representations.
 *
 * Represents a standard protocol acknowledgment packet returned by a REV Expansion Hub
 * or Control Hub in response to a command datagram.
 *
 * In desktop simulation, [LynxAck] confirms successful command reception and reports
 * the [isAttentionRequired] status flag, allowing simulated command handlers to verify
 * that communication leases and packet sequence numbers match protocol expectations.
 */
open class LynxAck : LynxMessage {
    /**
     * Constructs a [LynxAck] tied to the originating [module] and attention state.
     *
     * @param module The simulated [LynxModule] generating or receiving the acknowledgment.
     * @param isAttentionRequired Whether the module signals an internal diagnostic condition.
     */
    constructor(module: LynxModule, isAttentionRequired: Boolean)
}
