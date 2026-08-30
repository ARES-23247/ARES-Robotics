// ARES OWNERSHIP: GENERATED STARTER
package org.firstinspires.ftc.teamcode

import com.areslib.ftc.FtcBaseRobot
import com.areslib.networktables.NT4Instance
import com.areslib.sim.model.MecanumRobotDouble
import com.areslib.sim.opmode.SimOpModeKind
import com.areslib.sim.opmode.SimOpModeRunner
import com.areslib.util.RobotClock
import org.firstinspires.ftc.teamcode.opmodes.ARESStarterTeleOp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Proves the checked-in zero-code TeleOp executes against the desktop hardware double. */
class StarterSimulatorE2ETest {
    @After
    fun cleanUp() {
        NT4Instance.defaultInstance.closeServer()
        RobotClock.useSystemTime()
    }

    @Test
    fun `generated controls drive the generic mecanum simulation and stop safely`() {
        RobotClock.useMockTime(1_000L)
        val robotDouble = MecanumRobotDouble()
        val lifecycle = requireNotNull(
            SimOpModeRunner.createOpModeInstance(null, ARESStarterTeleOp::class.java.name),
        )

        assertEquals(SimOpModeKind.TELEOP, lifecycle.modeKind)
        try {
            lifecycle.initialize(robotDouble.hardwareMap)
            assertNotNull(FtcBaseRobot.activeInstance)
            lifecycle.tick()
            assertTrue(listOf(robotDouble.fl, robotDouble.fr, robotDouble.rl, robotDouble.rr).all { it.power == 0.0 })

            lifecycle.start()
            lifecycle.gamepad1.left_stick_y = -1.0f
            RobotClock.useMockTime(1_020L)
            lifecycle.tick()

            assertTrue(
                "At least one canonical drive motor should receive the generated forward command",
                listOf(robotDouble.fl, robotDouble.fr, robotDouble.rl, robotDouble.rr).any { abs(it.power) > 0.01 },
            )
        } finally {
            lifecycle.stop()
        }

        assertNull(FtcBaseRobot.activeInstance)
        assertTrue(listOf(robotDouble.fl, robotDouble.fr, robotDouble.rl, robotDouble.rr).all { it.power == 0.0 })
    }
}
