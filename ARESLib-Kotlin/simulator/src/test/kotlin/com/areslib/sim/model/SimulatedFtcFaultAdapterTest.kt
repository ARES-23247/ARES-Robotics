package com.areslib.sim.model

import com.areslib.ftc.drivetrain.MecanumHardwareIO
import com.areslib.hardware.HardwareRegistry
import com.areslib.simulation.SimulationFaultCommand
import com.areslib.simulation.SimulationFaultKind
import com.areslib.simulation.SimulationFaultTimeline
import com.areslib.util.RobotClock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulatedFtcFaultAdapterTest {
    @AfterTest
    fun restoreClock() = RobotClock.useSystemTime()

    @Test
    fun `motor freezes cached encoder input and reports stale health during scheduled interval`() {
        val motor = SimDcMotorEx(
            faultTimeline = timeline(SimulationFaultKind.STALE_INPUT, "ftc.drive.fl", 100L, 200L),
            faultTargetId = "ftc.drive.fl",
        )
        motor.currentPosition = 10
        motor.velocity = 2.0
        assertEquals(10, motor.currentPosition)
        assertEquals(2.0, motor.velocity)

        RobotClock.useMockTime(100L)
        motor.currentPosition = 99
        motor.velocity = 20.0
        assertEquals(10, motor.currentPosition)
        assertEquals(2.0, motor.velocity)
        assertTrue(motor.inputValid)
        assertFalse(motor.inputFresh)

        RobotClock.useMockTime(200L)
        assertEquals(99, motor.currentPosition)
        assertEquals(20.0, motor.velocity)
        assertTrue(motor.inputFresh)
    }

    @Test
    fun `write rejection reaches FTC drivetrain output latch and requires neutral recovery`() {
        val robot = MecanumRobotDouble(
            timeline(SimulationFaultKind.WRITE_REJECTED, "ftc.drive.fl", 100L, 200L),
        )
        val io = MecanumHardwareIO(robot.hardwareMap, HardwareRegistry())

        RobotClock.useMockTime(100L)
        io.setMotorPowers(0.5, 0.5, 0.5, 0.5)
        assertTrue(io.outputFaultLatched)
        assertFalse(io.recoverWithNeutral(), "neutral cannot be acknowledged while the write fault remains")

        RobotClock.useMockTime(200L)
        assertTrue(io.recoverWithNeutral())
        assertFalse(io.outputFaultLatched)
        assertEquals(0.0, robot.fl.power)
        assertEquals(0.0, robot.fr.power)
        io.close()
    }

    @Test
    fun `device and bus failures reject reads while brownout reduces available output`() {
        val commands = listOf(
            SimulationFaultCommand("disconnect", "ftc.drive.fl", SimulationFaultKind.DEVICE_DISCONNECTED, 100L, 150L),
            SimulationFaultCommand("bus", "ftc.control-hub", SimulationFaultKind.BUS_DISCONNECTED, 200L, 250L),
            SimulationFaultCommand("brownout", "ftc.power", SimulationFaultKind.BROWNOUT, 300L, 350L),
        )
        val motor = SimDcMotorEx(SimulationFaultTimeline(commands), "ftc.drive.fl")

        RobotClock.useMockTime(100L)
        assertFailsWith<IllegalStateException> { motor.currentPosition }
        assertFailsWith<IllegalStateException> { motor.power = 0.5 }

        RobotClock.useMockTime(200L)
        assertFailsWith<IllegalStateException> { motor.velocity }

        RobotClock.useMockTime(300L)
        motor.power = 1.0
        assertEquals(0.35, motor.power, 1e-9)
    }

    private fun timeline(
        kind: SimulationFaultKind,
        targetId: String,
        start: Long,
        end: Long,
    ) = SimulationFaultTimeline(
        listOf(SimulationFaultCommand("fault", targetId, kind, start, end)),
    )
}
