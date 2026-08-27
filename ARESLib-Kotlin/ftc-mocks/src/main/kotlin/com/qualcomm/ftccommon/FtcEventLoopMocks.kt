@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.ftccommon

import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl

/**
 * Class implementation for Ftc Event Loop.
 *
 * Robotics framework control component.
 */
open class FtcEventLoop {
    val opModeManager: OpModeManagerImpl = OpModeManagerImpl()
}
