package com.areslib.math.estimation

import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d

/**
 * Deeply immutable estimator output published through Redux.
 *
 * The mutable EKF workspace and its replay history remain owned by [PoseEstimatorRuntime]. Fixed
 * 3x3 matrices are flattened into primitive fields so a published snapshot cannot expose mutable
 * arrays and requires only one allocation per estimator publication.
 */
data class PoseEstimatorSnapshot(
    val estimatedPoseX: Double = 0.0,
    val estimatedPoseY: Double = 0.0,
    val estimatedPoseHeading: Double = 0.0,
    val covariance00: Double = 1.0,
    val covariance01: Double = 0.0,
    val covariance02: Double = 0.0,
    val covariance10: Double = 0.0,
    val covariance11: Double = 1.0,
    val covariance12: Double = 0.0,
    val covariance20: Double = 0.0,
    val covariance21: Double = 0.0,
    val covariance22: Double = 1.0,
    val isBeached: Boolean = false,
    val lastUnbeachedTimeMs: Long = 0L,
    val gyroBiasRadPerSec: Double = 0.0,
    val stationarySinceMs: Long = 0L,
    val lastInnovationX: Double = 0.0,
    val lastInnovationY: Double = 0.0,
    val lastInnovationTheta: Double = 0.0,
    val lastNormalizedInnovationSquared: Double = 0.0,
    val kalmanGain00: Double = 0.0,
    val kalmanGain01: Double = 0.0,
    val kalmanGain02: Double = 0.0,
    val kalmanGain10: Double = 0.0,
    val kalmanGain11: Double = 0.0,
    val kalmanGain12: Double = 0.0,
    val kalmanGain20: Double = 0.0,
    val kalmanGain21: Double = 0.0,
    val kalmanGain22: Double = 0.0,
    val lastMeasurementAccepted: Boolean = false,
    val lastRejectionReason: String? = null,
    val lastObservationTimestampMs: Long = -1L
) {
    /** Allocating convenience view for non-hot-path callers. */
    val estimatedPose: Pose2d
        get() = Pose2d(estimatedPoseX, estimatedPoseY, Rotation2d(estimatedPoseHeading))

    /** Allocating convenience view for diagnostics and compatibility callers. */
    val covariance: Matrix3x3
        get() = Matrix3x3(
            covariance00, covariance01, covariance02,
            covariance10, covariance11, covariance12,
            covariance20, covariance21, covariance22
        )

    fun covarianceElement(index: Int): Double = when (index) {
        0 -> covariance00
        1 -> covariance01
        2 -> covariance02
        3 -> covariance10
        4 -> covariance11
        5 -> covariance12
        6 -> covariance20
        7 -> covariance21
        8 -> covariance22
        else -> throw IndexOutOfBoundsException("Covariance index must be in 0..8: $index")
    }

    fun kalmanGainElement(index: Int): Double = when (index) {
        0 -> kalmanGain00
        1 -> kalmanGain01
        2 -> kalmanGain02
        3 -> kalmanGain10
        4 -> kalmanGain11
        5 -> kalmanGain12
        6 -> kalmanGain20
        7 -> kalmanGain21
        8 -> kalmanGain22
        else -> throw IndexOutOfBoundsException("Kalman-gain index must be in 0..8: $index")
    }

    /** Explicitly allocating copy for offline calibration/export code. */
    fun copyCovariance(): DoubleArray = doubleArrayOf(
        covariance00, covariance01, covariance02,
        covariance10, covariance11, covariance12,
        covariance20, covariance21, covariance22
    )

    /** Explicitly allocating copy for offline diagnostics/export code. */
    fun copyKalmanGain(): DoubleArray = doubleArrayOf(
        kalmanGain00, kalmanGain01, kalmanGain02,
        kalmanGain10, kalmanGain11, kalmanGain12,
        kalmanGain20, kalmanGain21, kalmanGain22
    )
}

/** Copies one mutable runtime workspace into one deeply immutable Redux snapshot. */
internal fun PoseEstimatorState.reduxSnapshot(): PoseEstimatorSnapshot {
    val observationTimestampMs = if (history.isEmpty()) {
        lastObservationTimestampMs
    } else {
        history[history.size - 1].timestampMs
    }
    return PoseEstimatorSnapshot(
        estimatedPoseX = estimatedPoseX,
        estimatedPoseY = estimatedPoseY,
        estimatedPoseHeading = estimatedPoseHeading,
        covariance00 = covarianceArray[0],
        covariance01 = covarianceArray[1],
        covariance02 = covarianceArray[2],
        covariance10 = covarianceArray[3],
        covariance11 = covarianceArray[4],
        covariance12 = covarianceArray[5],
        covariance20 = covarianceArray[6],
        covariance21 = covarianceArray[7],
        covariance22 = covarianceArray[8],
        isBeached = isBeached,
        lastUnbeachedTimeMs = lastUnbeachedTimeMs,
        gyroBiasRadPerSec = gyroBiasRadPerSec,
        stationarySinceMs = stationarySinceMs,
        lastInnovationX = lastInnovationX,
        lastInnovationY = lastInnovationY,
        lastInnovationTheta = lastInnovationTheta,
        lastNormalizedInnovationSquared = lastNormalizedInnovationSquared,
        kalmanGain00 = lastKalmanGain[0],
        kalmanGain01 = lastKalmanGain[1],
        kalmanGain02 = lastKalmanGain[2],
        kalmanGain10 = lastKalmanGain[3],
        kalmanGain11 = lastKalmanGain[4],
        kalmanGain12 = lastKalmanGain[5],
        kalmanGain20 = lastKalmanGain[6],
        kalmanGain21 = lastKalmanGain[7],
        kalmanGain22 = lastKalmanGain[8],
        lastMeasurementAccepted = lastMeasurementAccepted,
        lastRejectionReason = lastRejectionReason,
        lastObservationTimestampMs = observationTimestampMs
    )
}
