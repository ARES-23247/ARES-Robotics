package com.areslib.reducer.controller

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Vector3
import com.areslib.math.estimation.PoseEstimator
import com.areslib.hardware.vision.VisionOutlierFilter
import com.areslib.reducer.VisionReducer

object VisionMeasurementController {
    private val DEFAULT_STD_DEVS = Vector3(0.05, 0.05, 0.1)

    private val scratchBefore = object : ThreadLocal<DoubleArray>() {
        override fun initialValue() = DoubleArray(9)
    }

    private val scratchAfter = object : ThreadLocal<DoubleArray>() {
        override fun initialValue() = DoubleArray(9)
    }

    fun handle(state: RobotState, action: RobotAction.VisionMeasurementsReceived): RobotState {
        val robotPose = state.drive.poseEstimator.estimatedPose
        val robotHeading = robotPose.heading.radians
        val measurements = action.measurements
        val validMeasurements = ArrayList<VisionMeasurement>(measurements.size)

        for (i in 0 until measurements.size) {
            val it = measurements[i]
            if (VisionOutlierFilter.isValid(
                    config = state.vision.filterConfig,
                    measurement = it,
                    robotHeadingRad = robotHeading,
                    robotPose = robotPose,
                    angularVelocityRadPerSec = state.drive.angularVelocityRadiansPerSecond,
                    linearAccelXG = state.drive.xAccelerationG,
                    linearAccelYG = state.drive.yAccelerationG,
                    linearAccelZG = state.drive.zAccelerationG
                )) {
                validMeasurements.add(it)
            }
        }

        var currentEstimator = state.drive.poseEstimator
        val stdDevs = action.customVisionStdDevs ?: DEFAULT_STD_DEVS
        var acceptedCountDelta = 0
        var rejectedCountDelta = 0
        var lastCovBefore: DoubleArray? = null
        var lastCovAfter: DoubleArray? = null
        var lastAccepted = false
        var lastReason: String? = null

        val sb = scratchBefore.get()!!
        val sa = scratchAfter.get()!!

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

            currentEstimator = PoseEstimator.addVisionMeasurement(
                state = currentEstimator,
                measurement = measurement,
                visionStdDevs = stdDevs,
                numTags = validMeasurements.size,
                useMahalanobisRejection = true,
                mahalanobisThreshold = state.vision.filterConfig.mahalanobisThreshold
            )
            lastAccepted = currentEstimator.lastMeasurementAccepted
            lastReason = currentEstimator.lastRejectionReason
            if (lastAccepted) {
                acceptedCountDelta++
                if (lastCovBefore == null) {
                    lastCovBefore = state.vision.covarianceBeforeUpdate ?: DoubleArray(9)
                }
                if (lastCovAfter == null) {
                    lastCovAfter = state.vision.covarianceAfterUpdate ?: DoubleArray(9)
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

        val updatedDrive = state.drive.updateDiagnostics(
            state.drive.odometryX,
            state.drive.odometryY,
            state.drive.odometryHeading,
            currentEstimator
        )

        return state.copy(
            vision = updatedVision,
            drive = updatedDrive,
            timestampMs = action.timestampMs
        )
    }
}
