package com.areslib.simulation

import com.areslib.util.RobotClock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationFaultTimelineTest {
    @AfterTest
    fun restoreClock() = RobotClock.useSystemTime()

    @Test
    fun `timeline follows RobotClock and releases bounded faults`() {
        val timeline = SimulationFaultTimeline(
            listOf(
                SimulationFaultCommand("encoder-stale", "drive.fl", SimulationFaultKind.STALE_INPUT, 100L, 200L),
            ),
        )

        RobotClock.useMockTime(99L)
        assertNull(timeline.activeFault("drive.fl"))
        RobotClock.useMockTime(100L)
        assertEquals(SimulationFaultKind.STALE_INPUT, timeline.activeFault("drive.fl")?.kind)
        RobotClock.useMockTime(200L)
        assertNull(timeline.activeFault("drive.fl"))
    }

    @Test
    fun `stable ordering resolves simultaneous faults deterministically`() {
        val timeline = SimulationFaultTimeline(
            listOf(
                SimulationFaultCommand("write", "motor", SimulationFaultKind.WRITE_REJECTED, 10L),
                SimulationFaultCommand("invalid", "motor", SimulationFaultKind.INVALID_INPUT, 10L),
            ),
        )

        assertEquals("invalid", timeline.activeFault("motor", 10L)?.commandId)
        assertTrue(timeline.isActive("motor", SimulationFaultKind.INVALID_INPUT, 10L))
        assertTrue(timeline.isActive("motor", SimulationFaultKind.WRITE_REJECTED, 10L))
    }

    @Test
    fun `invalid and duplicate commands are rejected during initialization`() {
        assertFailsWith<IllegalArgumentException> {
            SimulationFaultCommand("bad", "motor", SimulationFaultKind.FROZEN_INPUT, 10L, 10L)
        }
        assertFailsWith<IllegalArgumentException> {
            SimulationFaultTimeline(
                listOf(
                    SimulationFaultCommand("same", "a", SimulationFaultKind.BROWNOUT, 0L),
                    SimulationFaultCommand("same", "b", SimulationFaultKind.BUS_DISCONNECTED, 0L),
                ),
            )
        }
    }
}
