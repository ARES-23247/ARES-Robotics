package com.areslib.frc.vision

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.frc.TestSwerveHardwareIO
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FrcLocalizationCalibrationSessionTest {
    @TempDir
    lateinit var tempDir: Path

    private class SeedRecordingIO : TestSwerveHardwareIO() {
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
    fun `surveyed heading retains signed full turns while seeded pose is normalized`() {
        val io = SeedRecordingIO()
        val store = Store()
        FrcLocalizationCalibrationSession(store, io, { emptyList() }, tempDir.toFile()).use { session ->
            session.adjustTruth(deltaHeading = 4.0 * Math.PI + 0.2)
            assertEquals(4.0 * Math.PI + 0.2, session.truthHeading, 1e-12)
            session.seedPoseToTruth(100L)
            assertEquals(0, io.seedCount)
            settle(session, store, 100L)
            assertEquals(1, io.seedCount)
            assertEquals(0.2, io.seeded.heading.radians, 1e-12)
            session.adjustTruth(deltaHeading = -6.0 * Math.PI)
            assertEquals(-2.0 * Math.PI + 0.2, session.truthHeading, 1e-12)
        }
    }

    @Test
    fun `invalid surveyed adjustment is rejected without partial mutation`() {
        FrcLocalizationCalibrationSession(Store(), null, { emptyList() }, tempDir.toFile()).use { session ->
            session.adjustTruth(deltaX = 1.0, deltaY = 2.0, deltaHeading = 0.3)
            assertThrows(IllegalArgumentException::class.java) {
                session.adjustTruth(deltaX = 5.0, deltaHeading = Double.NaN)
            }
            assertEquals(1.0, session.truthX)
            assertEquals(2.0, session.truthY)
            assertEquals(0.3, session.truthHeading, 1e-12)
            session.adjustTruth(deltaX = Double.MAX_VALUE)
            assertThrows(IllegalArgumentException::class.java) { session.adjustTruth(deltaX = Double.MAX_VALUE) }
            assertEquals(Double.MAX_VALUE, session.truthX)
        }
    }

    @Test
    fun `moving robot cannot publish surveyed stationary truth`() {
        val store = Store()
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = 100L,
            isExternalEstimate = true, motionMeasurementsValid = true, xVelocityMetersPerSecond = 1.0))
        FrcLocalizationCalibrationSession(store, null, { listOf(VisionMeasurement(timestampMs = 100L)) },
            tempDir.toFile()).use { session ->
            session.toggleContinuousRecording()
            session.periodic(100L, driverNeutral = true)
        }
        val samples = LocalizationCalibrationCsv.read(tempDir.toFile().listFiles()!!.filter { it.name.endsWith(".csv.gz") })
        assertEquals(0, samples.size)
    }

    @Test
    fun `records each camera frame once and seeds surveyed truth`() {
        val store = Store(RobotState(), ::rootReducer)
        store.dispatch(RobotAction.PoseUpdate(0.5, 0.6, 0.1, timestampMs = 50L, isReset = true))
        val io = SeedRecordingIO()
        var measurements = listOf(
            VisionMeasurement(
                timestampMs = 1100L,
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
        settle(session, store, 100L)
        assertEquals(1, io.seedCount)
        assertEquals(1.0, io.seeded.x, 0.0)
        assertEquals(2.0, io.seeded.y, 0.0)
        assertEquals(0.4, io.seeded.heading.radians, 1e-12)

        session.toggleContinuousRecording()
        settle(session, store, 610L)
        observe(store, 1120L)
        session.periodic(1120L, true)
        measurements = listOf(measurements[0].copy(timestampMs = 1130L, frameId = 2L))
        observe(store, 1140L)
        session.periodic(1140L, true)
        session.close()

        val files = tempDir.toFile().listFiles { file ->
            file.name.endsWith(".csv.gz")
        }?.toList().orEmpty()
        val samples = LocalizationCalibrationCsv.read(files)
        assertEquals(2, samples.size)
        assertEquals(1.0, samples[0].truthX, 0.0)
        assertEquals(2.0, samples[0].truthY, 0.0)
    }

    @Test
    fun `pending checkpoints require fresh neutral dwell and cancel on edits and close`() {
        val store = Store()
        val io = SeedRecordingIO()
        val session = FrcLocalizationCalibrationSession(store, io, { emptyList() }, tempDir.toFile())
        session.markStart(0L)
        for (time in 0L..600L step 50L) session.periodic(time, true) // default zero feedback is unknown
        assertEquals(0, io.seedCount)
        observe(store, 650L)
        session.periodic(650L, true)
        session.periodic(1150L, true) // stale snapshot cannot complete dwell
        assertEquals(0, io.seedCount)
        settle(session, store, 1200L, neutral = false)
        assertEquals(0, io.seedCount)
        settle(session, store, 1800L)
        assertEquals(1, io.seedCount)
        session.markEnd(2400L)
        session.adjustTruth(deltaX = 1.0) // changing truth cancels pending checkpoint
        settle(session, store, 2400L)
        assertEquals(1, session.runId)
        session.markEnd(3000L)
        settle(session, store, 3000L)
        assertEquals(2, session.runId)
        session.seedPoseToTruth(3600L)
        session.close()
        session.close()
        settle(session, store, 3600L)
        assertEquals(1, io.seedCount)
        assertThrows(IllegalStateException::class.java) { session.markStart(4200L) }
        val rows = LocalizationCalibrationCsv.read(tempDir.toFile().listFiles()!!.filter { it.name.endsWith(".csv.gz") })
        assertEquals(listOf(com.areslib.math.estimation.LocalizationCalibrationCheckpoint.START,
            com.areslib.math.estimation.LocalizationCalibrationCheckpoint.END), rows.map { it.checkpoint })
        assertEquals(listOf(1, 1), rows.map { it.runId })
    }

    private fun observe(store: Store, time: Long) {
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, 0.0, timestampMs = time,
            isExternalEstimate = true, motionMeasurementsValid = true))
    }

    @Test
    fun `continuous collection rejects stale future and unconfirmed frames and odometry mode`() {
        val store = Store()
        var frames = listOf(VisionMeasurement(timestampMs = 0L))
        FrcLocalizationCalibrationSession(store, null, { frames }, tempDir.toFile()).use { session ->
            session.toggleContinuousRecording()
            settle(session, store, 0L) // frame is now stale
            frames = listOf(VisionMeasurement(timestampMs = 1000L))
            observe(store, 550L)
            session.periodic(550L, true) // future frame
            frames = listOf(VisionMeasurement(timestampMs = 600L))
            observe(store, 600L)
            session.periodic(600L) // no explicit neutral confirmation
            store.dispatch(RobotAction.JoystickDriveIntent(targetXVelocity = 1.0,
                targetYVelocity = 0.0, targetAngularVelocity = 0.0))
            settle(session, store, 650L) // nonneutral commanded motion despite zero measured speed
            store.dispatch(RobotAction.JoystickDriveIntent(targetXVelocity = 0.0,
                targetYVelocity = 0.0, targetAngularVelocity = 0.0))
            session.cycleTestType() // ODOMETRY_TRANSLATION
            session.toggleContinuousRecording()
            frames = listOf(VisionMeasurement(timestampMs = 1700L))
            settle(session, store, 1200L)
        }
        val rows = LocalizationCalibrationCsv.read(tempDir.toFile().listFiles()!!.filter { it.name.endsWith(".csv.gz") })
        assertEquals(0, rows.size)
    }

    private fun settle(session: FrcLocalizationCalibrationSession, store: Store, start: Long, neutral: Boolean = true) {
        for (time in start..start + 500L step 50L) {
            observe(store, time)
            session.periodic(time, neutral)
        }
    }
}
