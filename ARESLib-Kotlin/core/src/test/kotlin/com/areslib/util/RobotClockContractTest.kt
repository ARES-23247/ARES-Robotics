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
        try {
            repeat(20_000) { iteration ->
                RobotClock.useSystemTime()
                RobotClock.useMockTime((iteration + 1).toLong())
            }
        } finally {
            running.set(false)
            reader.join(2_000L)
        }
        assertFalse(reader.isAlive, "Owned clock reader must terminate")
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

        assertTrue(t1 - t0 >= 0L, "A short nanoTime difference must be nonnegative, including signed wrap")
        assertTrue(m1 >= m0, "currentTimeMillis must advance monotonically")
    }

    @Test fun `mock conversion matches exact low 64 bits across signed millisecond range`() {
        val random = java.util.Random(6501)
        val million = java.math.BigInteger.valueOf(1_000_000)
        val values = listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, Long.MAX_VALUE / 1_000_000) +
            List(2000) { random.nextLong() }
        for (value in values) {
            RobotClock.useMockTime(value)
            assertEquals(value, RobotClock.currentTimeMillis())
            assertEquals(java.math.BigInteger.valueOf(value).multiply(million).toLong(), RobotClock.nanoTime())
        }
    }

    @Test fun `short mock intervals survive nanosecond sign wrap`() {
        for (origin in listOf(Long.MAX_VALUE / 1_000_000, Long.MIN_VALUE / 1_000_000 - 1,
            Long.MIN_VALUE, Long.MAX_VALUE - 1, -1L, 0L)) {
            RobotClock.useMockTime(origin)
            val first = RobotClock.nanoTime()
            RobotClock.useMockTime(origin + 1)
            assertEquals(1_000_000L, RobotClock.nanoTime() - first)
        }
    }

    @Test fun `each concurrent getter returns a fully published mock value`() {
        val first = 0x1111111122222222L
        val second = -0x3333333344444444L
        RobotClock.useMockTime(first)
        val running = AtomicBoolean(true)
        val invalid = AtomicBoolean(false)
        val started = java.util.concurrent.CountDownLatch(4)
        val readers = List(4) { Thread {
            started.countDown()
            while (running.get()) {
                val millis = RobotClock.currentTimeMillis()
                val nanos = RobotClock.nanoTime()
                if ((millis != first && millis != second) ||
                    (nanos != first * 1_000_000L && nanos != second * 1_000_000L)) invalid.set(true)
            }
        } }
        try {
            readers.forEach { it.start() }
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            repeat(20_000) { RobotClock.useMockTime(if (it % 2 == 0) first else second) }
        } finally {
            running.set(false); readers.forEach { it.join(2000) }
        }
        assertTrue(readers.none { it.isAlive }); assertFalse(invalid.get())
    }

    @Test fun `live milliseconds use the fixed monotonic anchor after mock mode ends`() {
        RobotClock.useMockTime(Long.MIN_VALUE)
        RobotClock.useSystemTime()
        val wall = RobotClock.javaClass.getDeclaredField("startWallMs").apply { isAccessible = true }.getLong(RobotClock)
        val anchor = RobotClock.javaClass.getDeclaredField("startNanos").apply { isAccessible = true }.getLong(RobotClock)
        val before = System.nanoTime()
        val actual = RobotClock.currentTimeMillis()
        val after = System.nanoTime()
        assertTrue(actual >= wall + (before - anchor) / 1_000_000L)
        assertTrue(actual <= wall + (after - anchor) / 1_000_000L)
    }
}
