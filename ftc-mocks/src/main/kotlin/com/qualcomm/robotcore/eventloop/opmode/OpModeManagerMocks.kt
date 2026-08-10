@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.eventloop.opmode

/**
 * Interface implementation for Op Mode Manager.
 *
 * Robotics framework control component.
 */
interface OpModeManager {
    val activeOpModeName: String
    companion object {
        const val DEFAULT_OP_MODE_NAME = "\$Stop\$Robot\$"
    }
}

/**
 * Interface implementation for Op Mode Manager Notifier.
 *
 * Robotics framework control component.
 */
interface OpModeManagerNotifier {
    fun registerListener(listener: Notifications)
    fun unregisterListener(listener: Notifications)

    /**
     * Interface implementation for Notifications.
     *
     * Robotics framework control component.
     */
    interface Notifications {
        fun onOpModePreInit(opMode: com.qualcomm.robotcore.eventloop.opmode.OpMode)
        fun onOpModePreStart(opMode: com.qualcomm.robotcore.eventloop.opmode.OpMode)
        fun onOpModePostStop(opMode: com.qualcomm.robotcore.eventloop.opmode.OpMode)
    }
}

/**
 * Class implementation for Op Mode Manager Impl.
 *
 * Robotics framework control component.
 */
open class OpModeManagerImpl : OpModeManager, OpModeManagerNotifier {
    override val activeOpModeName: String = ""
    override fun registerListener(listener: OpModeManagerNotifier.Notifications) {}
    override fun unregisterListener(listener: OpModeManagerNotifier.Notifications) {}
}
