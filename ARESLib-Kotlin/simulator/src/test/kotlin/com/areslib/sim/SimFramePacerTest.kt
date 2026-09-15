package com.areslib.sim

import com.areslib.util.RobotClock
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SimFramePacerTest {
    @Test fun `variable work and coarse wakeups do not accumulate drift`() {
        var now = 0L
        var waits = 0
        val pacer = SimFramePacer(SimHostClock { now }, SimHostWait { requested ->
            // Model a Windows timer that wakes on 15.625 ms boundaries.
            now = ((now + requested + 15_624_999L) / 15_625_000L) * 15_625_000L
            waits++
        })
        repeat(1_000) { frame ->
            now += (1L + frame % 7) * 1_000_000L
            pacer.awaitNextFrame()
        }
        assertTrue(now in 20_000_000_000L..20_015_625_000L)
        assertTrue(waits > 0)
        assertEquals(0L, pacer.rebasedPeriods)
    }

    @Test fun `long stall rebases instead of running a backlog of physics steps`() {
        var now = 0L
        val waits = mutableListOf<Long>()
        val pacer = SimFramePacer(SimHostClock { now }, SimHostWait { waits += it; now += it })
        now = 215_000_000L
        pacer.awaitNextFrame()
        assertTrue(waits.isEmpty())
        assertEquals(9L, pacer.rebasedPeriods)
        now += 3_000_000L
        pacer.awaitNextFrame()
        assertEquals(listOf(17_000_000L), waits)
        assertEquals(235_000_000L, now)
    }

    @Test fun `spurious wakeups and signed nano clock wrap preserve the deadline`() {
        val origin = Long.MAX_VALUE - 10_000_000L
        var now = origin
        var calls = 0
        val pacer = SimFramePacer(SimHostClock { now }, SimHostWait {
            calls++
            now += if (calls == 1) 1_000_000L else it
        })
        pacer.awaitNextFrame()
        assertEquals(2, calls)
        assertEquals(20_000_000L, now - origin)
    }

    @Test fun `real host pacing can be interrupted while the robot clock is frozen`() {
        val started = CountDownLatch(1)
        var interrupted = false
        RobotClock.useMockTime(123L)
        val worker = Thread {
            val pacer = SimFramePacer(periodNanos = TimeUnit.SECONDS.toNanos(30))
            started.countDown()
            try { pacer.awaitNextFrame() } catch (_: InterruptedException) { interrupted = true }
        }
        try {
            worker.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertTrue(interrupted)
            assertEquals(123L, RobotClock.currentTimeMillis())
        } finally {
            worker.interrupt()
            worker.join(2_000)
            RobotClock.useSystemTime()
        }
    }
}
