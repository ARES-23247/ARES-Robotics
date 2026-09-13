package com.areslib.logging

import com.areslib.util.RobotClock
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRequestLimiterAuditTest {
    @AfterEach fun resetClock() = RobotClock.useSystemTime()

    @Test fun `concurrent requests share one exact burst budget`() {
        RobotClock.useMockTime(0)
        val limiter = LogRequestLimiter()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val ready = CountDownLatch(1)
            val results = (1..64).map { pool.submit(Callable { ready.await(); limiter.tryConsume("one-client") }) }
            ready.countDown()
            assertEquals(10, results.count { it.get() })
        } finally { pool.shutdownNow() }
    }

    @Test fun `fractional polling preserves exact credit boundaries`() {
        RobotClock.useMockTime(0)
        val limiter = LogRequestLimiter()
        repeat(10) { assertTrue(limiter.tryConsume("one-client")) }
        for (time in 10L..90L step 10L) {
            RobotClock.useMockTime(time)
            assertFalse(limiter.tryConsume("one-client"))
        }
        RobotClock.useMockTime(100)
        assertTrue(limiter.tryConsume("one-client"))
        assertFalse(limiter.tryConsume("one-client"))
    }

    @Test fun `monotonic nanosecond wrap retains refill behavior`() {
        val origin = 9_223_372_036_850L
        RobotClock.useMockTime(origin)
        val limiter = LogRequestLimiter()
        repeat(10) { assertTrue(limiter.tryConsume("one-client")) }
        RobotClock.useMockTime(origin + 100)
        assertTrue(limiter.tryConsume("one-client"))
        assertFalse(limiter.tryConsume("one-client"))
    }

    @Test fun `tracking pressure cannot reset an active clients burst`() {
        RobotClock.useMockTime(0)
        val limiter = LogRequestLimiter(2)
        repeat(10) { assertTrue(limiter.tryConsume("first")) }
        assertTrue(limiter.tryConsume("second"))
        assertFalse(limiter.tryConsume("third"))
        assertFalse(limiter.tryConsume("first"))
        RobotClock.useMockTime(60_000)
        assertTrue(limiter.tryConsume("third"))
        limiter.clear()
        assertTrue(limiter.tryConsume("first"))
    }
}
