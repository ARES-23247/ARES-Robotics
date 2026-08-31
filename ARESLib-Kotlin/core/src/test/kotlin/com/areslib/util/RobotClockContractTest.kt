package com.areslib.util

import com.areslib.action.RobotAction
import com.areslib.state.DriveMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicBoolean

class RobotClockContractTest {

    @AfterEach
    fun restoreSystemClock() {
        RobotClock.useSystemTime()
    }

    @Test
    fun `mock milliseconds and nanoseconds share one exact timeline`() {
        RobotClock.useSystemTime()
        RobotClock.useMockTime(1_234L)

        assertTrue(RobotClock.isMocked)
        assertEquals(1_234L, RobotClock.currentTimeMillis())
        assertEquals(1_234_000_000L, RobotClock.nanoTime())

        RobotClock.useMockTime(9_876L)
        assertEquals(9_876L, RobotClock.currentTimeMillis())
        assertEquals(9_876_000_000L, RobotClock.nanoTime())
    }

    @Test
    fun `default action timestamps are captured from RobotClock at construction`() {
        RobotClock.useMockTime(100L)
        val action = RobotAction.SetDriveMode(DriveMode.HEADING_HOLD)

        RobotClock.useMockTime(200L)

        assertEquals(100L, action.timestampMs)
        assertEquals(200L, RobotClock.currentTimeMillis())
    }

    @Test
    fun `useSystemTime exits mock mode`() {
        RobotClock.useMockTime(42L)
        RobotClock.useSystemTime()

        assertFalse(RobotClock.isMocked)
    }

    @Test
    fun `mode and injected timestamp publish coherently across threads`() {
        val running = AtomicBoolean(true)
        val sawTornInitialMock = AtomicBoolean(false)
        val reader = Thread {
            while (running.get()) {
                if (RobotClock.isMocked && RobotClock.currentTimeMillis() == 0L) {
                    sawTornInitialMock.set(true)
                }
            }
        }
        reader.start()
        repeat(20_000) { iteration ->
            RobotClock.useSystemTime()
            RobotClock.useMockTime((iteration + 1).toLong())
        }
        running.set(false)
        reader.join(2_000L)

        assertFalse(sawTornInitialMock.get(), "Mock mode must never publish before its timestamp")
    }

    @Test
    fun `mock time can advance and rewind deterministically`() {
        RobotClock.useMockTime(5_000L)
        assertEquals(5_000L, RobotClock.currentTimeMillis())
        assertEquals(5_000_000_000L, RobotClock.nanoTime())

        RobotClock.useMockTime(1_000L)
        assertEquals(1_000L, RobotClock.currentTimeMillis())
        assertEquals(1_000_000_000L, RobotClock.nanoTime())
    }

    @Test
    fun `system clock advances monotonically`() {
        RobotClock.useSystemTime()
        val t0 = RobotClock.nanoTime()
        val m0 = RobotClock.currentTimeMillis()
        Thread.sleep(10)
        val t1 = RobotClock.nanoTime()
        val m1 = RobotClock.currentTimeMillis()

        assertTrue(t1 >= t0, "nanoTime must advance monotonically")
        assertTrue(m1 >= m0, "currentTimeMillis must advance monotonically")
    }
}
