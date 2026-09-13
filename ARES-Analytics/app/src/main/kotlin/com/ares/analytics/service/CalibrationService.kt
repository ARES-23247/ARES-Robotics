package com.ares.analytics.service

import com.ares.analytics.service.calibration.CameraCalibrationSolver

/**
 * Camera mounting pose in robot coordinates: forward X, left Y, up Z.
 * Orientation applies Rz(yaw) Ry(pitch) Rx(roll) to aligned camera forward/left/up vectors.
 *
 * @property x Translational X offset in meters ($m$).
 * @property y Translational Y offset in meters ($m$).
 * @property z Translational Z height offset in meters ($m$).
 * @property roll Rotation angle around X axis in radians ($rad$).
 * @property pitch Rotation angle around Y axis in radians ($rad$).
 * @property yaw Rotation angle around Z axis in radians ($rad$), **CCW-positive** (0 = +X).
 */
data class Pose3d(
    val x: Double,
    val y: Double,
    val z: Double,
    val roll: Double,
    val pitch: Double,
    val yaw: Double // Heading (radians, CCW-positive)
)

/**
 * Local linear uncertainty for an equal-weight camera translation fit with IID isotropic errors.
 *
 * @property pose Solved 6-DOF target pose [Pose3d].
 * @property standardErrors Standard error vector for parameter estimates.
 * @property covarianceMatrix $6 \times 6$ parameter estimation covariance matrix.
 * @property reducedChiSquared Legacy name for residual variance in square meters, SSE/(3N-6).
 * This is not a dimensionless reduced chi-squared: no known observation variances were supplied.
 * Covariance order is x,y,z,roll,pitch,yaw in meters/radians; systematic bias is not represented.
 */
data class CalibrationDiagnostics(
    val pose: Pose3d,
    val standardErrors: DoubleArray,
    val covarianceMatrix: Array<DoubleArray>,
    val reducedChiSquared: Double
) {
    val residualVariance: Double get() = reducedChiSquared

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CalibrationDiagnostics
        if (pose != other.pose) return false
        if (!standardErrors.contentEquals(other.standardErrors)) return false
        if (!covarianceMatrix.contentDeepEquals(other.covarianceMatrix)) return false
        if (reducedChiSquared.compareTo(other.reducedChiSquared) != 0) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pose.hashCode()
        result = 31 * result + standardErrors.contentHashCode()
        result = 31 * result + covarianceMatrix.contentDeepHashCode()
        result = 31 * result + reducedChiSquared.hashCode()
        return result
    }
}

/**
 * Observation of a known tag while the level robot rotates at a fixed field origin.
 * Legacy targetSpaceX/Y/Z names hold the TAG in CAMERA optical coordinates (right/down/forward,
 * meters), as in Limelight targetpose_cameraspace. They must not contain camerapose_targetspace
 * or robotPoseTargetSpace. The three optical rotation fields are retained for source compatibility
 * and are unused by this translation-only fit. Tag field positions are relative to the rotation
 * center; translated robot motion cannot be inferred from gyro heading alone.
 */
data class CalibrationMeasurement(
    val gyroHeading: Double, // radians (CCW-positive)
    val tagId: Int,
    val tagFieldX: Double,
    val tagFieldY: Double,
    val tagFieldZ: Double,
    val targetSpaceX: Double,
    val targetSpaceY: Double,
    val targetSpaceZ: Double,
    val targetSpaceRoll: Double,
    val targetSpacePitch: Double,
    val targetSpaceYaw: Double
)

class CalibrationService(databaseService: DatabaseService) {

    private val cameraSolver = CameraCalibrationSolver(databaseService)

    /**
     * Solves for the camera mounting pose by rigid least-squares alignment.
     * Invalid or unobservable observations throw IllegalArgumentException.
     */
    fun solveCameraExtrinsics(measurements: List<CalibrationMeasurement>): Pose3d {
        return cameraSolver.solveCameraExtrinsics(measurements)
    }

    /**
     * Scans NT4 telemetry from a calibration run to pull measurements and run the solver.
     */
    suspend fun runExtrinsicCalibration(
        sessionId: String,
        cameraIndex: Int
    ): Pose3d {
        return cameraSolver.runExtrinsicCalibration(sessionId, cameraIndex)
    }

    fun solveCameraExtrinsicsWithDiagnostics(measurements: List<CalibrationMeasurement>): CalibrationDiagnostics {
        return cameraSolver.solveCameraExtrinsicsWithDiagnostics(measurements)
    }

    suspend fun runExtrinsicCalibrationWithDiagnostics(
        sessionId: String,
        cameraIndex: Int
    ): CalibrationDiagnostics {
        return cameraSolver.runExtrinsicCalibrationWithDiagnostics(sessionId, cameraIndex)
    }
}

data class FieldTag(val x: Double, val y: Double, val z: Double)
