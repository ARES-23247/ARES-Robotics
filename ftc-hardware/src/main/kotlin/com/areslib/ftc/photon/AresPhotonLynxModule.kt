package com.areslib.ftc.photon

import com.qualcomm.hardware.lynx.LynxModule
import com.qualcomm.hardware.lynx.LynxUnsupportedCommandException
import com.qualcomm.hardware.lynx.LynxUsbDevice
import com.qualcomm.hardware.lynx.commands.LynxCommand
import com.qualcomm.hardware.lynx.commands.LynxMessage
import com.qualcomm.hardware.lynx.commands.LynxRespondable
import java.util.concurrent.ConcurrentHashMap

/**
 * [LynxModule] wrapper that routes selected writes through [AresPhotonCore].
 *
 * When interception is disabled or unavailable, every operation delegates to the SDK implementation.
 * Intercepted commands retain the SDK network-transmission lock and real acknowledgement lifecycle.
 * Congestion or direct-write failure falls back to the normal SDK sender.
 */
class AresPhotonLynxModule(
    lynxUsbDevice: LynxUsbDevice?,
    moduleAddress: Int,
    isParent: Boolean,
    isUserModule: Boolean
) : LynxModule(lynxUsbDevice, moduleAddress, isParent, isUserModule) {

    /** Exposes the SDK unfinished-command map required by the interception path. */
    fun getUnfinishedCommandsMap(): ConcurrentHashMap<Int, LynxRespondable<LynxMessage>> {
        return unfinishedCommands
    }

    /** Delegates message-number allocation to [LynxModule]. */
    override fun getNewMessageNumber(): Byte {
        return super.getNewMessageNumber()
    }

    @Throws(InterruptedException::class, LynxUnsupportedCommandException::class)
    /** Routes cached/eligible commands through [AresPhotonCore], otherwise delegates to the SDK. */
    override fun sendCommand(command: LynxMessage) {
        if (!AresPhotonCore.isEnabled.get()) {
            super.sendCommand(command)
            return
        }
        if (command is LynxCommand<*>) {
            if (AresPhotonCore.shouldParallelize(command)) {
                val success = AresPhotonCore.registerSend(command)
                if (!success) {
                    super.sendCommand(command)
                }
                return
            }
        }
        super.sendCommand(command)
    }

    @Throws(InterruptedException::class)
    /** Preserves the SDK transmission lock for both direct and fallback sends. */
    override fun acquireNetworkTransmissionLock(message: LynxMessage) {
        super.acquireNetworkTransmissionLock(message)
    }

    @Throws(InterruptedException::class)
    /** Releases the SDK transmission lock. */
    override fun releaseNetworkTransmissionLock(message: LynxMessage) {
        super.releaseNetworkTransmissionLock(message)
    }
}
