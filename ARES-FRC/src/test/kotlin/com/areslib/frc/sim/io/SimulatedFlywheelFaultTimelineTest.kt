package com.areslib.frc.sim.io

import com.areslib.frc.Dyn4jSimulation
import com.areslib.simulation.SimulationFaultCommand
import com.areslib.simulation.SimulationFaultKind
import com.areslib.simulation.SimulationFaultTimeline
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimulatedFlywheelFaultTimelineTest {
    @AfterEach
    fun restoreClock() = RobotClock.useSystemTime()

    @Test
    fun `stale velocity remains observable but cannot authorize feeding`() {
        val sim = Dyn4jSimulation(seed = 42L)
        val io = SimulatedFlywheelIO(
            sim,
            timeline(SimulationFaultKind.STALE_INPUT, "frc.flywheel", 100L, 200L),
        )
        sim.flywheelSim.update(6.0, 0.02)
        val healthyVelocity = io.velocityRpm
        assertTrue(io.velocityValid)

        RobotClock.useMockTime(100L)
        sim.flywheelSim.update(12.0, 0.20)
        assertEquals(healthyVelocity, io.velocityRpm, 1e-9)
        assertFalse(io.velocityValid)

        RobotClock.useMockTime(200L)
        assertTrue(io.velocityValid)
        assertTrue(io.velocityRpm != healthyVelocity)
    }

    @Test
    fun `failed write and CAN disconnect neutralize output and report rejection`() {
        val commands = listOf(
            SimulationFaultCommand("write", "frc.flywheel", SimulationFaultKind.WRITE_REJECTED, 100L, 150L),
            SimulationFaultCommand("bus", "frc.can2", SimulationFaultKind.BUS_DISCONNECTED, 200L, 250L),
        )
        val sim = Dyn4jSimulation(seed = 42L)
        val io = SimulatedFlywheelIO(sim, SimulationFaultTimeline(commands))

        RobotClock.useMockTime(100L)
        io.setAppliedVoltage(9.0)
        assertFalse(io.lastWriteAccepted)
        assertEquals(0.0, sim.simFlywheelVoltage, 1e-9)

        RobotClock.useMockTime(200L)
        io.setVelocityRpm(4_000.0)
        assertFalse(io.lastWriteAccepted)
        assertFalse(io.velocityValid)
        assertEquals(0.0, sim.simFlywheelVoltage, 1e-9)

        RobotClock.useMockTime(250L)
        io.setAppliedVoltage(9.0)
        assertTrue(io.lastWriteAccepted)
        assertEquals(9.0, sim.simFlywheelVoltage, 1e-9)
    }

    @Test
    fun `brownout constrains the FRC simulated bus without changing FTC semantics`() {
        val sim = Dyn4jSimulation(seed = 42L)
        val io = SimulatedFlywheelIO(
            sim,
            timeline(SimulationFaultKind.BROWNOUT, "frc.power", 100L, 200L),
        )

        RobotClock.useMockTime(100L)
        io.setAppliedVoltage(12.0)
        assertEquals(4.2, sim.simFlywheelVoltage, 1e-9)

        RobotClock.useMockTime(200L)
        io.setAppliedVoltage(12.0)
        assertEquals(12.0, sim.simFlywheelVoltage, 1e-9)
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
