package com.areslib.ftc.drivetrain

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FtcOdometryArbiterBoundaryAuditTest {
    @Test
    fun `all five event sequences obey immediate failover and consecutive recovery`() {
        val thresholds = intArrayOf(-3, 0, 1, 2, 5, Int.MAX_VALUE)
        for (threshold in thresholds) {
            // Six events: missing (healthy flag false/true), unhealthy, healthy, reset, force fallback.
            repeat(7776) { sequence ->
                val arbiter = FtcOdometrySourceArbiter(threshold)
                val required = if (threshold < 1) 1 else threshold
                var expected = FtcOdometrySource.UNINITIALIZED
                var remaining = required
                var encoded = sequence
                repeat(5) {
                    val event = encoded % 6
                    encoded /= 6
                    when (event) {
                        4 -> { arbiter.reset(); expected = FtcOdometrySource.UNINITIALIZED; remaining = required }
                        5 -> { arbiter.forceFallback(); expected = FtcOdometrySource.DRIVETRAIN_FALLBACK; remaining = required }
                        else -> {
                            val present = event >= 2
                            val healthy = event == 1 || event == 3
                            if (!present || !healthy) {
                                expected = FtcOdometrySource.DRIVETRAIN_FALLBACK
                                remaining = required
                            } else if (expected == FtcOdometrySource.UNINITIALIZED) {
                                expected = FtcOdometrySource.PINPOINT
                            } else if (expected == FtcOdometrySource.DRIVETRAIN_FALLBACK) {
                                remaining--
                                if (remaining == 0) {
                                    expected = FtcOdometrySource.PINPOINT
                                    remaining = required
                                }
                            }
                            assertEquals(expected, arbiter.update(present, healthy))
                        }
                    }
                    assertEquals(expected, arbiter.activeSource)
                    assertEquals(required - remaining, arbiter.healthyRecoverySamples)
                }
            }
        }
    }

    @Test
    fun `source transitions allocate no loop objects`() {
        val arbiter = FtcOdometrySourceArbiter(2)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        repeat(100_000) { arbiter.update(true, it % 3 != 0) }
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { arbiter.update(true, it % 3 != 0) }
        val bytes = bean.getThreadAllocatedBytes(threadId) - before
        assertTrue(bytes <= 4096L, "Source transitions allocated $bytes bytes")
        println("FTC odometry source transitions: $bytes bytes / 10,000 updates (desktop JVM)")
    }
}
