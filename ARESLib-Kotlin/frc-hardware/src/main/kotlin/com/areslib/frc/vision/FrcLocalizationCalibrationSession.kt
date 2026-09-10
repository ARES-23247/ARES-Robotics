package com.areslib.frc.vision

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.math.estimation.LocalizationCalibrationCheckpoint
import com.areslib.math.estimation.LocalizationCalibrationPlatform
import com.areslib.math.estimation.LocalizationCalibrationRecorder
import com.areslib.math.estimation.LocalizationCalibrationSample
import com.areslib.math.estimation.LocalizationCalibrationTestType
import com.areslib.math.estimation.StationaryCalibrationGate
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
    private val stationaryGate = StationaryCalibrationGate()
    private enum class PendingAction { NONE, SEED, START, END }
    private var pendingAction = PendingAction.NONE
    private var pendingRequestedAtMs = 0L
    private var closed = false
    var stationaryReady: Boolean = false
        private set
    val actionPending: Boolean get() = pendingAction != PendingAction.NONE

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
        check(!closed)
        cancelPendingAndRestartDwell()
        val values = LocalizationCalibrationTestType.entries
        testType = values[(testType.ordinal + 1) % values.size]
        continuousRecording = false
    }

    fun adjustTruth(deltaX: Double = 0.0, deltaY: Double = 0.0, deltaHeading: Double = 0.0) {
        check(!closed)
        val x = truthX + deltaX
        val y = truthY + deltaY
        val heading = truthHeading + deltaHeading
        require(x.isFinite() && y.isFinite() && heading.isFinite()) { "Surveyed pose must remain finite" }
        cancelPendingAndRestartDwell()
        truthX = x
        truthY = y
        truthHeading = heading
    }

    fun zeroTruth() {
        check(!closed)
        cancelPendingAndRestartDwell()
        truthX = 0.0
        truthY = 0.0
        truthHeading = 0.0
    }

    fun toggleContinuousRecording() {
        check(!closed)
        continuousRecording = !continuousRecording
        stationaryGate.reset()
        stationaryReady = false
    }

    /** Queues a seed; [periodic] applies it only after valid stationary dwell and neutral controls. */
    fun seedPoseToTruth(timestampMs: Long) {
        request(PendingAction.SEED, timestampMs)
    }

    private fun applySeed(timestampMs: Long) {
        val heading = com.areslib.math.wrapAngle(truthHeading)
        val pose = Pose2d(truthX, truthY, Rotation2d(heading))
        swerveIO?.seedPose(pose)
        store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = truthX,
                yMeters = truthY,
                headingRadians = heading,
                timestampMs = timestampMs,
                isReset = true,
                isExternalEstimate = true
            )
        )
    }

    /** Consume cached feedback each loop. Omitted neutral confirmation fails closed. */
    fun periodic(timestampMs: Long, driverNeutral: Boolean = false) {
        if (closed) return
        if (actionPending && timestampMs < pendingRequestedAtMs) cancelPendingAndRestartDwell()
        val drive = store.state.drive
        val commandNeutral = drive.xVelocityMetersPerSecond == 0.0 && drive.yVelocityMetersPerSecond == 0.0 &&
            drive.angularVelocityRadiansPerSecond == 0.0
        stationaryReady = stationaryGate.update(timestampMs, driverNeutral && commandNeutral,
            kotlin.math.hypot(drive.measuredFieldXVelocityMetersPerSecond, drive.measuredFieldYVelocityMetersPerSecond),
            drive.measuredAngularVelocityRadiansPerSecond, drive.measuredMotionValid,
            drive.poseEstimator.lastObservationTimestampMs)
        if (!stationaryReady) return
        val action = pendingAction
        if (action != PendingAction.NONE) {
            pendingAction = PendingAction.NONE
            if (action == PendingAction.SEED || action == PendingAction.START) applySeed(timestampMs)
            if (action == PendingAction.START) record(timestampMs, LocalizationCalibrationCheckpoint.START, emptyList())
            if (action == PendingAction.END) {
                record(timestampMs, LocalizationCalibrationCheckpoint.END, emptyList())
                runId++
            }
            stationaryGate.reset()
            stationaryReady = false
            return
        }
        if (!continuousRecording || (testType != LocalizationCalibrationTestType.VISION_STATIONARY &&
                testType != LocalizationCalibrationTestType.COMBINED_VALIDATION)) return
        val measurements = measurementsProvider()
        var newestTimestamp = Long.MIN_VALUE
        for (measurement in measurements) {
            if (measurement.timestampMs > newestTimestamp) newestTimestamp = measurement.timestampMs
        }
        if (newestTimestamp == Long.MIN_VALUE || newestTimestamp <= lastRecordedVisionTimestampMs) return
        val age = timestampMs - newestTimestamp
        if (newestTimestamp > timestampMs || age < 0L || age > 250L) return
        lastRecordedVisionTimestampMs = newestTimestamp
        record(timestampMs, LocalizationCalibrationCheckpoint.NONE, measurements)
    }

    fun markStart(timestampMs: Long) {
        request(PendingAction.START, timestampMs)
    }

    fun markEnd(timestampMs: Long) {
        request(PendingAction.END, timestampMs)
    }

    private fun request(action: PendingAction, timestampMs: Long) {
        check(!closed)
        pendingAction = action
        pendingRequestedAtMs = timestampMs
        // Restart at the explicit operator request; prior motion evidence cannot authorize a new seed.
        stationaryGate.reset()
        stationaryReady = false
    }

    private fun cancelPendingAndRestartDwell() {
        pendingAction = PendingAction.NONE
        stationaryGate.reset()
        stationaryReady = false
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
                truthHeading = truthHeading,
                truthHeadingUnwrapped = true
            )
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        continuousRecording = false
        cancelPendingAndRestartDwell()
        recorder.close()
    }
}
