package com.areslib.hardware.vision

import com.areslib.math.geometry.*
import com.areslib.math.wrapAngle
import com.areslib.state.VisionMeasurement
import kotlin.test.*

class VisionFilterBoundaryAuditTest {
    private fun config(scale: Double = 10.0) = VisionFilterConfig(
        minFieldX = -scale, maxFieldX = scale, minFieldY = -scale, maxFieldY = scale,
        robotLengthMeters = 0.0, robotWidthMeters = 0.0, fieldBoundsToleranceMeters = 0.0,
        maxDistanceMeters = scale, maxRotationDeviationRad = 0.1
    )
    private fun observation(x: Double = 0.0, yaw: Double = 0.0) = VisionMeasurement(
        targetPose = Pose3d(Translation3d(x, 0.0, 0.0), Rotation3d(0.0, 0.0, yaw)), tagId = 1
    )
    private fun valid(c: VisionFilterConfig, m: VisionMeasurement, heading: Double = 0.0) =
        VisionOutlierFilter.isValid(c, m, heading, 0.0, 0.0)

    @Test fun `large finite translation within configured limits remains valid`() {
        assertTrue(valid(config(2e200), observation(1e200)))
    }

    @Test fun `subnormal squared translation cannot hide a distance violation`() {
        assertFalse(valid(config(1.0).copy(maxDistanceMeters = 1e-201), observation(1e-200)))
    }

    @Test fun `gyro heading is normalized before subtraction loses the observation angle`() {
        val heading = 1e20
        assertTrue(valid(config(), observation(yaw = wrapAngle(heading)), heading))
        assertFalse(valid(config(), observation(yaw = wrapAngle(heading) + 0.2), heading))
    }

    @Test fun `nonunit and zero quaternions cannot masquerade as valid measured rotations`() {
        for (q in listOf(Quaternion(0.0, 0.0, 0.0, 0.0), Quaternion(2.0, 0.0, 0.0, 0.0))) {
            val m = observation().apply { targetPose.rotation.q = q }
            assertFalse(valid(config(), m))
            assertFalse(VisionOutlierFilter.isPoseWithinFieldBounds(config(), m.targetPose))
        }
    }

    @Test fun `nonfinite reported metadata and empty solves fail closed`() {
        val invalid = listOf(
            observation().copy(averageTagDistanceMeters = Double.NaN),
            observation().copy(tagSpanMeters = Double.POSITIVE_INFINITY),
            observation().copy(averageTagAreaPercent = Double.NaN),
            observation().copy(ambiguity = -0.1, ambiguityAvailable = true),
            observation().copy(latencyMs = Double.NaN),
            observation().copy(latencyMs = -1.0),
            observation().copy(tagCount = 0)
        )
        for (m in invalid) assertFalse(valid(config(), m), "$m")
    }

    @Test fun `finite shock magnitude does not overflow during squaring`() {
        assertTrue(VisionOutlierFilter.isValid(config().copy(maxAccelerationG = 2e200), observation(),
            0.0, 0.0, 0.0, linearAccelXG = 1e200, linearAccelYG = 1e200))
    }

    @Test fun `tiny shock cannot disappear through squared underflow`() {
        assertFalse(VisionOutlierFilter.isValid(config().copy(maxAccelerationG = 1e-201), observation(),
            0.0, 0.0, 0.0, linearAccelXG = 1e-200))
    }

    @Test fun `overflowing footprint and tolerance sums cannot turn an outside corner into equality`() {
        val c = config(Double.MAX_VALUE).copy(robotLengthMeters = Double.MAX_VALUE, fieldBoundsToleranceMeters = Double.MAX_VALUE * 0.1)
        assertFalse(VisionOutlierFilter.isPoseWithinFieldBounds(c, observation(Double.MAX_VALUE).targetPose))
    }

    @Test fun `generic FRC defaults do not silently impose a retired season tag range`() {
        assertTrue(valid(VisionFilterConfig.frcDefaults(), observation().copy(tagId = 32)))
        assertFalse(valid(VisionFilterConfig.frcDefaults().copy(allowedTagIds = setOf(1, 2)), observation().copy(tagId = 32)))
    }

    @Test fun `positive minimum footprint cannot fit in a zero-width field`() {
        val c = config().copy(minFieldX = 0.0, maxFieldX = 0.0, robotLengthMeters = Double.MIN_VALUE)
        assertFalse(VisionOutlierFilter.isPoseWithinFieldBounds(c, observation().targetPose))
        assertTrue(VisionOutlierFilter.isPoseWithinFieldBounds(c.copy(minFieldX = -Double.MIN_VALUE, maxFieldX = Double.MIN_VALUE), observation().targetPose))
    }

    @Test fun `rotated footprint agrees with explicit corner transforms`() {
        val random = java.util.Random(208L)
        val c = config().copy(minFieldX = -2.0, maxFieldX = 2.0, minFieldY = -2.0, maxFieldY = 2.0,
            robotLengthMeters = 0.6, robotWidthMeters = 0.4, fieldBoundsToleranceMeters = 0.01)
        repeat(1000) {
            val x = random.nextDouble() * 5.0 - 2.5
            val y = random.nextDouble() * 5.0 - 2.5
            val heading = random.nextDouble() * 2.0 * Math.PI - Math.PI
            var expected = true
            for (localX in listOf(-0.3, 0.3)) for (localY in listOf(-0.2, 0.2)) {
                val cornerX = x + localX * kotlin.math.cos(heading) - localY * kotlin.math.sin(heading)
                val cornerY = y + localX * kotlin.math.sin(heading) + localY * kotlin.math.cos(heading)
                expected = expected && cornerX in -2.01..2.01 && cornerY in -2.01..2.01
            }
            val pose = Pose3d(Translation3d(x, y, 0.0), Rotation3d(0.0, 0.0, heading))
            assertEquals(expected, VisionOutlierFilter.isPoseWithinFieldBounds(c, pose))
        }
    }

    @Test fun `unknown finite geometry metadata and explicitly unavailable ambiguity remain supported`() {
        val m = observation().copy(tagSpanMeters = -3.0, averageTagAreaPercent = -2.0,
            averageTagDistanceMeters = -1.0, ambiguity = Double.NaN, ambiguityAvailable = false)
        assertTrue(valid(config(), m))
    }

    @Test fun `configuration copies recompute scalar validity`() {
        val original = config()
        val invalid = original.copy(maxDistanceMeters = Double.NaN)
        assertTrue(valid(original, observation()))
        assertFalse(valid(invalid, observation()))
        assertTrue(valid(invalid.copy(maxDistanceMeters = 1.0), observation()))
    }
}
