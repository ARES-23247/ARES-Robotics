package com.areslib.frc.vision

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.math.estimation.LocalizationCalibrationCsv
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.reducer.rootReducer
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FrcLocalizationCalibrationSessionTest {
    @TempDir
    lateinit var tempDir: Path

    private class SeedRecordingIO : SwerveHardwareIO {
        var seeded = Pose2d()
        var seedCount = 0

        override fun read() = DriveState()
        override fun write(driveState: DriveState, powerScale: Double) = Unit
        override fun addVisionMeasurement(pose: Pose2d, timestampSeconds: Double) = Unit
        override fun seedPose(pose: Pose2d) {
            seeded = pose
            seedCount++
        }
    }

    @Test
    fun `records each camera frame once and seeds surveyed truth`() {
        val store = Store(RobotState(), ::rootReducer)
        store.dispatch(RobotAction.PoseUpdate(0.5, 0.6, 0.1, timestampMs = 50L, isReset = true))
        val io = SeedRecordingIO()
        var measurements = listOf(
            VisionMeasurement(
                timestampMs = 100L,
                targetPose = Pose3d(Translation3d(0.5, 0.6, 0.0), Rotation3d())
            )
        )
        val session = FrcLocalizationCalibrationSession(
            store,
            io,
            measurementsProvider = { measurements },
            logDirectory = tempDir.toFile()
        )

        session.adjustTruth(deltaX = 1.0, deltaY = 2.0, deltaHeading = 0.4)
        session.seedPoseToTruth(90L)
        assertEquals(1, io.seedCount)
        assertEquals(1.0, io.seeded.x, 0.0)
        assertEquals(2.0, io.seeded.y, 0.0)
        assertEquals(0.4, io.seeded.heading.radians, 1e-12)

        session.toggleContinuousRecording()
        session.periodic(110L)
        session.periodic(120L)
        measurements = listOf(measurements[0].copy(timestampMs = 130L, frameId = 2L))
        session.periodic(140L)
        session.close()

        val files = tempDir.toFile().listFiles { file -> file.extension == "csv" }?.toList().orEmpty()
        val samples = LocalizationCalibrationCsv.read(files)
        assertEquals(2, samples.size)
        assertEquals(1.0, samples[0].truthX, 0.0)
        assertEquals(2.0, samples[0].truthY, 0.0)
    }
}
