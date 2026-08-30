package com.areslib.reducer.controller

import com.areslib.action.RobotAction
import com.areslib.hardware.vision.VisionOutlierFilter
import com.areslib.math.estimation.PoseEstimator
import com.areslib.math.estimation.PoseEstimatorState
import com.areslib.math.wrapAngle
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement

/** Immutable diagnostics applied by the pure vision reducer after runtime EKF processing. */
internal data class VisionEstimatorDiagnostics(
    val lastMeasurementAccepted: Boolean,
    val lastRejectionReason: String?,
    val covarianceBeforeUpdate: DoubleArray?,
    val covarianceAfterUpdate: DoubleArray?,
    val acceptedCountDelta: Int,
    val rejectedCountDelta: Int
)

/** A filtered public action plus private-runtime diagnostics for one camera frame. */
internal data class PreparedVisionMeasurements(
    val action: RobotAction.VisionMeasurementsReceived,
    val diagnostics: VisionEstimatorDiagnostics
)

/**
 * Per-store vision processor. Historical pose lookup and EKF rewind use the owning store's
 * private estimator workspace; no mutable history is reachable from Redux snapshots.
 */
internal class StoreVisionMeasurementProcessor {
    private val scratchBefore = DoubleArray(9)
    private val scratchAfter = DoubleArray(9)
    private val scratchHistoricalPose = DoubleArray(3)

    fun prepare(
        state: RobotState,
        action: RobotAction.VisionMeasurementsReceived,
        estimator: PoseEstimatorState
    ): PreparedVisionMeasurements {
        val measurements = action.measurements
        val validMeasurements = ArrayList<VisionMeasurement>(measurements.size)

        for (i in 0 until measurements.size) {
            val measurement = measurements[i]
            sampleHistoricalPose(estimator, measurement.timestampMs, scratchHistoricalPose)
            if (VisionOutlierFilter.isValid(
                    config = state.vision.filterConfig,
                    measurement = measurement,
                    robotHeadingRad = scratchHistoricalPose[2],
                    robotPoseX = scratchHistoricalPose[0],
                    robotPoseY = scratchHistoricalPose[1],
                    angularVelocityRadPerSec = state.drive.measuredAngularVelocityRadiansPerSecond,
                    linearAccelXG = state.drive.xAccelerationG,
                    linearAccelYG = state.drive.yAccelerationG,
                    linearAccelZG = state.drive.zAccelerationG
                )) {
                validMeasurements.add(measurement)
            }
        }

        val configuredStdDevs = action.customVisionStdDevs
        val defaultStdDevX = configuredStdDevs?.x ?: 0.05
        val defaultStdDevY = configuredStdDevs?.y ?: 0.05
        val defaultStdDevHeading = configuredStdDevs?.z ?: 0.1
        var acceptedCountDelta = 0
        var rejectedCountDelta = measurements.size - validMeasurements.size
        var lastCovBefore: DoubleArray? = null
        var lastCovAfter: DoubleArray? = null
        var lastAccepted = false
        var lastReason: String? = null

        if (action.fuseIntoPoseEstimator) {
            for (i in 0 until validMeasurements.size) {
                val measurement = validMeasurements[i]
                copyCovariance(estimator, scratchBefore)

                val reportedStdDevX = measurement.stdDevXMeters
                val reportedStdDevY = measurement.stdDevYMeters
                val reportedStdDevHeading = measurement.stdDevHeadingRadians
                val stdDevX = if (reportedStdDevX.isFinite() && reportedStdDevX > 0.0) {
                    reportedStdDevX
                } else {
                    defaultStdDevX
                }
                val stdDevY = if (reportedStdDevY.isFinite() && reportedStdDevY > 0.0) {
                    reportedStdDevY
                } else {
                    defaultStdDevY
                }
                val stdDevHeading = when {
                    measurement.solverType == com.areslib.state.VisionSolverType.MEGATAG2 -> 1.0e6
                    reportedStdDevHeading.isFinite() && reportedStdDevHeading > 0.0 -> reportedStdDevHeading
                    else -> defaultStdDevHeading
                }
                val nisThreshold = if (measurement.solverType == com.areslib.state.VisionSolverType.MEGATAG2) {
                    state.vision.filterConfig.mahalanobisThreshold2D
                } else {
                    state.vision.filterConfig.mahalanobisThreshold
                }

                PoseEstimator.addVisionMeasurementDirect(
                    state = estimator,
                    measurement = measurement,
                    visionStdDevX = stdDevX,
                    visionStdDevY = stdDevY,
                    visionStdDevHeading = stdDevHeading,
                    numTags = measurement.tagCount.coerceAtLeast(1),
                    useMahalanobisRejection = true,
                    mahalanobisThreshold = nisThreshold
                )
                lastAccepted = estimator.lastMeasurementAccepted
                lastReason = estimator.lastRejectionReason
                if (lastAccepted) {
                    acceptedCountDelta++
                    val before = lastCovBefore ?: DoubleArray(9).also { lastCovBefore = it }
                    val after = lastCovAfter ?: DoubleArray(9).also { lastCovAfter = it }
                    System.arraycopy(scratchBefore, 0, before, 0, 9)
                    copyCovariance(estimator, scratchAfter)
                    System.arraycopy(scratchAfter, 0, after, 0, 9)
                } else {
                    rejectedCountDelta++
                }
            }
            if (validMeasurements.isEmpty() && measurements.isNotEmpty()) {
                lastReason = "prefilter_rejected"
            }
        } else {
            // The platform estimator already fused this frame; retain it for diagnostics only.
            acceptedCountDelta = validMeasurements.size
            rejectedCountDelta = measurements.size - validMeasurements.size
            lastAccepted = validMeasurements.isNotEmpty()
            lastReason = if (!lastAccepted && measurements.isNotEmpty()) "external_filter_rejected" else null
        }

        return PreparedVisionMeasurements(
            action = action.copy(measurements = validMeasurements),
            diagnostics = VisionEstimatorDiagnostics(
                lastMeasurementAccepted = lastAccepted,
                lastRejectionReason = lastReason,
                covarianceBeforeUpdate = lastCovBefore,
                covarianceAfterUpdate = lastCovAfter,
                acceptedCountDelta = acceptedCountDelta,
                rejectedCountDelta = rejectedCountDelta
            )
        )
    }

    private fun sampleHistoricalPose(estimator: PoseEstimatorState, timestampMs: Long, out: DoubleArray) {
        val history = estimator.history
        if (history.isEmpty()) {
            out[0] = estimator.estimatedPoseX
            out[1] = estimator.estimatedPoseY
            out[2] = estimator.estimatedPoseHeading
            return
        }

        if (timestampMs <= history[0].timestampMs) {
            val oldest = history[0]
            out[0] = oldest.x
            out[1] = oldest.y
            out[2] = oldest.headingRad
            return
        }

        for (i in 1 until history.size) {
            val after = history[i]
            if (timestampMs <= after.timestampMs) {
                val before = history[i - 1]
                val spanMs = after.timestampMs - before.timestampMs
                val alpha = if (spanMs <= 0L) {
                    0.0
                } else {
                    ((timestampMs - before.timestampMs).toDouble() / spanMs.toDouble()).coerceIn(0.0, 1.0)
                }
                out[0] = before.x + (after.x - before.x) * alpha
                out[1] = before.y + (after.y - before.y) * alpha
                out[2] = wrapAngle(before.headingRad + wrapAngle(after.headingRad - before.headingRad) * alpha)
                return
            }
        }

        val newest = history[history.size - 1]
        out[0] = newest.x
        out[1] = newest.y
        out[2] = newest.headingRad
    }

    private fun copyCovariance(estimator: PoseEstimatorState, destination: DoubleArray) {
        val covariance = estimator.covariance
        destination[0] = covariance.m00
        destination[1] = covariance.m01
        destination[2] = covariance.m02
        destination[3] = covariance.m10
        destination[4] = covariance.m11
        destination[5] = covariance.m12
        destination[6] = covariance.m20
        destination[7] = covariance.m21
        destination[8] = covariance.m22
    }
}
