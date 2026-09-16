@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.ftccommon

import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl

/**
 * Class implementation for [FtcEventLoop].
 *
 * Robotics framework control component managing the simulated FTC event loop
 * and OpMode lifecycle transitions in desktop environments.
 *
 * In physical FTC robot controllers, `FtcEventLoop` bridges hardware host services,
 * USB event dispatching, gamepad inputs, and OpMode scheduling.
 *
 * In desktop simulation, this mock double provides access to [opModeManager]
 * so testing frameworks and simulator runners can register, initialize, and step
 * through autonomous and teleoperated OpMode loop iterations without Android OS services.
 */
open class FtcEventLoop {
    /**
     * The backing [OpModeManagerImpl] instance managing OpMode registration and lifecycle.
     */
    val opModeManager: OpModeManagerImpl = OpModeManagerImpl()

    /**
     * Constructs a default desktop simulation [FtcEventLoop].
     */
    constructor()
}
