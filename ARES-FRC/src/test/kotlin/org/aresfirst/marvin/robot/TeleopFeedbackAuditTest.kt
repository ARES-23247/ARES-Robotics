package org.aresfirst.marvin.robot

import com.areslib.frc.FrcSwerveRobot
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import com.areslib.telemetry.GamepadState
import org.aresfirst.marvin.marvin.*
import edu.wpi.first.hal.HAL
import edu.wpi.first.wpilibj.GenericHID
import edu.wpi.first.wpilibj.XboxController
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TeleopFeedbackAuditTest {
    private class RecordingController(port: Int) : XboxController(port) {
        val writes = mutableListOf<Double>()
        var fail = false
        override fun setRumble(type: GenericHID.RumbleType, value: Double) {
            writes.add(value)
            if (fail) error("rumble failure")
        }
    }
    private lateinit var robot: FrcSwerveRobot
    private lateinit var driver: RecordingController
    private lateinit var copilot: RecordingController
    private lateinit var controls: FRCTeleOpDriveController
    private lateinit var copilotState: GamepadState

    @BeforeEach fun setup() {
        assertTrue(HAL.initialize(500, 0))
        robot = FrcSwerveRobot(isSimulation = true,
            initialState = RobotState(superstructure = SuperstructureState(custom = MarvinState())),
            reducer = MarvinReducer::reduce)
        driver = RecordingController(0)
        copilot = RecordingController(1)
        copilotState = GamepadState()
        controls = FRCTeleOpDriveController(robot, MarvinShooterSubsystem(robot.store),
            driver, copilot, GamepadState(), copilotState)
        controls.teleopInit()
    }
    @AfterEach fun cleanup() { if (::robot.isInitialized) robot.close() }

    @Test fun `first traction loss at clock origin immediately notifies both controllers`() {
        controls.updateTractionFeedback(true, 0L)
        assertEquals(listOf(1.0), driver.writes)
        assertEquals(driver.writes, copilot.writes)
    }

    @Test fun `pulse expires at one second and held traction loss sends no repeated zero writes`() {
        controls.updateTractionFeedback(true, 3000L)
        controls.updateTractionFeedback(true, 3999L)
        assertEquals(listOf(1.0), driver.writes)
        controls.updateTractionFeedback(true, 4000L)
        repeat(100) { controls.updateTractionFeedback(true, 4001L + it) }
        assertEquals(listOf(1.0, 0.0), driver.writes)
        assertEquals(driver.writes, copilot.writes)
    }

    @Test fun `cooldown starts at actual stop and does not slide while traction remains lost`() {
        controls.updateTractionFeedback(true, 3000L)
        controls.updateTractionFeedback(true, 4001L)
        controls.updateTractionFeedback(true, 8000L)
        controls.updateTractionFeedback(false, 8100L)
        controls.updateTractionFeedback(true, 8101L)
        assertEquals(listOf(1.0, 0.0, 1.0), driver.writes)
    }

    @Test fun `recovery stops a pulse and suppresses a new pulse until cooldown boundary`() {
        controls.updateTractionFeedback(true, 3000L)
        controls.updateTractionFeedback(false, 3500L)
        controls.updateTractionFeedback(true, 5499L)
        controls.updateTractionFeedback(false, 5499L)
        controls.updateTractionFeedback(true, 5500L)
        assertEquals(listOf(1.0, 0.0, 1.0), driver.writes)
    }

    @Test fun `clock rewind and signed elapsed overflow cannot leave rumble stuck on`() {
        controls.updateTractionFeedback(true, 3000L)
        controls.updateTractionFeedback(true, 2999L)
        assertEquals(listOf(1.0, 0.0), driver.writes)
        controls.teleopInit()
        driver.writes.clear()
        controls.updateTractionFeedback(true, Long.MIN_VALUE)
        controls.updateTractionFeedback(true, Long.MAX_VALUE)
        assertEquals(listOf(1.0, 0.0), driver.writes)
    }

    @Test fun `teleop init clears active rumble and stale assist ownership`() {
        copilotState.x = true
        controls.teleopPeriodic()
        assertTrue(controls.drivetrainAssistActive)
        controls.updateTractionFeedback(true, 3000L)
        controls.teleopInit()
        assertEquals(listOf(1.0, 0.0), driver.writes)
        assertFalse(controls.drivetrainAssistActive)
        controls.updateTractionFeedback(true, 0L)
        assertEquals(listOf(1.0, 0.0, 1.0), driver.writes)
    }

    @Test fun `controller fault attempts both rumble stops even when one output throws`() {
        controls.updateTractionFeedback(true, 3000L)
        driver.fail = true
        controls.latchControllerAllStop("feedback audit", IllegalStateException("trigger"))
        assertEquals(listOf(1.0, 0.0), driver.writes)
        assertEquals(driver.writes, copilot.writes)
        assertTrue(robot.store.state.superstructure.marvin.mechanismSafetyFaultLatched)
    }

    @Test fun `idle feedback does not write HID outputs and cooldown rewind has a bounded recovery`() {
        repeat(100) { controls.updateTractionFeedback(false, it.toLong()) }
        assertTrue(driver.writes.isEmpty())
        controls.updateTractionFeedback(true, 3000L)
        controls.updateTractionFeedback(false, 3500L)
        controls.updateTractionFeedback(false, 0L)
        controls.updateTractionFeedback(true, 1999L)
        controls.updateTractionFeedback(false, 1999L)
        controls.updateTractionFeedback(true, 2000L)
        assertEquals(listOf(1.0, 0.0, 1.0), driver.writes)
    }

    @Test fun `failed first rumble write does not skip the second controller`() {
        driver.fail = true
        assertThrows(IllegalStateException::class.java) { controls.updateTractionFeedback(true, 0L) }
        assertEquals(listOf(1.0), copilot.writes)
        controls.latchControllerAllStop("feedback start", IllegalStateException("start failed"))
        assertEquals(listOf(1.0, 0.0), copilot.writes)
    }
}
