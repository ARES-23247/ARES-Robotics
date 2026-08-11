package com.areslib.frc

import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.VisionSolverType
import edu.wpi.first.networktables.NetworkTableInstance
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrcLimelightIOTest {
    @Test
    fun `official Limelight topics produce one coherent MegaTag2 observation`() {
        val instance = NetworkTableInstance.getDefault()
        val tableName = "limelight-contract-${System.nanoTime()}"
        val table = instance.getTable(tableName)
        val orientationSub = table.getDoubleArrayTopic("robot_orientation_set").subscribe(DoubleArray(0))
        val imuModeSub = table.getDoubleTopic("imumode_set").subscribe(Double.NaN)
        val cameraPoseSub = table.getDoubleArrayTopic("camerapose_robotspace_set").subscribe(DoubleArray(0))
        val tvPub = table.getDoubleTopic("tv").publish()
        val tidPub = table.getDoubleTopic("tid").publish()
        val heartbeatPub = table.getDoubleTopic("hb").publish()
        val mt1Pub = table.getDoubleArrayTopic("botpose_wpiblue").publish()
        val mt2Pub = table.getDoubleArrayTopic("botpose_orb_wpiblue").publish()
        val obsoleteMt2Pub = table.getDoubleArrayTopic("botpose_wpiblue_mt2").publish()
        val targetSpacePub = table.getDoubleArrayTopic("botpose_targetspace").publish()
        val stdDevsPub = table.getDoubleArrayTopic("stddevs").publish()
        val cameraPose = Pose3d(
            Translation3d(0.25, -0.1, 0.5),
            Rotation3d(Math.toRadians(1.0), Math.toRadians(-20.0), Math.toRadians(3.0))
        )
        val io = FrcLimelightIO(tableName, cameraPoses = listOf(cameraPose))

        try {
            tvPub.set(1.0)
            tidPub.set(7.0)
            heartbeatPub.set(12.0)
            mt1Pub.set(doubleArrayOf(
                1.1, 2.1, 0.0, 0.0, 0.0, 30.0, 20.0, 2.0, 1.0, 2.5, 3.0,
                5.0, 0.0, 0.0, 1.0, 2.8, 2.6, 0.04,
                7.0, 0.0, 0.0, 1.0, 3.0, 2.9, 0.08
            ))
            mt2Pub.set(doubleArrayOf(
                1.0, 2.0, 0.0, 0.0, 0.0, -80.0, 20.0, 2.0, 1.0, 2.5, 3.0,
                6.0, 0.0, 0.0, 1.0, 1.7, 1.5, 0.03,
                8.0, 0.0, 0.0, 1.0, 2.2, 2.0, 0.12
            ))
            obsoleteMt2Pub.set(doubleArrayOf(99.0, 99.0, 0.0, 0.0, 0.0, 0.0, 20.0, 2.0, 1.0, 2.5, 3.0))
            targetSpacePub.set(doubleArrayOf(0.2, 0.1, 2.4, 1.0, 2.0, 3.0))
            stdDevsPub.set(doubleArrayOf(0.3, 0.4, 0.0, 0.0, 0.0, 8.0, 0.1, 0.2, 0.0, 0.0, 0.0, 2.0))
            io.setOrientation(42.0, 5.0, 1.0, 2.0, 3.0, 4.0)
            io.setImuMode(1)

            val inputs = VisionIOInputs()
            io.updateInputs(inputs)
            val measurement = inputs.measurements.single()

            assertTrue(inputs.isConnected)
            assertEquals(VisionSolverType.MEGATAG2, measurement.solverType)
            assertEquals(1.0, measurement.targetPose.x, 0.0)
            assertEquals(2.0, measurement.targetPose.y, 0.0)
            assertEquals(2, measurement.tagCount)
            assertEquals(6, measurement.tagId, "Closest raw fiducial should identify the solve")
            assertEquals(1.0, measurement.tagSpanMeters, 0.0)
            assertEquals(2.5, measurement.averageTagDistanceMeters, 0.0)
            assertEquals(3.0, measurement.averageTagAreaPercent, 0.0)
            assertFalse(measurement.ambiguityAvailable)
            assertTrue(measurement.recoveryAmbiguityAvailable)
            assertEquals(0.08, measurement.recoveryAmbiguity, 0.0)
            assertEquals(measurement.frameId - 20_000L, measurement.captureTimestampMicros)
            assertTrue(measurement.hasRecoveryPose)
            assertEquals(1.1, measurement.recoveryPose.x, 0.0)
            assertEquals(0.1, measurement.stdDevXMeters, 0.0)
            assertEquals(0.2, measurement.stdDevYMeters, 0.0)
            assertEquals(1.0e6, measurement.stdDevHeadingRadians, 0.0)
            assertArrayEquals(doubleArrayOf(42.0, 5.0, 1.0, 2.0, 3.0, 4.0), orientationSub.get(), 0.0)
            assertEquals(1.0, imuModeSub.get(), 0.0)
            assertArrayEquals(doubleArrayOf(0.25, -0.1, 0.5, 1.0, -20.0, 3.0), cameraPoseSub.get(), 1e-9)

            io.updateInputs(inputs)
            assertTrue(inputs.measurements.isEmpty(), "The same NT pose sample must not be fused twice")
        } finally {
            io.close()
            orientationSub.close()
            imuModeSub.close()
            cameraPoseSub.close()
            tvPub.close()
            tidPub.close()
            heartbeatPub.close()
            mt1Pub.close()
            mt2Pub.close()
            obsoleteMt2Pub.close()
            targetSpacePub.close()
            stdDevsPub.close()
        }
    }
}
