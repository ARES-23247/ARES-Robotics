package org.aresfirst.marvin

import com.areslib.frc.FrcSwerveRobot

import org.aresfirst.marvin.marvin.MarvinConfig
import org.aresfirst.marvin.marvin.SetFlywheelSpeed
import org.aresfirst.marvin.marvin.SetFlywheelActive
import org.aresfirst.marvin.marvin.LatchMechanismSafetyFault
import org.aresfirst.marvin.marvin.marvin
import org.aresfirst.marvin.robot.FRCTeleOpDriveController
import com.areslib.math.coordinate.CoordinateTransformers
import com.areslib.state.Alliance
import com.areslib.util.RobotClock
import edu.wpi.first.hal.AllianceStationID
import edu.wpi.first.hal.HAL
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import edu.wpi.first.wpilibj.simulation.XboxControllerSim
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ARESRobotTimedBehaviorRegressionTest {

    private var timedRobot: ARESRobot? = null

    @BeforeEach
    fun setUp() {
        assertTrue(HAL.initialize(500, 0))
        RobotClock.useMockTime(1_000L)
        DriverStationSim.resetData()
        DriverStationSim.setDsAttached(true)
        DriverStationSim.setEnabled(false)
        DriverStationSim.setAllianceStationId(AllianceStationID.Red1)
        DriverStationSim.notifyNewData()
        edu.wpi.first.wpilibj.smartdashboard.SmartDashboard.putString("Topology/HardwareMap", "")
    }

    @AfterEach
    fun tearDown() {
        timedRobot?.close()
        timedRobot = null
        DriverStationSim.resetData()
        RobotClock.useSystemTime()
    }

    @Test
    fun `TimedRobot propagates alliance and simulation ground truth into Redux state`() {
        val outer = ARESRobot()
        timedRobot = outer
        outer.robotInit()

        val facade = privateField<FrcSwerveRobot>(outer, "robot")
        val teleop = privateField<FRCTeleOpDriveController>(outer, "teleOpController")
        val sim = privateField<Dyn4jSimulation>(outer, "sim")

        assertEquals(Alliance.RED, facade.store.state.drive.alliance)
        assertEquals(MarvinConfig.FieldTargets.redSpeaker, teleop.speakerTranslation)

        sim.resetPose(4.0, 3.0, 0.75)
        RobotClock.useMockTime(1_050L)
        outer.simulationPeriodic()
        assertEquals(4.0, facade.store.state.drive.odometryX, 1e-6)
        assertEquals(3.0, facade.store.state.drive.odometryY, 1e-6)
        assertEquals(0.75, facade.store.state.drive.odometryHeading, 1e-6)

        DriverStationSim.setAllianceStationId(AllianceStationID.Blue1)
        DriverStationSim.notifyNewData()
        outer.robotPeriodic()
        assertEquals(Alliance.BLUE, facade.store.state.drive.alliance)
        assertEquals(MarvinConfig.FieldTargets.blueSpeaker, teleop.speakerTranslation)
    }

    @Test
    fun `teleop and test init synchronously apply alliance assigned after robot init`() {
        DriverStationSim.setAllianceStationId(AllianceStationID.Unknown)
        DriverStationSim.notifyNewData()
        val outer = ARESRobot()
        timedRobot = outer
        outer.robotInit()
        val facade = privateField<FrcSwerveRobot>(outer, "robot")
        val teleop = privateField<FRCTeleOpDriveController>(outer, "teleOpController")
        assertEquals(Alliance.BLUE, facade.store.state.drive.alliance)

        DriverStationSim.setAllianceStationId(AllianceStationID.Red1)
        DriverStationSim.notifyNewData()
        outer.teleopInit()
        assertEquals(Alliance.RED, facade.store.state.drive.alliance)
        assertEquals(MarvinConfig.FieldTargets.redSpeaker, teleop.speakerTranslation)
        val topologyJson = facade.telemetry.getString("Topology/HardwareMap", "")
        assertTrue(topologyJson.contains("\"robotId\":\"Marvin-XIX\""))
        assertTrue(topologyJson.contains("\"canBus\":\"CAN2\""))
        assertTrue(topologyJson.contains("\"canIds\":\"9,10,11,12\""))

        DriverStationSim.setAllianceStationId(AllianceStationID.Blue1)
        DriverStationSim.notifyNewData()
        outer.testInit()
        assertEquals(Alliance.BLUE, facade.store.state.drive.alliance)
        assertEquals(MarvinConfig.FieldTargets.blueSpeaker, teleop.speakerTranslation)
    }

    @Test
    fun `disabled init clears and latches stale mechanism targets`() {
        val outer = ARESRobot()
        timedRobot = outer
        outer.robotInit()
        val facade = privateField<FrcSwerveRobot>(outer, "robot")
        facade.store.dispatch(SetFlywheelSpeed(4_000.0))
        facade.store.dispatch(SetFlywheelActive(true))
        assertTrue(facade.store.state.superstructure.marvin.flywheelActive)

        outer.disabledInit()

        val disabled = facade.store.state.superstructure.marvin
        assertTrue(disabled.mechanismSafetyInhibited)
        assertFalse(disabled.flywheelActive)
        assertEquals(0.0, disabled.flywheel.targetVelocityRpm, 1e-9)
    }

    @Test
    fun `mode init preserves fault latch until dual operator disabled recovery`() {
        val outer = ARESRobot()
        timedRobot = outer
        outer.robotInit()
        val facade = privateField<FrcSwerveRobot>(outer, "robot")
        facade.store.dispatch(LatchMechanismSafetyFault("teleop controller failed"))

        outer.disabledInit()
        DriverStationSim.setAutonomous(true)
        DriverStationSim.setEnabled(true)
        DriverStationSim.notifyNewData()
        outer.autonomousInit()

        var marvin = facade.store.state.superstructure.marvin
        assertTrue(marvin.mechanismSafetyInhibited)
        assertTrue(marvin.mechanismSafetyFaultLatched)
        assertEquals("teleop controller failed", marvin.mechanismSafetyFaultReason)

        DriverStationSim.setAutonomous(false)
        DriverStationSim.setEnabled(false)
        DriverStationSim.notifyNewData()
        outer.disabledInit()
        val driver = XboxControllerSim(0)
        val operator = XboxControllerSim(1)
        driver.setBackButton(true)
        driver.setStartButton(true)
        operator.setBackButton(true)
        operator.setStartButton(true)
        DriverStationSim.notifyNewData()
        outer.disabledPeriodic()

        marvin = facade.store.state.superstructure.marvin
        assertFalse(marvin.mechanismSafetyFaultLatched)
        assertFalse(marvin.mechanismSafetyInhibited)
        assertEquals("", marvin.mechanismSafetyFaultReason)
    }

    @Test
    fun `canonical FRC field extents match official Crescendo dimensions`() {
        assertEquals(651.25 * 0.0254, CoordinateTransformers.FRC_FIELD_LENGTH, 1e-9)
        assertEquals(323.25 * 0.0254, CoordinateTransformers.FRC_FIELD_WIDTH, 1e-9)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(owner: ARESRobot, name: String): T {
        return ARESRobot::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(owner) as T
        }
    }
}
