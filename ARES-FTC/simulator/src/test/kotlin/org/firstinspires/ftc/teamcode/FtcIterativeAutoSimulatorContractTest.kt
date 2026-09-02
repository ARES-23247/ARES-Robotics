package org.firstinspires.ftc.teamcode

import com.areslib.action.RobotAction
import com.areslib.ftc.FtcBaseRobot
import com.areslib.networktables.NT4Instance
import com.areslib.sim.model.MecanumRobotDouble
import com.areslib.sim.opmode.SimOpModeRunner
import com.areslib.sim.opmode.SimOpModeKind
import com.areslib.state.Alliance
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import org.firstinspires.ftc.teamcode.opmodes.ARESAuto
import org.firstinspires.ftc.teamcode.opmodes.TestAutoRed
import org.firstinspires.ftc.teamcode.subsystems.indicator_lights.IndicatorLightsSubsystem
import org.firstinspires.ftc.teamcode.subsystems.prism.PrismLightsSubsystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class FtcIterativeAutoSimulatorContractTest {
    @After
    fun cleanUp() {
        NT4Instance.defaultInstance.closeServer()
        RobotClock.useSystemTime()
    }

    @Test
    fun `canonical and locked iterative autos resolve without fallback substitution`() {
        listOf(ARESAuto::class.java, TestAutoRed::class.java).forEach { autoClass ->
            val lifecycle = requireNotNull(
                SimOpModeRunner.createOpModeInstance(null, autoClass.name),
                { "Simulator did not resolve ${autoClass.name}" },
            )
            assertEquals(autoClass, lifecycle.rawOpMode.javaClass)
            assertTrue(lifecycle.rawOpMode is OpMode)
            assertEquals(SimOpModeKind.AUTONOMOUS, lifecycle.modeKind)
        }
    }

    @Test
    fun `dashboard alliance synchronizes unlocked auto through init and start while lock wins`() {
        RobotClock.useMockTime(1_000L)
        val robotDouble = MecanumRobotDouble()
        val unlocked = requireNotNull(SimOpModeRunner.createOpModeInstance(ARESAuto(), null))
        try {
            unlocked.initialize(robotDouble.hardwareMap)
            val unlockedRobot = requireNotNull(FtcBaseRobot.activeInstance)
            unlockedRobot.store.dispatch(RobotAction.SetAlliance(Alliance.BLUE))
            unlocked.tick()

            assertEquals(Alliance.BLUE, unlockedRobot.store.state.drive.alliance)
            val seededPose = unlockedRobot.store.state.drive.poseEstimator.estimatedPose
            assertEquals(0.0, seededPose.x, 0.0)
            assertEquals(0.0, seededPose.y, 0.0)
            assertEquals(0.0, seededPose.heading.radians, 0.0)

            unlocked.start()
            assertEquals(
                "START must not reassert the selector's old RED default",
                Alliance.BLUE,
                unlockedRobot.store.state.drive.alliance,
            )
        } finally {
            unlocked.stop()
        }

        val locked = requireNotNull(SimOpModeRunner.createOpModeInstance(TestAutoRed(), null))
        try {
            locked.initialize(MecanumRobotDouble().hardwareMap)
            val lockedRobot = requireNotNull(FtcBaseRobot.activeInstance)
            lockedRobot.store.dispatch(RobotAction.SetAlliance(Alliance.BLUE))
            locked.tick()
            assertEquals(Alliance.RED, lockedRobot.store.state.drive.alliance)
            locked.start()
            assertEquals(Alliance.RED, lockedRobot.store.state.drive.alliance)
        } finally {
            locked.stop()
        }
    }

    @Test
    fun `Light Practice action-only auto selects runs and completes in the simulator`() {
        RobotClock.useMockTime(1_000L)
        val rawOpMode = ARESAuto()
        val lifecycle = requireNotNull(SimOpModeRunner.createOpModeInstance(rawOpMode, null))
        try {
            lifecycle.initialize(MecanumRobotDouble().hardwareMap)
            val robot = requireNotNull(FtcBaseRobot.activeInstance)

            rawOpMode.gamepad1.dpad_right = true
            lifecycle.tick()
            rawOpMode.gamepad1.dpad_right = false
            lifecycle.tick()

            assertEquals("test-auto", robot.telemetryManager.nt4.getString("ARES/Auto/Selected", ""))
            assertEquals("Ready", robot.telemetryManager.nt4.getString("ARES/Auto/Status", ""))

            lifecycle.start()
            repeat(150) {
                if (robot.telemetryManager.nt4.getString("ARES/Auto/Status", "") == "Complete") return@repeat
                RobotClock.useMockTime(RobotClock.currentTimeMillis() + 20L)
                lifecycle.tick()
            }

            assertEquals("Complete", robot.telemetryManager.nt4.getString("ARES/Auto/Status", ""))
            val indicatorState = IndicatorLightsSubsystem.state(robot.store.state)
            val prismState = PrismLightsSubsystem.state(robot.store.state)
            assertEquals(0.511, indicatorState.leftColor, 0.0)
            assertEquals(0.511, indicatorState.rightColor, 0.0)
            assertEquals(615, prismState.pattern)
        } finally {
            lifecycle.stop()
        }
    }
}
