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
        override val cameraPoses: List<Pose3d> = listOf(Pose3d(Translation3d(0.18, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0)))
    ) : VisionIO {
        override fun updateInputs(inputs: VisionIOInputs) {
            inputs.isConnected = connected
            inputs.measurements = measurementsList
            inputs.cameraPoses = cameraPoses
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
        assertEquals(2, inputs.cameraPoses.size) // Defaults
    }
}
