package com.areslib.hardware.vision

import com.areslib.math.geometry.Pose3d
import com.areslib.state.VisionMeasurement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VisionMeasurementBufferTest {

    @Test
    fun testChronologicalSorting() {
        val buffer = VisionMeasurementBuffer(maxHistoryMs = 1000L)

        val m1 = VisionMeasurement(timestampMs = 200L, targetPose = Pose3d(), tagId = 1, ambiguity = 0.1)
        val m2 = VisionMeasurement(timestampMs = 100L, targetPose = Pose3d(), tagId = 2, ambiguity = 0.2)
        val m3 = VisionMeasurement(timestampMs = 300L, targetPose = Pose3d(), tagId = 3, ambiguity = 0.05)

        buffer.addMeasurement(m1)
        buffer.addMeasurement(m2)
        buffer.addMeasurement(m3)

        val all = buffer.getAll()
        assertEquals(3, all.size)
        assertEquals(100L, all[0].timestampMs)
        assertEquals(200L, all[1].timestampMs)
        assertEquals(300L, all[2].timestampMs)
    }

    @Test
    fun testSlidingWindowEviction() {
        val buffer = VisionMeasurementBuffer(maxHistoryMs = 500L)

        // Add older measurements
        buffer.addMeasurement(VisionMeasurement(timestampMs = 100L, targetPose = Pose3d(), tagId = 1))
        buffer.addMeasurement(VisionMeasurement(timestampMs = 300L, targetPose = Pose3d(), tagId = 2))

        assertEquals(2, buffer.size)

        // Add a new measurement at 700. Cutoff is 700 - 500 = 200. Entry at 100 should be evicted.
        buffer.addMeasurement(VisionMeasurement(timestampMs = 700L, targetPose = Pose3d(), tagId = 3))

        val all = buffer.getAll()
        assertEquals(2, all.size)
        assertEquals(300L, all[0].timestampMs)
        assertEquals(700L, all[1].timestampMs)
    }

    @Test
    fun testQueryInterval() {
        val buffer = VisionMeasurementBuffer(maxHistoryMs = 2000L)

        buffer.addMeasurement(VisionMeasurement(timestampMs = 100L, targetPose = Pose3d()))
        buffer.addMeasurement(VisionMeasurement(timestampMs = 200L, targetPose = Pose3d()))
        buffer.addMeasurement(VisionMeasurement(timestampMs = 300L, targetPose = Pose3d()))
        buffer.addMeasurement(VisionMeasurement(timestampMs = 400L, targetPose = Pose3d()))

        val range = buffer.getMeasurementsBetween(200L, 350L)
        assertEquals(2, range.size)
        assertEquals(200L, range[0].timestampMs)
        assertEquals(300L, range[1].timestampMs)
    }

    @Test
    fun testClearOperations() {
        val buffer = VisionMeasurementBuffer(maxHistoryMs = 2000L)

        buffer.addMeasurement(VisionMeasurement(timestampMs = 100L, targetPose = Pose3d()))
        buffer.addMeasurement(VisionMeasurement(timestampMs = 200L, targetPose = Pose3d()))
        buffer.addMeasurement(VisionMeasurement(timestampMs = 300L, targetPose = Pose3d()))

        buffer.clearBefore(250L)
        val all = buffer.getAll()
        assertEquals(1, all.size)
        assertEquals(300L, all[0].timestampMs)
    }

    @Test
    fun testConcurrentAccess() {
        val buffer = VisionMeasurementBuffer(maxHistoryMs = 5000L)
        val executor = Executors.newFixedThreadPool(8)
        val numThreads = 8
        val entriesPerThread = 100

        for (t in 0 until numThreads) {
            executor.submit {
                for (i in 0 until entriesPerThread) {
                    val time = 1000L + (t * entriesPerThread) + i
                    buffer.addMeasurement(VisionMeasurement(timestampMs = time, targetPose = Pose3d(), tagId = t))
                }
            }
        }

        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))

        // Total elements might be slightly less if eviction occurred, but since maxHistoryMs is large (5000)
        // and times are in range 1000..1800, all should be kept.
        assertEquals(numThreads * entriesPerThread, buffer.size)

        // Verify sorted order holds completely
        val all = buffer.getAll()
        for (i in 0 until all.size - 1) {
            assertTrue(all[i].timestampMs <= all[i + 1].timestampMs)
        }
    }
}
