package com.areslib.ftc

import com.areslib.ftc.vision.FtcLimelightIO
import com.areslib.hardware.vision.VisionIOInputs
import com.qualcomm.hardware.limelightvision.LLResult
import com.qualcomm.hardware.limelightvision.Limelight3A
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D
import org.firstinspires.ftc.robotcore.external.navigation.Position
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FtcLimelightIOTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class FtcLimelightIOTest {

    /**
     * MockLLResult declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    class MockLLResult(
        private val valid: Boolean,
        private val botpose: Pose3D?
    ) : LLResult() {
        override fun isValid(): Boolean = valid
        override fun getBotpose(): Pose3D? = botpose
    }

    /**
     * MockLimelight3A declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    class MockLimelight3A(
        private val result: LLResult?
    ) : Limelight3A() {
        override fun getLatestResult(): LLResult? = result
    }

    @Test
    /**
     * testCoordinateTransformation declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testCoordinateTransformation() {
        // Mock FTC coordinates:
        // Position: X = 1.0 (right), Y = 2.0 (forward), Z = 0.5
        // Heading (Yaw) = 106 degrees (facing forward-ish, since straight forward on field is 90)
        // Pitch = 25 degrees (camera tilt)
        // Roll = 0 degrees
        val ftcPose = Pose3D(
            Position(DistanceUnit.METER, 1.0, 2.0, 0.5, 0),
            YawPitchRollAngles(AngleUnit.DEGREES, 106.0, 25.0, 0.0, 0)
        )

        val mockResult = MockLLResult(valid = true, botpose = ftcPose)
        val mockLimelight = MockLimelight3A(mockResult)
        val limelightIO = FtcLimelightIO(mockLimelight)

        val inputs = VisionIOInputs()
        limelightIO.updateInputs(inputs)

        assertTrue(inputs.isConnected)
        assertEquals(0, inputs.measurements.size)
    }

    @Test
    /**
     * testFiducialParsing declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testFiducialParsing() {
        val ftcPose = Pose3D(
            Position(DistanceUnit.METER, 1.0, 2.0, 0.5, 0),
            YawPitchRollAngles(AngleUnit.DEGREES, 0.0, 0.0, 0.0, 0)
        )
        val relativePose = Pose3D(
            Position(DistanceUnit.METER, 0.5, -0.3, 1.5, 0),
            YawPitchRollAngles(AngleUnit.DEGREES, 10.0, 20.0, 30.0, 0)
        )
        val mockFiducial = com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult(
            fiducialId = 1,
            tx = 5.0,
            ty = 2.0,
            pose3d = Pose3D(),
            robotPoseTargetSpace = relativePose
        )
        
        class MockFiducialLLResult(
            private val valid: Boolean,
            private val botpose: Pose3D?,
            private val fiducials: List<com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult>
        ) : LLResult() {
            override fun isValid(): Boolean = valid
            override fun getBotpose(): Pose3D? = botpose
            override fun getFiducialResults(): List<com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult> = fiducials
        }
        
        val mockResult = MockFiducialLLResult(valid = true, botpose = ftcPose, fiducials = listOf(mockFiducial))
        val mockLimelight = MockLimelight3A(mockResult)
        val limelightIO = FtcLimelightIO(mockLimelight)
        
        val inputs = VisionIOInputs()
        limelightIO.updateInputs(inputs)
        
        assertTrue(inputs.isConnected)
        assertEquals(1, inputs.measurements.size)
        
        val measurement = inputs.measurements[0]
        assertEquals(1, measurement.tagId)
        assertEquals(1, measurement.tagCount)
        
        // Verify relative target space pose fields
        assertEquals(0.5, measurement.robotPoseTargetSpace.x, 1e-6)
        assertEquals(-0.3, measurement.robotPoseTargetSpace.y, 1e-6)
        assertEquals(1.5, measurement.robotPoseTargetSpace.z, 1e-6)
        assertEquals(Math.toRadians(30.0), measurement.robotPoseTargetSpace.rotation.x, 1e-6)
        assertEquals(Math.toRadians(20.0), measurement.robotPoseTargetSpace.rotation.y, 1e-6)
        // Verify ambiguity score is below maxAmbiguity threshold (0.2) and passes outlier filter
        assertTrue(measurement.ambiguity < com.areslib.hardware.vision.VisionFilterConfig.ftcDefaults().maxAmbiguity)
        assertTrue(com.areslib.hardware.vision.VisionOutlierFilter.isValid(
            config = com.areslib.hardware.vision.VisionFilterConfig.ftcDefaults(),
            measurement = measurement,
            robotHeadingRad = 0.0,
            robotPose = com.areslib.math.geometry.Pose2d(1.0, 2.0, com.areslib.math.geometry.Rotation2d())
        ))
    }

    @Test
    fun `multi tag botpose is emitted once with contributing tag count`() {
        val fieldPose = Pose3D(
            Position(DistanceUnit.METER, 0.5, 0.25, 0.0, 0),
            YawPitchRollAngles(AngleUnit.DEGREES, 0.0, 0.0, 0.0, 0)
        )
        fun fiducial(id: Int, distanceMeters: Double) =
            com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult(
                fiducialId = id,
                tx = 0.0,
                ty = 0.0,
                pose3d = Pose3D(),
                robotPoseTargetSpace = Pose3D(
                    Position(DistanceUnit.METER, 0.0, 0.0, distanceMeters, 0),
                    YawPitchRollAngles(AngleUnit.DEGREES, 0.0, 0.0, 0.0, 0)
                )
            )

        val result = object : LLResult() {
            override fun isValid() = true
            override fun getBotpose() = fieldPose
            override fun getFiducialResults() = listOf(fiducial(4, 2.0), fiducial(7, 1.0))
        }
        val inputs = VisionIOInputs()
        FtcLimelightIO(MockLimelight3A(result)).updateInputs(inputs)

        assertEquals(1, inputs.measurements.size, "One camera pose must not be fused once per tag")
        assertEquals(2, inputs.measurements.single().tagCount)
        assertEquals(7, inputs.measurements.single().tagId, "Closest tag should represent target-space alignment")
    }
}
