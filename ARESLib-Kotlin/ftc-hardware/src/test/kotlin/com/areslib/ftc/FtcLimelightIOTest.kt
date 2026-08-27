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
import org.junit.jupiter.api.Assertions.assertFalse
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
        private val result: LLResult?,
        private val connected: Boolean = true,
        private val timeSinceLastUpdateMs: Long = 0L
    ) : Limelight3A() {
        override fun getLatestResult(): LLResult? = result
        override fun isConnected(): Boolean = connected
        override fun getTimeSinceLastUpdate(): Long = timeSinceLastUpdateMs
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
        val limelightIO = FtcLimelightIO(mockLimelight, sourceId = "limelight-front")

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
            Position(DistanceUnit.METER, 1.0, 1.0, 0.5, 0),
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
        val limelightIO = FtcLimelightIO(mockLimelight, sourceId = "limelight-front")
        
        val inputs = VisionIOInputs()
        limelightIO.updateInputs(inputs)
        
        assertTrue(inputs.isConnected)
        assertEquals(1, inputs.measurements.size)
        
        val measurement = inputs.measurements[0]
        assertEquals(1, measurement.tagId)
        assertEquals(1, measurement.tagCount)
        assertEquals("limelight-front", measurement.sourceId)
        assertEquals(measurement.timestampMs * 1_000L, measurement.captureTimestampMicros)
        
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
            robotPose = com.areslib.math.geometry.Pose2d(1.0, 1.0, com.areslib.math.geometry.Rotation2d())
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

    @Test
    fun `MegaTag2 normal pose retains independent MegaTag1 recovery pose`() {
        val mt2 = Pose3D(
            Position(DistanceUnit.METER, 1.0, 0.2, 0.0, 0),
            YawPitchRollAngles(AngleUnit.DEGREES, 0.0, 0.0, 0.0, 0)
        )
        val mt1 = Pose3D(
            Position(DistanceUnit.METER, 1.1, 0.3, 0.0, 0),
            YawPitchRollAngles(AngleUnit.DEGREES, 60.0, 0.0, 0.0, 0)
        )
        val fiducial = com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult(
            fiducialId = 1,
            tx = 0.0,
            ty = 0.0,
            pose3d = Pose3D(),
            robotPoseTargetSpace = Pose3D(
                Position(DistanceUnit.METER, 0.0, 0.0, 1.0, 0),
                YawPitchRollAngles(AngleUnit.DEGREES, 0.0, 0.0, 0.0, 0)
            )
        )
        val result = object : LLResult() {
            override fun isValid() = true
            override fun getBotpose() = mt1
            override fun getBotpose_MT2() = mt2
            override fun getFiducialResults() = listOf(fiducial)
        }

        val inputs = VisionIOInputs()
        FtcLimelightIO(MockLimelight3A(result)).updateInputs(inputs)
        val measurement = inputs.measurements.single()

        assertEquals(1.0, measurement.targetPose.x, 1e-9)
        assertTrue(measurement.hasRecoveryPose)
        assertEquals(1.1, measurement.recoveryPose.x, 1e-9)
        assertEquals(Math.toRadians(60.0), measurement.recoveryPose.rotation.z, 1e-9)
        assertEquals(com.areslib.state.VisionSolverType.MEGATAG2, measurement.solverType)
    }

    @Test
    fun `capture timestamp quality metrics and unavailable ambiguity are preserved`() {
        val fieldPose = Pose3D(
            Position(DistanceUnit.METER, 1.2, 0.4, 0.0, 0),
            YawPitchRollAngles(AngleUnit.DEGREES, 20.0, 0.0, 0.0, 0)
        )
        val targetPose = Pose3D(
            Position(DistanceUnit.METER, 0.0, 0.0, 2.0, 0),
            YawPitchRollAngles(AngleUnit.DEGREES, 0.0, 0.0, 0.0, 0)
        )
        val fiducial = com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult(
            5, 0.0, 0.0, Pose3D(), targetPose
        )
        val result = object : LLResult(
            controlHubTimestampMs = 1_000L,
            targetingLatency = 30.0,
            captureLatency = 20.0
        ) {
            override fun isValid() = true
            override fun getBotpose_MT2() = fieldPose
            override fun getBotpose() = fieldPose
            override fun getBotposeTagCount() = 2
            override fun getBotposeSpan() = 1.4
            override fun getBotposeAvgDist() = 2.3
            override fun getBotposeAvgArea() = 4.2
            override fun getFiducialResults() = listOf(fiducial)
        }

        val inputs = VisionIOInputs()
        FtcLimelightIO(MockLimelight3A(result)).updateInputs(inputs)
        val measurement = inputs.measurements.single()

        assertEquals(950L, measurement.timestampMs)
        assertEquals(1_000L, measurement.frameId)
        assertEquals(50.0, measurement.latencyMs, 0.0)
        assertEquals(2, measurement.tagCount)
        assertEquals(1.4, measurement.tagSpanMeters, 0.0)
        assertEquals(2.3, measurement.averageTagDistanceMeters, 0.0)
        assertEquals(4.2, measurement.averageTagAreaPercent, 0.0)
        assertFalse(measurement.ambiguityAvailable)
    }

    @Test
    fun `stale or disconnected results are never emitted`() {
        val fieldPose = Pose3D(
            Position(DistanceUnit.METER, 1.0, 1.0, 0.0, 0),
            YawPitchRollAngles(AngleUnit.DEGREES, 0.0, 0.0, 0.0, 0)
        )
        val fiducial = com.qualcomm.hardware.limelightvision.LLResultTypes.FiducialResult(
            1, 0.0, 0.0, Pose3D(), Pose3D()
        )
        val staleResult = object : LLResult() {
            override fun isValid() = true
            override fun getStaleness() = 251L
            override fun getBotpose() = fieldPose
            override fun getFiducialResults() = listOf(fiducial)
        }
        val staleInputs = VisionIOInputs()
        FtcLimelightIO(MockLimelight3A(staleResult)).updateInputs(staleInputs)
        assertTrue(staleInputs.isConnected)
        assertTrue(staleInputs.measurements.isEmpty())

        val disconnectedInputs = VisionIOInputs()
        FtcLimelightIO(MockLimelight3A(staleResult, connected = false)).updateInputs(disconnectedInputs)
        assertFalse(disconnectedInputs.isConnected)
        assertTrue(disconnectedInputs.measurements.isEmpty())
    }
}
