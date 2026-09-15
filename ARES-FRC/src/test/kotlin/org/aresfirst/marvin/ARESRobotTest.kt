package org.aresfirst.marvin

import com.areslib.frc.FrcSwerveRobot
import org.aresfirst.marvin.marvin.marvin
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
        assertTrue(HAL.initialize(500, 0))
        DriverStationSim.resetData()
        DriverStationSim.setDsAttached(true)
        DriverStationSim.setEnabled(true)
        controllerSim = XboxControllerSim(0)
        coPilotSim = XboxControllerSim(1)
        robot = ARESRobot()
    }

    @AfterEach
    fun tearDown() {
        try { if (::robot.isInitialized) robot.close() } finally { DriverStationSim.resetData() }
    }

    @Test
    fun testRobotLifecycle() {
        robot.robotInit()

        // Test disabled
        DriverStationSim.setAutonomous(false)
        DriverStationSim.setEnabled(false)
        DriverStationSim.notifyNewData()
        robot.robotPeriodic()
        robot.disabledInit()
        robot.disabledPeriodic()

        // Test autonomous
        DriverStationSim.setAutonomous(true)
        DriverStationSim.setEnabled(true)
        DriverStationSim.notifyNewData()
        robot.autonomousInit()
        robot.autonomousPeriodic()
        robot.robotPeriodic()

        // Test teleop init
        DriverStationSim.setAutonomous(false)
        DriverStationSim.setEnabled(true)
        DriverStationSim.notifyNewData()
        robot.teleopInit()
        teleopFrame()
        robot.robotPeriodic()

        // Smoke-test sampled button configurations; dedicated controller tests assert their effects:
        // 1. backButton -> reset gyro
        controllerSim.setBackButton(true)
        teleopFrame()
        controllerSim.setBackButton(false)

        // 2. xButton on copilot -> lock swerve
        coPilotSim.setXButton(true)
        teleopFrame()
        coPilotSim.setXButton(false)

        // 3. rightTriggerAxis -> SOTM
        controllerSim.setRightTriggerAxis(0.8)
        teleopFrame()
        controllerSim.setRightTriggerAxis(0.0)

        // 4. rightBumper -> Shuttle
        controllerSim.setRawButton(RIGHT_BUMPER_BUTTON, true)
        teleopFrame()
        controllerSim.setRawButton(RIGHT_BUMPER_BUTTON, false)

        // 5. bButton -> static shoot
        controllerSim.setBButton(true)
        teleopFrame()
        controllerSim.setBButton(false)

        // 6. copilot rt -> flywheel low speed
        coPilotSim.setRightTriggerAxis(0.8)
        teleopFrame()
        coPilotSim.setRightTriggerAxis(0.0)

        // 7. copilot rb -> flywheel high speed
        coPilotSim.setRawButton(RIGHT_BUMPER_BUTTON, true)
        teleopFrame()
        coPilotSim.setRawButton(RIGHT_BUMPER_BUTTON, false)

        // 8. aButton -> Start Slamtake
        controllerSim.setAButton(true)
        teleopFrame()
        controllerSim.setAButton(false)

        // 9. leftBumper -> Unjam
        controllerSim.setRawButton(LEFT_BUMPER_BUTTON, true)
        teleopFrame()
        controllerSim.setRawButton(LEFT_BUMPER_BUTTON, false)

    }

    private fun teleopFrame() {
        DriverStationSim.notifyNewData()
        robot.robotPeriodic() // Refresh the same snapshots consumed by the hand controller.
        robot.teleopPeriodic()
        val facade = ARESRobot::class.java.getDeclaredField("robot").run {
            isAccessible = true
            get(robot) as FrcSwerveRobot
        }
        val mechanism = facade.store.state.superstructure.marvin
        assertFalse(mechanism.mechanismSafetyFaultLatched, mechanism.mechanismSafetyFaultReason)
    }
}
