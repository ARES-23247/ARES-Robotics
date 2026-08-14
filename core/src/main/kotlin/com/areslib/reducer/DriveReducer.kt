package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.DriveState
import com.areslib.state.DriveMode
import com.areslib.math.estimation.ApplyPoseEstimatorRuntimeResult

/**
 * Object implementation for Drive Reducer.
 *
 * Pure Redux state definition and deterministic reducer transition handler.
 */
object DriveReducer {
    /**
     * Reduces the DriveState slice independently based on drive actions.
     */
    fun reduce(state: DriveState, action: RobotAction): DriveState {
        return when (action) {
            is RobotAction.DriveHardwareUpdate -> {
                if (!action.xVelocity.isFinite() || !action.yVelocity.isFinite() ||
                    !action.angularVelocity.isFinite() || !action.deltaX.isFinite() ||
                    !action.deltaY.isFinite() || !action.deltaHeading.isFinite() ||
                    !action.pitchDegrees.isFinite() || !action.rollDegrees.isFinite() ||
                    !action.xAccelerationG.isFinite() || !action.yAccelerationG.isFinite() ||
                    !action.zAccelerationG.isFinite()) {
                    return state
                }

                observationDtSeconds(state, action.timestampMs) ?: return state
                val nextOdomX = state.odometryX + action.deltaX
                val nextOdomY = state.odometryY + action.deltaY
                val nextOdomHeading = state.odometryHeading + action.deltaHeading
                state.copy(
                    xVelocityMetersPerSecond = action.xVelocity,
                    yVelocityMetersPerSecond = action.yVelocity,
                    angularVelocityRadiansPerSecond = action.angularVelocity,
                    odometryX = nextOdomX,
                    odometryY = nextOdomY,
                    odometryHeading = nextOdomHeading,
                    pitchDegrees = action.pitchDegrees,
                    rollDegrees = action.rollDegrees,
                    xAccelerationG = action.xAccelerationG,
                    yAccelerationG = action.yAccelerationG,
                    zAccelerationG = action.zAccelerationG
                )
            }
            is RobotAction.PoseUpdate -> {
                if (!action.xMeters.isFinite() || !action.yMeters.isFinite() ||
                    !action.headingRadians.isFinite()) {
                    return state
                }

                val motionMeasurementsValid = action.motionMeasurementsValid &&
                    action.xVelocityMetersPerSecond.isFinite() &&
                    action.yVelocityMetersPerSecond.isFinite() &&
                    action.angularVelocityRadiansPerSecond.isFinite()
                val measuredFieldXVelocity = if (motionMeasurementsValid) {
                    action.xVelocityMetersPerSecond
                } else 0.0
                val measuredFieldYVelocity = if (motionMeasurementsValid) {
                    action.yVelocityMetersPerSecond
                } else 0.0
                val measuredAngularVelocity = if (motionMeasurementsValid) {
                    action.angularVelocityRadiansPerSecond
                } else 0.0
                val imuMeasurementsValid = action.imuMeasurementsValid &&
                    action.pitchDegrees.isFinite() && action.rollDegrees.isFinite() &&
                    action.pitchVelocityDegPerSec.isFinite() && action.rollVelocityDegPerSec.isFinite() &&
                    action.xAccelerationG.isFinite() && action.yAccelerationG.isFinite() &&
                    action.zAccelerationG.isFinite()
                val pitchDegrees = if (imuMeasurementsValid) action.pitchDegrees else 0.0
                val rollDegrees = if (imuMeasurementsValid) action.rollDegrees else 0.0
                val xAccelerationG = if (imuMeasurementsValid) action.xAccelerationG else 0.0
                val yAccelerationG = if (imuMeasurementsValid) action.yAccelerationG else 0.0
                val zAccelerationG = if (imuMeasurementsValid) action.zAccelerationG else 0.0

                if (!action.isReset && !action.isExternalEstimate) {
                    observationDtSeconds(state, action.timestampMs) ?: return state
                }

                state.copy(
                    odometryX = action.xMeters,
                    odometryY = action.yMeters,
                    odometryHeading = action.headingRadians,
                    poseEstimateIsExternal = action.isExternalEstimate && !action.isReset,
                    measuredFieldXVelocityMetersPerSecond = measuredFieldXVelocity,
                    measuredFieldYVelocityMetersPerSecond = measuredFieldYVelocity,
                    measuredAngularVelocityRadiansPerSecond = measuredAngularVelocity,
                    measuredMotionValid = motionMeasurementsValid,
                    pitchDegrees = pitchDegrees,
                    rollDegrees = rollDegrees,
                    imuMeasurementsValid = imuMeasurementsValid,
                    xAccelerationG = xAccelerationG,
                    yAccelerationG = yAccelerationG,
                    zAccelerationG = zAccelerationG,
                    headingLockTargetRadians = if (action.isReset) null else state.headingLockTargetRadians,
                    positionLockX = if (action.isReset) null else state.positionLockX,
                    positionLockY = if (action.isReset) null else state.positionLockY
                )
            }
            is ApplyPoseEstimatorRuntimeResult -> {
                val updatedEstimator = action.estimatorState ?: return state
                state.updateDiagnostics(
                    state.odometryX,
                    state.odometryY,
                    state.odometryHeading,
                    updatedEstimator
                )
            }
            is RobotAction.SetDriveMode -> {
                state.copy(driveMode = action.mode)
            }
            is RobotAction.SetAlliance -> {
                state.copy(alliance = action.alliance)
            }
            is RobotAction.SetHeadingLockTarget -> {
                state.copy(headingLockTargetRadians = action.targetRadians)
            }
            is RobotAction.SetPositionLockTarget -> {
                state.copy(positionLockX = action.targetX, positionLockY = action.targetY)
            }
            is RobotAction.JoystickDriveIntent -> {
                if (!action.targetXVelocity.isFinite() || !action.targetYVelocity.isFinite() ||
                    !action.targetAngularVelocity.isFinite()) {
                    return state.copy(
                        xVelocityMetersPerSecond = 0.0,
                        yVelocityMetersPerSecond = 0.0,
                        angularVelocityRadiansPerSecond = 0.0,
                        isXLock = false
                    )
                }
                val hasLinearInput = kotlin.math.abs(action.targetXVelocity) > 0.05 || kotlin.math.abs(action.targetYVelocity) > 0.05
                val hasAngularInput = !action.fromHeadingHold && kotlin.math.abs(action.targetAngularVelocity) > 0.05
                
                val currentMode = state.driveMode
                val newMode = if (currentMode == DriveMode.X_BRAKE && (hasLinearInput || hasAngularInput)) {
                    DriveMode.TELEOP
                } else {
                    currentMode
                }

                val newTargetHeading = if (hasAngularInput) {
                    null
                } else {
                    state.headingLockTargetRadians
                }

                val newPosLockX = if (hasLinearInput) null else state.positionLockX
                val newPosLockY = if (hasLinearInput) null else state.positionLockY

                state.copy(
                    xVelocityMetersPerSecond = action.targetXVelocity,
                    yVelocityMetersPerSecond = action.targetYVelocity,
                    angularVelocityRadiansPerSecond = action.targetAngularVelocity,
                    driveMode = newMode,
                    headingLockTargetRadians = newTargetHeading,
                    positionLockX = newPosLockX,
                    positionLockY = newPosLockY,
                    isFieldCentric = action.isFieldCentric,
                    isXLock = action.isXLock
                )
            }
            else -> state
        }
    }

    /**
     * Derives process-model time from sensor timestamps. Duplicate/out-of-order
     * samples are rejected; long scheduler stalls are bounded so one bad interval
     * cannot explode covariance or gyro-bias integration.
     */
    private fun observationDtSeconds(state: DriveState, timestampMs: Long): Double? {
        val lastTimestampMs = state.poseEstimator.lastObservationTimestampMs
        if (lastTimestampMs < 0L) return 0.02
        val deltaMs = timestampMs - lastTimestampMs
        if (deltaMs <= 0L) return null
        return (deltaMs / 1_000.0).coerceIn(0.001, 0.1)
    }
}
