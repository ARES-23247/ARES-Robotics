package com.areslib.math.estimation

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.wrapAngle
import com.areslib.reducer.controller.VisionEstimatorDiagnostics
import com.areslib.reducer.controller.StoreVisionMeasurementProcessor
import com.areslib.state.RobotState

/** Reducer input prepared by one store-owned estimator runtime. */
internal data class PreparedStoreAction(
    val publicAction: RobotAction,
    val estimatorAction: ApplyPoseEstimatorRuntimeResult? = null
)

/**
 * Internal derived action carrying immutable estimator outputs back through the pure Redux tree.
 * It is deliberately not sent to action listeners: replay logs retain the raw sensor action and
 * deterministically rebuild this result with their own [PoseEstimatorRuntime].
 */
internal data class ApplyPoseEstimatorRuntimeResult(
    val estimatorState: PoseEstimatorSnapshot?,
    val visionDiagnostics: VisionEstimatorDiagnostics?,
    override val timestampMs: Long
) : RobotAction

/**
 * Mutable EKF replay owner for exactly one [com.areslib.Store].
 *
 * The fixed-capacity history and estimator scratch state never enter Redux. Published
 * [PoseEstimatorSnapshot] values contain only immutable primitive fields, so retained robot states
 * cannot be changed by later odometry or delayed vision.
 */
internal class PoseEstimatorRuntime(initialState: PoseEstimatorSnapshot) {
    private var estimator = createWorkspace(initialState)
    private val visionController = StoreVisionMeasurementProcessor()

    fun prepare(state: RobotState, action: RobotAction): PreparedStoreAction {
        return when (action) {
            is RobotAction.DriveHardwareUpdate -> PreparedStoreAction(
                publicAction = action,
                estimatorAction = processDriveHardwareUpdate(action)?.let {
                    ApplyPoseEstimatorRuntimeResult(it, null, action.timestampMs)
                }
            )
            is RobotAction.PoseUpdate -> PreparedStoreAction(
                publicAction = action,
                estimatorAction = processPoseUpdate(state, action)?.let {
                    ApplyPoseEstimatorRuntimeResult(it, null, action.timestampMs)
                }
            )
            is RobotAction.VisionMeasurementsReceived -> {
                val prepared = visionController.prepare(state, action, estimator)
                PreparedStoreAction(
                    publicAction = prepared.action,
                    estimatorAction = ApplyPoseEstimatorRuntimeResult(
                        estimatorState = if (action.fuseIntoPoseEstimator) estimator.reduxSnapshot() else null,
                        visionDiagnostics = prepared.diagnostics,
                        timestampMs = action.timestampMs
                    )
                )
            }
            else -> PreparedStoreAction(action)
        }
    }

    private fun processDriveHardwareUpdate(action: RobotAction.DriveHardwareUpdate): PoseEstimatorSnapshot? {
        if (!action.xVelocity.isFinite() || !action.yVelocity.isFinite() ||
            !action.angularVelocity.isFinite() || !action.deltaX.isFinite() ||
            !action.deltaY.isFinite() || !action.deltaHeading.isFinite() ||
            !action.pitchDegrees.isFinite() || !action.rollDegrees.isFinite() ||
            !action.xAccelerationG.isFinite() || !action.yAccelerationG.isFinite() ||
            !action.zAccelerationG.isFinite()) {
            return null
        }
        val dtSeconds = observationDtSeconds(action.timestampMs) ?: return null
        PoseEstimator.addOdometryObservationDirect(
            state = estimator,
            timestampMs = action.timestampMs,
            deltaX = action.deltaX,
            deltaY = action.deltaY,
            deltaHeadingRad = action.deltaHeading,
            pitchDegrees = action.pitchDegrees,
            rollDegrees = action.rollDegrees,
            gyroRateRadPerSec = action.angularVelocity,
            dtSeconds = dtSeconds
        )
        estimator.lastObservationTimestampMs = action.timestampMs
        return estimator.reduxSnapshot()
    }

    private fun processPoseUpdate(state: RobotState, action: RobotAction.PoseUpdate): PoseEstimatorSnapshot? {
        if (!action.xMeters.isFinite() || !action.yMeters.isFinite() || !action.headingRadians.isFinite()) {
            return null
        }

        if (action.isReset) {
            val newHistory = HistoryBuffer(HISTORY_CAPACITY)
            val resetCovariance = Matrix3x3(
                0.01, 0.0, 0.0,
                0.0, 0.01, 0.0,
                0.0, 0.0, 0.01
            )
            newHistory.addEntryDirect(
                action.timestampMs,
                action.xMeters,
                action.yMeters,
                action.headingRadians,
                resetCovariance,
                1.0
            )
            estimator = estimator.copy(
                estimatedPoseX = action.xMeters,
                estimatedPoseY = action.yMeters,
                estimatedPoseHeading = action.headingRadians,
                covarianceArray = doubleArrayOf(
                    0.01, 0.0, 0.0,
                    0.0, 0.01, 0.0,
                    0.0, 0.0, 0.01
                ),
                history = newHistory,
                isBeached = false,
                lastUnbeachedTimeMs = action.timestampMs,
                lastKalmanGain = DoubleArray(9)
            ).also { it.lastObservationTimestampMs = action.timestampMs }
            return estimator.reduxSnapshot()
        }

        if (action.isExternalEstimate) {
            PoseEstimator.acceptExternalEstimate(
                state = estimator,
                timestampMs = action.timestampMs,
                xMeters = action.xMeters,
                yMeters = action.yMeters,
                headingRadians = action.headingRadians
            )
            estimator.lastObservationTimestampMs = action.timestampMs
            return estimator.reduxSnapshot()
        }

        val dtSeconds = observationDtSeconds(action.timestampMs) ?: return null
        val fieldDeltaX = action.xMeters - state.drive.odometryX
        val fieldDeltaY = action.yMeters - state.drive.odometryY
        val deltaHeading = wrapAngle(action.headingRadians - state.drive.odometryHeading)
        val cosStart = kotlin.math.cos(state.drive.odometryHeading)
        val sinStart = kotlin.math.sin(state.drive.odometryHeading)
        val bodyArcX = cosStart * fieldDeltaX + sinStart * fieldDeltaY
        val bodyArcY = -sinStart * fieldDeltaX + cosStart * fieldDeltaY

        val twistX: Double
        val twistY: Double
        if (kotlin.math.abs(deltaHeading) < 1e-6) {
            twistX = bodyArcX
            twistY = bodyArcY
        } else {
            val s = kotlin.math.sin(deltaHeading) / deltaHeading
            val c = (1.0 - kotlin.math.cos(deltaHeading)) / deltaHeading
            val determinant = s * s + c * c
            twistX = (s * bodyArcX + c * bodyArcY) / determinant
            twistY = (-c * bodyArcX + s * bodyArcY) / determinant
        }

        val motionMeasurementsValid = action.motionMeasurementsValid &&
            action.angularVelocityRadiansPerSecond.isFinite()
        PoseEstimator.addOdometryObservationDirect(
            state = estimator,
            timestampMs = action.timestampMs,
            deltaX = twistX,
            deltaY = twistY,
            deltaHeadingRad = deltaHeading,
            pitchDegrees = if (action.imuMeasurementsValid && action.pitchDegrees.isFinite()) action.pitchDegrees else 0.0,
            rollDegrees = if (action.imuMeasurementsValid && action.rollDegrees.isFinite()) action.rollDegrees else 0.0,
            pitchVelocityDegPerSec = if (action.imuMeasurementsValid && action.pitchVelocityDegPerSec.isFinite()) {
                action.pitchVelocityDegPerSec
            } else 0.0,
            rollVelocityDegPerSec = if (action.imuMeasurementsValid && action.rollVelocityDegPerSec.isFinite()) {
                action.rollVelocityDegPerSec
            } else 0.0,
            gyroRateRadPerSec = if (motionMeasurementsValid) action.angularVelocityRadiansPerSecond else 0.0,
            dtSeconds = dtSeconds,
            applyGyroBiasCorrection = action.applyControlHubGyroCorrection
        )
        estimator.lastObservationTimestampMs = action.timestampMs
        return estimator.reduxSnapshot()
    }

    private fun observationDtSeconds(timestampMs: Long): Double? {
        val history = estimator.history
        if (history.isEmpty()) return 0.02
        val deltaMs = timestampMs - history[history.size - 1].timestampMs
        if (deltaMs <= 0L) return null
        return (deltaMs / 1_000.0).coerceIn(0.001, 0.1)
    }

    private fun createWorkspace(snapshot: PoseEstimatorSnapshot): PoseEstimatorState {
        val history = HistoryBuffer(HISTORY_CAPACITY)
        if (snapshot.lastObservationTimestampMs >= 0L) {
            history.addEntryDirect(
                snapshot.lastObservationTimestampMs,
                snapshot.estimatedPoseX,
                snapshot.estimatedPoseY,
                snapshot.estimatedPoseHeading,
                snapshot.covariance,
                1.0
            )
        }
        return PoseEstimatorState(
            estimatedPoseX = snapshot.estimatedPoseX,
            estimatedPoseY = snapshot.estimatedPoseY,
            estimatedPoseHeading = snapshot.estimatedPoseHeading,
            covarianceArray = snapshot.copyCovariance(),
            history = history,
            isBeached = snapshot.isBeached,
            lastUnbeachedTimeMs = snapshot.lastUnbeachedTimeMs,
            gyroBiasRadPerSec = snapshot.gyroBiasRadPerSec,
            stationarySinceMs = snapshot.stationarySinceMs,
            lastInnovationX = snapshot.lastInnovationX,
            lastInnovationY = snapshot.lastInnovationY,
            lastInnovationTheta = snapshot.lastInnovationTheta,
            lastNormalizedInnovationSquared = snapshot.lastNormalizedInnovationSquared,
            lastKalmanGain = snapshot.copyKalmanGain(),
            lastMeasurementAccepted = snapshot.lastMeasurementAccepted,
            lastRejectionReason = snapshot.lastRejectionReason
        ).also { it.lastObservationTimestampMs = snapshot.lastObservationTimestampMs }
    }

    private companion object {
        const val HISTORY_CAPACITY = 150
    }
}
