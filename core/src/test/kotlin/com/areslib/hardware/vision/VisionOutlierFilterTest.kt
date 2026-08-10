package com.areslib.hardware.vision

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.VisionMeasurement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * VisionOutlierFilterTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class VisionOutlierFilterTest {

    private val filter = VisionOutlierFilter()
    private val robotPose = Pose2d(0.0, 0.0)
    private val robotHeadingRad = 0.0

    @Test
    /**
     * testValidMeasurement declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testValidMeasurement() {
        val measurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(2.0, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.05)),
            tagId = 1,
            ambiguity = 0.05
        )
        assertTrue(filter.isValid(measurement, robotHeadingRad, robotPose))
    }

    @Test
    /**
     * testDistanceRejection declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testDistanceRejection() {
        // Distance is 7.0 meters (exceeds max distance of 6.0)
        val measurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(7.0, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1,
            ambiguity = 0.05
        )
        assertFalse(filter.isValid(measurement, robotHeadingRad, robotPose))
    }

    @Test
    /**
     * testAmbiguityRejection declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testAmbiguityRejection() {
        // Ambiguity is 0.3 (exceeds max of 0.2)
        val measurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(2.0, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1,
            ambiguity = 0.3
        )
        assertFalse(filter.isValid(measurement, robotHeadingRad, robotPose))
    }

    @Test
    fun `nonfinite measurements and motion inputs fail closed`() {
        val nanPose = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(
                Translation3d(Double.NaN, 0.0, 0.0),
                Rotation3d()
            ),
            tagId = 1,
            ambiguity = 0.05
        )
        val validPose = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(1.0, 0.0, 0.0), Rotation3d()),
            tagId = 1,
            ambiguity = 0.05
        )

        assertFalse(filter.isValid(nanPose, robotHeadingRad, robotPose))
        assertFalse(filter.isValid(validPose.copy(ambiguity = Double.NaN), robotHeadingRad, robotPose))
        assertFalse(filter.isValid(validPose, Double.NaN, robotPose))
        assertFalse(filter.isValid(validPose, robotHeadingRad, robotPose, angularVelocityRadPerSec = Double.POSITIVE_INFINITY))
    }

    @Test
    /**
     * testHeadingRejection declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testHeadingRejection() {
        // Heading deviation is 35 degrees (exceeds max of 30 degrees)
        val deviationRad = Math.toRadians(35.0)
        val measurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(2.0, 0.0, 0.0), Rotation3d(0.0, 0.0, deviationRad)),
            tagId = 1,
            ambiguity = 0.05
        )
        assertFalse(filter.isValid(measurement, robotHeadingRad, robotPose))
    }

    @Test
    /**
     * test3DFieldBoundaryRejections declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun test3DFieldBoundaryRejections() {
        // X out of bounds (> 2.5)
        val outOfBoundsX = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(2.6, 0.0, 0.2), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1,
            ambiguity = 0.05
        )
        assertFalse(filter.isValid(outOfBoundsX, robotHeadingRad, robotPose))

        // Y out of bounds (< -2.5)
        val outOfBoundsY = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(0.0, -2.6, 0.2), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1,
            ambiguity = 0.05
        )
        assertFalse(filter.isValid(outOfBoundsY, robotHeadingRad, robotPose))

        // Z underground (< -0.2)
        val undergroundZ = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(0.0, 0.0, -0.25), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1,
            ambiguity = 0.05
        )
        assertFalse(filter.isValid(undergroundZ, robotHeadingRad, robotPose))

        // Z floating (> 1.0)
        val floatingZ = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(0.0, 0.0, 1.05), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1,
            ambiguity = 0.05
        )
        assertFalse(filter.isValid(floatingZ, robotHeadingRad, robotPose))
    }

    @Test
    /**
     * testAngularVelocityBlurLockout declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testAngularVelocityBlurLockout() {
        val measurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(1.0, 0.0, 0.2), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1,
            ambiguity = 0.05
        )

        // Spin too fast (2.1 rad/s > 2.0 rad/s limit)
        assertFalse(filter.isValid(measurement, robotHeadingRad, robotPose, angularVelocityRadPerSec = 2.1))
        
        // Spin under limit (1.9 rad/s <= 2.0 rad/s limit)
        assertTrue(filter.isValid(measurement, robotHeadingRad, robotPose, angularVelocityRadPerSec = 1.9))
    }

    @Test
    /**
     * testHighGShockCollisionLockout declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testHighGShockCollisionLockout() {
        val measurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(1.0, 0.0, 0.2), Rotation3d(0.0, 0.0, 0.0)),
            tagId = 1,
            ambiguity = 0.05
        )

        // Rest case (1G Z gravity, 0G lateral) should pass
        assertTrue(filter.isValid(measurement, robotHeadingRad, robotPose, linearAccelXG = 0.0, linearAccelYG = 0.0, linearAccelZG = 1.0))

        // Dynamic shock in X/Y exceeding 2.5G: X=2.6G should fail
        assertFalse(filter.isValid(measurement, robotHeadingRad, robotPose, linearAccelXG = 2.6, linearAccelYG = 0.0, linearAccelZG = 1.0))

        // Dynamic shock in Z exceeding 2.5G: Z=3.6G (dynamic Z = 2.6G) should fail
        assertFalse(filter.isValid(measurement, robotHeadingRad, robotPose, linearAccelXG = 0.0, linearAccelYG = 0.0, linearAccelZG = 3.6))

        // Under 2.5G limit (e.g. 2.0G dynamic shock) should pass
        assertTrue(filter.isValid(measurement, robotHeadingRad, robotPose, linearAccelXG = 2.0, linearAccelYG = 0.0, linearAccelZG = 1.0))
    }

    @Test
    /**
     * testFrcDefaults declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testFrcDefaults() {
        val frcFilter = VisionOutlierFilter(VisionFilterConfig.frcDefaults())
        
        // Measurement that exceeds FTC limits (e.g. X = 8.0m, Z = 2.0m, distance = 8.24m, yaw deviation = 20 deg)
        // but is valid within FRC presets.
        val frcMeasurement = VisionMeasurement(
            timestampMs = 100L,
            targetPose = Pose3d(Translation3d(8.0, 2.0, 2.0), Rotation3d(0.0, 0.0, Math.toRadians(20.0))),
            tagId = 5,
            ambiguity = 0.05
        )
        
        val robotPose = Pose2d(0.0, 0.0)
        
        // Verify it passes FRC filter
        assertTrue(frcFilter.isValid(
            frcMeasurement, 
            robotHeadingRad = 0.0, 
            robotPose = robotPose,
            angularVelocityRadPerSec = 1.0,
            linearAccelXG = 1.0,
            linearAccelYG = 1.0,
            linearAccelZG = 1.0
        ))
        
        // Verify it fails standard (FTC) filter
        assertFalse(filter.isValid(
            frcMeasurement, 
            robotHeadingRad = 0.0, 
            robotPose = robotPose,
            angularVelocityRadPerSec = 1.0,
            linearAccelXG = 1.0,
            linearAccelYG = 1.0,
            linearAccelZG = 1.0
        ))
    }
}
