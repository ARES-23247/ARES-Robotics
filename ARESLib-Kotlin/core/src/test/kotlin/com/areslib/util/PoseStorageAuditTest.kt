package com.areslib.util

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.state.Alliance
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PoseStorageAuditTest {
    private val original = PoseStorage.snapshot

    @AfterEach
    fun restoreStorage() {
        original?.let { PoseStorage.save(it.pose, it.alliance) } ?: PoseStorage.clear()
    }

    @Test
    fun `pose and alliance share one retained immutable snapshot`() {
        val pose = Pose2d(1.25, -0.85, Rotation2d(0.4))
        assertTrue(PoseStorage.save(pose, Alliance.BLUE))
        val saved = requireNotNull(PoseStorage.snapshot)
        assertSame(pose, saved.pose)
        assertEquals(Alliance.BLUE, saved.alliance)
        assertSame(saved, PoseStorage.snapshot)
        assertTrue(PoseStorage.save(Pose2d(-2.0, 3.0), Alliance.RED))
        assertSame(pose, saved.pose)
        assertEquals(Alliance.BLUE, saved.alliance)
        assertNotSame(saved, PoseStorage.snapshot)
    }

    @Test
    fun `clear discards the entire handoff without mutating retained snapshots`() {
        PoseStorage.save(Pose2d(7.0, -8.0, Rotation2d(2.0)), Alliance.BLUE)
        val retained = requireNotNull(PoseStorage.snapshot)
        PoseStorage.clear()
        PoseStorage.clear()
        assertNull(PoseStorage.snapshot)
        assertEquals(7.0, retained.pose.x)
        assertEquals(Alliance.BLUE, retained.alliance)
    }

    @Test
    fun `nonfinite coordinates or raw heading reject and invalidate an older handoff`() {
        for (invalid in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (pose in listOf(Pose2d(invalid, 2.0), Pose2d(1.0, invalid), Pose2d(1.0, 2.0, Rotation2d(invalid)))) {
                assertTrue(PoseStorage.save(Pose2d(4.0, 5.0), Alliance.BLUE))
                assertFalse(PoseStorage.save(pose, Alliance.RED))
                assertNull(PoseStorage.snapshot, "Invalid raw input must not survive angle normalization")
            }
        }
    }

    @Test
    fun `finite signed extremes are preserved without implicit angle rewriting`() {
        val pose = Pose2d(-Double.MAX_VALUE, Double.MIN_VALUE, Rotation2d(Double.MAX_VALUE))
        assertTrue(PoseStorage.save(pose, Alliance.RED))
        assertSame(pose, requireNotNull(PoseStorage.snapshot).pose)
    }

    @Test
    fun `concurrent publishers never expose mixed pose and alliance`() {
        val red = Pose2d(1.0, 2.0)
        val blue = Pose2d(-1.0, -2.0)
        PoseStorage.clear()
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val redWrites = workers.submit {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(20_000) { PoseStorage.save(red, Alliance.RED) }
            }
            val blueWrites = workers.submit {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(20_000) { PoseStorage.save(blue, Alliance.BLUE) }
            }
            start.countDown()
            repeat(40_000) {
                val observed = PoseStorage.snapshot
                if (observed != null) assertSame(if (observed.alliance == Alliance.RED) red else blue, observed.pose)
            }
            redWrites.get(5, TimeUnit.SECONDS)
            blueWrites.get(5, TimeUnit.SECONDS)
            val final = requireNotNull(PoseStorage.snapshot)
            assertSame(if (final.alliance == Alliance.RED) red else blue, final.pose)
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `repeated clear does not allocate replacement poses`() {
        repeat(100_000) { PoseStorage.clear() }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(10_000) { PoseStorage.clear() }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("PoseStorage clear: $allocated bytes / 10,000 warmed calls (desktop JVM)")
        assertTrue(allocated <= 4096L, "Clearing pose storage allocated $allocated bytes")
    }
}
