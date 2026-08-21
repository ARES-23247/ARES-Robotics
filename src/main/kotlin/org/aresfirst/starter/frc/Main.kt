package org.aresfirst.starter.frc

import edu.wpi.first.wpilibj.RobotBase

/** WPILib entry point for both desktop simulation and the RoboRIO. */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        RobotBase.startRobot(::AresStarterRobot)
    }
}
