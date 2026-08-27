package com.areslib.simulation

import com.areslib.util.RobotClock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeterministicSimulationSchedulerTest {
    @AfterTest
    fun restoreClock() = RobotClock.useSystemTime()

    @Test
    fun `ticks participants in stable order on one RobotClock timeline`() {
        val events = mutableListOf<String>()
        val scheduler = DeterministicSimulationScheduler(
            stepNanos = 20_000_000L,
            participants = listOf(
                SimulationTickParticipant { events += "read:$it:${RobotClock.nanoTime()}" },
                SimulationTickParticipant { events += "control:$it:${RobotClock.nanoTime()}" },
            ),
        )

        scheduler.step(2)

        assertEquals(2L, scheduler.tickSequence)
        assertEquals(40_000_000L, scheduler.timestampNanos)
        assertEquals(
            listOf(
                "read:20000000:20000000",
                "control:20000000:20000000",
                "read:40000000:40000000",
                "control:40000000:40000000",
            ),
            events,
        )
    }

    @Test
    fun `rejects a step RobotClock cannot represent exactly`() {
        assertFailsWith<IllegalArgumentException> {
            DeterministicSimulationScheduler(1_500_000L, emptyList())
        }
    }
}
