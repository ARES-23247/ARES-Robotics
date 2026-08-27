package com.areslib.frc

import edu.wpi.first.wpilibj.RobotBase

/**
 * JVM entry point used by WPILib on both the RoboRIO and desktop simulation.
 *
 * Keep initialization inside [ARESRobot.robotInit]; constructing hardware or starting services
 * here would run before WPILib has established the robot runtime and simulation/native bindings.
 */
object Main {
    /** Delegates robot ownership and lifecycle scheduling to WPILib. */
    @JvmStatic
    fun main(args: Array<String>) {
        RobotBase.startRobot { ARESRobot() }
    }
}
