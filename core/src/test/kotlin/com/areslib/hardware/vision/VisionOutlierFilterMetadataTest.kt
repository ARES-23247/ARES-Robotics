package com.areslib.hardware.vision

import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.VisionMeasurement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisionOutlierFilterMetadataTest {
    private val config = VisionFilterConfig.ftcDefaults().copy(
        minFieldX = -10.0,
        maxFieldX = 10.0,
        minFieldY = -10.0,
        maxFieldY = 10.0,
        maxDistanceMeters = 6.0
    )

    @Test
    fun `unavailable ambiguity is not replaced by a synthetic score`() {
        val measurement = validMeasurement().copy(
            ambiguity = Double.NaN,
            ambiguityAvailable = false
        )

        assertTrue(VisionOutlierFilter.isValid(config, measurement, 0.0, 0.0, 0.0))
    }

    @Test
    fun `camera reported impossible distance is rejected`() {
        val measurement = validMeasurement().copy(averageTagDistanceMeters = 6.1)

        assertFalse(VisionOutlierFilter.isValid(config, measurement, 0.0, 0.0, 0.0))
    }

    private fun validMeasurement() = VisionMeasurement(
        targetPose = Pose3d(Translation3d(0.5, 0.0, 0.0), Rotation3d()),
        tagId = 1,
        ambiguity = 0.01,
        robotPoseTargetSpace = Pose3d(Translation3d(0.0, 0.0, 2.0), Rotation3d())
    )
}
