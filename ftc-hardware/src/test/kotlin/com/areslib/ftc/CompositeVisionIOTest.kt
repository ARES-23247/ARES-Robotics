package com.areslib.ftc

import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.hardware.vision.CompositeVisionIO
import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Rotation3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompositeVisionIOTest {

    class MockVisionIO(
        private val connected: Boolean,
        private val measurementsList: List<VisionMeasurement>,
        override val cameraPoses: List<Pose3d> = emptyList()
    ) : VisionIO {
        var observedImuMode = Int.MIN_VALUE
        override fun updateInputs(inputs: VisionIOInputs) {
            inputs.isConnected = connected
            inputs.measurements = measurementsList
            inputs.cameraPoses = cameraPoses
        }

        override fun setImuMode(mode: Int) {
            observedImuMode = mode
        }
    }

    @Test
    fun testAggregateMeasurements() {
        val measurement1 = VisionMeasurement(timestampMs = 100L, tagId = 1)
        val measurement2 = VisionMeasurement(timestampMs = 200L, tagId = 2)
        val measurement3 = VisionMeasurement(timestampMs = 300L, tagId = 3)

        val pose1 = Pose3d(Translation3d(0.18, 0.0, 0.0), Rotation3d())
        val pose2 = Pose3d(Translation3d(-0.18, 0.0, Math.PI), Rotation3d())

        val io1 = MockVisionIO(connected = true, measurementsList = listOf(measurement1, measurement2), cameraPoses = listOf(pose1))
        val io2 = MockVisionIO(connected = false, measurementsList = emptyList(), cameraPoses = emptyList())
        val io3 = MockVisionIO(connected = true, measurementsList = listOf(measurement3), cameraPoses = listOf(pose2))

        val composite = CompositeVisionIO(listOf(io1, io2, io3))
        val inputs = VisionIOInputs()
        composite.updateInputs(inputs)

        assertTrue(inputs.isConnected)
        assertEquals(3, inputs.measurements.size)
        assertEquals(listOf(measurement1, measurement2, measurement3), inputs.measurements)
        
        assertEquals(2, inputs.cameraPoses.size)
        assertEquals(listOf(pose1, pose2), inputs.cameraPoses)
    }

    @Test
    fun testNoneConnected() {
        val io1 = MockVisionIO(connected = false, measurementsList = emptyList())
        val io2 = MockVisionIO(connected = false, measurementsList = emptyList())

        val composite = CompositeVisionIO(listOf(io1, io2))
        val inputs = VisionIOInputs()
        composite.updateInputs(inputs)

        assertFalse(inputs.isConnected)
        assertTrue(inputs.measurements.isEmpty())
        assertTrue(inputs.cameraPoses.isEmpty())
    }

    @Test
    fun `correlated camera frames keep only the best observation and forward IMU mode`() {
        val weaker = VisionMeasurement(
            timestampMs = 100L, sourceId = "left", tagCount = 1,
            averageTagDistanceMeters = 3.0, stdDevXMeters = 0.5, stdDevYMeters = 0.5
        )
        val stronger = VisionMeasurement(
            timestampMs = 105L, sourceId = "right", tagCount = 2,
            averageTagDistanceMeters = 2.0, stdDevXMeters = 0.2, stdDevYMeters = 0.2
        )
        val later = VisionMeasurement(timestampMs = 130L, sourceId = "left", tagCount = 1)
        val io1 = MockVisionIO(true, listOf(weaker, later), emptyList())
        val io2 = MockVisionIO(true, listOf(stronger), emptyList())
        val composite = CompositeVisionIO(listOf(io1, io2))
        val inputs = VisionIOInputs()

        composite.setImuMode(4)
        composite.updateInputs(inputs)

        assertEquals(4, io1.observedImuMode)
        assertEquals(4, io2.observedImuMode)
        assertEquals(listOf(stronger, later), inputs.measurements)
    }
}
