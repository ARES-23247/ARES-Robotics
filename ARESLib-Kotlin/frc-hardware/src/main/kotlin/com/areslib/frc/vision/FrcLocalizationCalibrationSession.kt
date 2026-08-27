package com.areslib.frc.vision

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.math.estimation.LocalizationCalibrationCheckpoint
import com.areslib.math.estimation.LocalizationCalibrationPlatform
import com.areslib.math.estimation.LocalizationCalibrationRecorder
import com.areslib.math.estimation.LocalizationCalibrationSample
import com.areslib.math.estimation.LocalizationCalibrationTestType
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import java.io.File

/**
 * FRC Test-mode front end for the shared localization calibration recorder.
 *
 * The caller supplies driver controls and telemetry while this class owns the
 * portable sample format, surveyed truth, route checkpoints, and camera-frame
 * de-duplication. It deliberately recommends no values on the robot; fitting is
 * performed offline by [com.areslib.math.estimation.LocalizationCalibrationCli].
 */
class FrcLocalizationCalibrationSession(
    private val store: Store,
    private val swerveIO: SwerveHardwareIO?,
    private val measurementsProvider: () -> List<VisionMeasurement>,
    logDirectory: File? = null
) : AutoCloseable {
    private val recorder = LocalizationCalibrationRecorder(LocalizationCalibrationPlatform.FRC, logDirectory)
    private var lastRecordedVisionTimestampMs = Long.MIN_VALUE

    var testType: LocalizationCalibrationTestType = LocalizationCalibrationTestType.VISION_STATIONARY
        private set
    var runId: Int = 1
        private set
    var truthX: Double = 0.0
        private set
    var truthY: Double = 0.0
        private set
    var truthHeading: Double = 0.0
        private set
    var continuousRecording: Boolean = false
        private set

    val droppedSampleCount: Long get() = recorder.droppedSampleCount

    fun cycleTestType() {
        val values = LocalizationCalibrationTestType.entries
        testType = values[(testType.ordinal + 1) % values.size]
        continuousRecording = false
    }

    fun adjustTruth(deltaX: Double = 0.0, deltaY: Double = 0.0, deltaHeading: Double = 0.0) {
        truthX += deltaX
        truthY += deltaY
        truthHeading = com.areslib.math.wrapAngle(truthHeading + deltaHeading)
    }

    fun zeroTruth() {
        truthX = 0.0
        truthY = 0.0
        truthHeading = 0.0
    }

    fun toggleContinuousRecording() {
        continuousRecording = !continuousRecording
    }

    /** Seeds CTRE and Redux together to the currently entered surveyed pose. */
    fun seedPoseToTruth(timestampMs: Long) {
        val pose = Pose2d(truthX, truthY, Rotation2d(truthHeading))
        swerveIO?.seedPose(pose)
        store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = truthX,
                yMeters = truthY,
                headingRadians = truthHeading,
                timestampMs = timestampMs,
                isReset = true,
                isExternalEstimate = true
            )
        )
    }

    /** Records at most one row per actual camera frame during stationary/validation runs. */
    fun periodic(timestampMs: Long) {
        if (!continuousRecording) return
        val measurements = measurementsProvider()
        var newestTimestamp = Long.MIN_VALUE
        for (measurement in measurements) {
            if (measurement.timestampMs > newestTimestamp) newestTimestamp = measurement.timestampMs
        }
        if (newestTimestamp == Long.MIN_VALUE || newestTimestamp <= lastRecordedVisionTimestampMs) return
        lastRecordedVisionTimestampMs = newestTimestamp
        record(timestampMs, LocalizationCalibrationCheckpoint.NONE, measurements)
    }

    fun markStart(timestampMs: Long) {
        seedPoseToTruth(timestampMs)
        record(timestampMs, LocalizationCalibrationCheckpoint.START, measurementsProvider())
    }

    fun markEnd(timestampMs: Long) {
        record(timestampMs, LocalizationCalibrationCheckpoint.END, measurementsProvider())
        runId++
    }

    private fun record(
        timestampMs: Long,
        checkpoint: LocalizationCalibrationCheckpoint,
        measurements: List<VisionMeasurement>
    ) {
        recorder.record(
            LocalizationCalibrationSample.capture(
                timestampMs = timestampMs,
                platform = LocalizationCalibrationPlatform.FRC,
                testType = testType,
                runId = runId,
                state = store.state,
                measurements = measurements,
                checkpoint = checkpoint,
                truthValid = true,
                truthX = truthX,
                truthY = truthY,
                truthHeading = truthHeading
            )
        )
    }

    override fun close() = recorder.close()
}
