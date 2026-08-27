package com.areslib.frc

import edu.wpi.first.hal.HAL
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import edu.wpi.first.wpilibj.simulation.XboxControllerSim
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ARESRobotTest {

    private companion object {
        const val LEFT_BUMPER_BUTTON = 5
        const val RIGHT_BUMPER_BUTTON = 6
    }

    private lateinit var robot: ARESRobot
    private lateinit var controllerSim: XboxControllerSim
    private lateinit var coPilotSim: XboxControllerSim

    @BeforeEach
    fun setUp() {
        assert(HAL.initialize(500, 0))
        DriverStationSim.setEnabled(true)
        controllerSim = XboxControllerSim(0)
        coPilotSim = XboxControllerSim(1)
        robot = ARESRobot()
    }

    @AfterEach
    fun tearDown() {
        robot.close()
    }

    @Test
    fun testRobotLifecycle() {
        robot.robotInit()

        // Test disabled
        DriverStationSim.setAutonomous(false)
        DriverStationSim.setEnabled(false)
        robot.robotPeriodic()
        robot.disabledInit()
        robot.disabledPeriodic()

        // Test autonomous
        DriverStationSim.setAutonomous(true)
        DriverStationSim.setEnabled(true)
        robot.autonomousInit()
        robot.autonomousPeriodic()
        robot.robotPeriodic()

        // Test teleop init
        DriverStationSim.setAutonomous(false)
        DriverStationSim.setEnabled(true)
        robot.teleopInit()
        robot.teleopPeriodic()
        robot.robotPeriodic()

        // Test various button configurations in teleop to cover all branches:
        // 1. backButton -> reset gyro
        controllerSim.setBackButton(true)
        robot.teleopPeriodic()
        controllerSim.setBackButton(false)

        // 2. xButton on copilot -> lock swerve
        coPilotSim.setXButton(true)
        robot.teleopPeriodic()
        coPilotSim.setXButton(false)

        // 3. rightTriggerAxis -> SOTM
        controllerSim.setRightTriggerAxis(0.8)
        robot.teleopPeriodic()
        controllerSim.setRightTriggerAxis(0.0)

        // 4. rightBumper -> Shuttle
        controllerSim.setRawButton(RIGHT_BUMPER_BUTTON, true)
        robot.teleopPeriodic()
        controllerSim.setRawButton(RIGHT_BUMPER_BUTTON, false)

        // 5. bButton -> static shoot
        controllerSim.setBButton(true)
        robot.teleopPeriodic()
        controllerSim.setBButton(false)

        // 6. copilot rt -> flywheel low speed
        coPilotSim.setRightTriggerAxis(0.8)
        robot.teleopPeriodic()
        coPilotSim.setRightTriggerAxis(0.0)

        // 7. copilot rb -> flywheel high speed
        coPilotSim.setRawButton(RIGHT_BUMPER_BUTTON, true)
        robot.teleopPeriodic()
        coPilotSim.setRawButton(RIGHT_BUMPER_BUTTON, false)

        // 8. aButton -> Start Slamtake
        controllerSim.setAButton(true)
        robot.teleopPeriodic()
        controllerSim.setAButton(false)

        // 9. leftBumper -> Unjam
        controllerSim.setRawButton(LEFT_BUMPER_BUTTON, true)
        robot.teleopPeriodic()
        controllerSim.setRawButton(LEFT_BUMPER_BUTTON, false)

    }
}
