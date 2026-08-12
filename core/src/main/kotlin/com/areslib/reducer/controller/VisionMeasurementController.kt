package com.areslib.reducer.controller

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Vector3
import com.areslib.math.estimation.PoseEstimator
import com.areslib.hardware.vision.VisionOutlierFilter
import com.areslib.reducer.VisionReducer
import com.areslib.math.wrapAngle

object VisionMeasurementController {
    private val DEFAULT_STD_DEVS = Vector3(0.05, 0.05, 0.1)

    private val scratchBefore = object : ThreadLocal<DoubleArray>() {
        override fun initialValue() = DoubleArray(9)
    }

    private val scratchAfter = object : ThreadLocal<DoubleArray>() {
        override fun initialValue() = DoubleArray(9)
    }

    private val scratchHistoricalPose = object : ThreadLocal<DoubleArray>() {
        override fun initialValue() = DoubleArray(3)
    }

    fun handle(state: RobotState, action: RobotAction.VisionMeasurementsReceived): RobotState {
        val measurements = action.measurements
        val validMeasurements = ArrayList<VisionMeasurement>(measurements.size)
        val historicalPose = scratchHistoricalPose.get()!!

        for (i in 0 until measurements.size) {
            val it = measurements[i]
            sampleHistoricalPose(state, it.timestampMs, historicalPose)
            if (VisionOutlierFilter.isValid(
                    config = state.vision.filterConfig,
                    measurement = it,
                    robotHeadingRad = historicalPose[2],
                    robotPoseX = historicalPose[0],
                    robotPoseY = historicalPose[1],
                    angularVelocityRadPerSec = state.drive.measuredAngularVelocityRadiansPerSecond,
                    linearAccelXG = state.drive.xAccelerationG,
                    linearAccelYG = state.drive.yAccelerationG,
                    linearAccelZG = state.drive.zAccelerationG
                )) {
                validMeasurements.add(it)
            }
        }

        // The estimator mutates its working state to remain allocation-free. Clone once at the
        // Redux boundary so no array or history entry reachable from a retained snapshot changes.
        var currentEstimator = if (action.fuseIntoPoseEstimator && validMeasurements.isNotEmpty()) {
            state.drive.poseEstimator.deepCopy()
        } else {
            state.drive.poseEstimator
        }
        val stdDevs = action.customVisionStdDevs ?: DEFAULT_STD_DEVS
        var acceptedCountDelta = 0
        var rejectedCountDelta = measurements.size - validMeasurements.size
        var lastCovBefore: DoubleArray? = null
        var lastCovAfter: DoubleArray? = null
        var lastAccepted = false
        var lastReason: String? = null

        val sb = scratchBefore.get()!!
        val sa = scratchAfter.get()!!

        if (action.fuseIntoPoseEstimator) {
            for (i in 0 until validMeasurements.size) {
                val measurement = validMeasurements[i]
                sb[0] = currentEstimator.covariance.m00
                sb[1] = currentEstimator.covariance.m01
                sb[2] = currentEstimator.covariance.m02
                sb[3] = currentEstimator.covariance.m10
                sb[4] = currentEstimator.covariance.m11
                sb[5] = currentEstimator.covariance.m12
                sb[6] = currentEstimator.covariance.m20
                sb[7] = currentEstimator.covariance.m21
                sb[8] = currentEstimator.covariance.m22

                val reportedStdDevX = measurement.stdDevXMeters
                val reportedStdDevY = measurement.stdDevYMeters
                val reportedStdDevHeading = measurement.stdDevHeadingRadians
                val stdDevX = if (reportedStdDevX.isFinite() && reportedStdDevX > 0.0) reportedStdDevX else stdDevs.x
                val stdDevY = if (reportedStdDevY.isFinite() && reportedStdDevY > 0.0) reportedStdDevY else stdDevs.y
                val stdDevHeading = when {
                    measurement.solverType == com.areslib.state.VisionSolverType.MEGATAG2 -> 1.0e6
                    reportedStdDevHeading.isFinite() && reportedStdDevHeading > 0.0 -> reportedStdDevHeading
                    else -> stdDevs.z
                }
                val nisThreshold = if (measurement.solverType == com.areslib.state.VisionSolverType.MEGATAG2) {
                    state.vision.filterConfig.mahalanobisThreshold2D
                } else {
                    state.vision.filterConfig.mahalanobisThreshold
                }
                currentEstimator = PoseEstimator.addVisionMeasurementDirect(
                    state = currentEstimator,
                    measurement = measurement,
                    visionStdDevX = stdDevX,
                    visionStdDevY = stdDevY,
                    visionStdDevHeading = stdDevHeading,
                    // A multi-tag camera solve is one correlated pose observation, not
                    // N independent observations. Its tag count scales covariance once.
                    numTags = measurement.tagCount.coerceAtLeast(1),
                    useMahalanobisRejection = true,
                    mahalanobisThreshold = nisThreshold
                )
                lastAccepted = currentEstimator.lastMeasurementAccepted
                lastReason = currentEstimator.lastRejectionReason
                if (lastAccepted) {
                    acceptedCountDelta++
                    if (lastCovBefore == null) {
                        lastCovBefore = DoubleArray(9)
                    }
                    if (lastCovAfter == null) {
                        lastCovAfter = DoubleArray(9)
                    }

                    System.arraycopy(sb, 0, lastCovBefore, 0, 9)

                    sa[0] = currentEstimator.covariance.m00
                    sa[1] = currentEstimator.covariance.m01
                    sa[2] = currentEstimator.covariance.m02
                    sa[3] = currentEstimator.covariance.m10
                    sa[4] = currentEstimator.covariance.m11
                    sa[5] = currentEstimator.covariance.m12
                    sa[6] = currentEstimator.covariance.m20
                    sa[7] = currentEstimator.covariance.m21
                    sa[8] = currentEstimator.covariance.m22

                    System.arraycopy(sa, 0, lastCovAfter, 0, 9)
                } else {
                    rejectedCountDelta++
                }
            }
            if (validMeasurements.isEmpty() && measurements.isNotEmpty()) {
                lastReason = "prefilter_rejected"
            }
        } else {
            // The platform estimator has already fused these observations. Keep the
            // measurements available to diagnostics without feeding correlated data into
            // the ARES EKF a second time.
            acceptedCountDelta = validMeasurements.size
            rejectedCountDelta = measurements.size - validMeasurements.size
            lastAccepted = validMeasurements.isNotEmpty()
            lastReason = if (!lastAccepted && measurements.isNotEmpty()) "external_filter_rejected" else null
        }

        val filteredAction = action.copy(measurements = validMeasurements)
        val reducedVision = VisionReducer.reduce(state.vision, filteredAction)
        val updatedVision = reducedVision.copy(
            lastMeasurementAccepted = lastAccepted,
            lastRejectionReason = lastReason,
            covarianceBeforeUpdate = lastCovBefore ?: reducedVision.covarianceBeforeUpdate,
            covarianceAfterUpdate = lastCovAfter ?: reducedVision.covarianceAfterUpdate,
            measurementCount = reducedVision.measurementCount + acceptedCountDelta,
            rejectionCount = reducedVision.rejectionCount + rejectedCountDelta
        )

        val updatedDrive = if (action.fuseIntoPoseEstimator) {
            state.drive.updateDiagnostics(
                state.drive.odometryX,
                state.drive.odometryY,
                state.drive.odometryHeading,
                currentEstimator
            )
        } else {
            state.drive
        }

        return state.copy(
            vision = updatedVision,
            drive = updatedDrive,
            timestampMs = action.timestampMs
        )
    }

    private fun sampleHistoricalPose(state: RobotState, timestampMs: Long, out: DoubleArray) {
        val estimator = state.drive.poseEstimator
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
                val alpha = if (spanMs <= 0L) 0.0 else {
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
}
