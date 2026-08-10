package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.DriveState
import com.areslib.state.DriveMode
import com.areslib.math.wrapAngle
import com.areslib.math.estimation.HistoryBuffer
import com.areslib.math.geometry.Matrix3x3
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.estimation.PoseEstimator

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
                val updatedEstimator = PoseEstimator.addOdometryObservationDirect(
                    state = state.poseEstimator,
                    timestampMs = action.timestampMs,
                    deltaX = action.deltaX,
                    deltaY = action.deltaY,
                    deltaHeadingRad = action.deltaHeading,
                    pitchDegrees = action.pitchDegrees,
                    rollDegrees = action.rollDegrees
                )
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
                ).updateDiagnostics(nextOdomX, nextOdomY, nextOdomHeading, updatedEstimator)
            }
            is RobotAction.PoseUpdate -> {
                if (!action.xMeters.isFinite() || !action.yMeters.isFinite() ||
                    !action.headingRadians.isFinite()) {
                    return state
                }

                val updatedEstimator = if (action.isReset) {
                    val newPose = Pose2d(action.xMeters, action.yMeters, Rotation2d(action.headingRadians))
                    val newHistory = HistoryBuffer(150)
                    newHistory.addEntry(action.timestampMs, newPose, Matrix3x3(0.01, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.01), 1.0)
                    
                    state.poseEstimator.copy(
                        estimatedPoseX = newPose.x,
                        estimatedPoseY = newPose.y,
                        estimatedPoseHeading = newPose.heading.radians,
                        covarianceArray = doubleArrayOf(0.01, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.01),
                        history = newHistory,
                        isBeached = false,
                        lastUnbeachedTimeMs = action.timestampMs
                    )
                } else if (action.isExternalEstimate) {
                    PoseEstimator.acceptExternalEstimate(
                        state = state.poseEstimator,
                        timestampMs = action.timestampMs,
                        xMeters = action.xMeters,
                        yMeters = action.yMeters,
                        headingRadians = action.headingRadians
                    )
                } else {
                    val fieldDeltaX = action.xMeters - state.odometryX
                    val fieldDeltaY = action.yMeters - state.odometryY
                    val deltaHeading = wrapAngle(action.headingRadians - state.odometryHeading)

                    // PoseUpdate contains absolute field-frame odometry. Convert the
                    // relative field transform back into the robot-frame SE(2) twist
                    // consumed by PoseEstimator. Passing the field delta through directly
                    // rotates it a second time whenever the robot heading is non-zero.
                    val cosStart = kotlin.math.cos(state.odometryHeading)
                    val sinStart = kotlin.math.sin(state.odometryHeading)
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
                    PoseEstimator.addOdometryObservationDirect(
                        state = state.poseEstimator,
                        timestampMs = action.timestampMs,
                        deltaX = twistX,
                        deltaY = twistY,
                        deltaHeadingRad = deltaHeading,
                        pitchDegrees = action.pitchDegrees,
                        rollDegrees = action.rollDegrees,
                        gyroRateRadPerSec = action.angularVelocityRadiansPerSecond
                    )
                }

                state.copy(
                    odometryX = action.xMeters,
                    odometryY = action.yMeters,
                    odometryHeading = action.headingRadians,
                    poseEstimateIsExternal = action.isExternalEstimate && !action.isReset,
                    measuredFieldXVelocityMetersPerSecond = action.xVelocityMetersPerSecond,
                    measuredFieldYVelocityMetersPerSecond = action.yVelocityMetersPerSecond,
                    measuredAngularVelocityRadiansPerSecond = action.angularVelocityRadiansPerSecond,
                    pitchDegrees = action.pitchDegrees,
                    rollDegrees = action.rollDegrees,
                    xAccelerationG = action.xAccelerationG,
                    yAccelerationG = action.yAccelerationG,
                    zAccelerationG = action.zAccelerationG,
                    headingLockTargetRadians = if (action.isReset) null else state.headingLockTargetRadians,
                    positionLockX = if (action.isReset) null else state.positionLockX,
                    positionLockY = if (action.isReset) null else state.positionLockY
                ).updateDiagnostics(action.xMeters, action.yMeters, action.headingRadians, updatedEstimator)
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
}
