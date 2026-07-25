package com.areslib.reducer

import com.areslib.action.RobotAction
import com.areslib.state.DriveState
import com.areslib.state.DriveMode
import com.areslib.math.wrapAngle
import com.areslib.math.kinematics.OdometryMath
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
                val deltaTrans = Translation2d(action.deltaX, action.deltaY)
                val deltaHeading = Rotation2d(action.deltaHeading)
                val updatedEstimator = PoseEstimator.addOdometryObservation(
                    state = state.poseEstimator,
                    timestampMs = action.timestampMs,
                    deltaTranslation = deltaTrans,
                    deltaHeading = deltaHeading,
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
                val updatedEstimator = if (action.isReset) {
                    val newPose = Pose2d(action.xMeters, action.yMeters, Rotation2d(action.headingRadians))
                    val newHistory = HistoryBuffer(50)
                    newHistory.addEntry(action.timestampMs, newPose, Matrix3x3(0.01, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.01), 1.0)
                    
                    state.poseEstimator.apply {
                        estimatedPoseX = newPose.x
                        estimatedPoseY = newPose.y
                        estimatedPoseHeading = newPose.heading.radians
                        covarianceArray[0] = 0.01; covarianceArray[1] = 0.0; covarianceArray[2] = 0.0
                        covarianceArray[3] = 0.0; covarianceArray[4] = 0.01; covarianceArray[5] = 0.0
                        covarianceArray[6] = 0.0; covarianceArray[7] = 0.0; covarianceArray[8] = 0.01
                        newHistory.copyInto(this.history)
                        isBeached = false
                        lastUnbeachedTimeMs = action.timestampMs
                    }
                } else {
                    val deltaX = action.xMeters - state.odometryX
                    val deltaY = action.yMeters - state.odometryY
                    val deltaHeading = wrapAngle(action.headingRadians - state.odometryHeading)

                    val headingDiff = state.poseEstimator.estimatedPoseHeading - state.odometryHeading
                    val deltaFieldX = OdometryMath.calculateDeltaX(headingDiff, deltaX, deltaY)
                    val deltaFieldY = OdometryMath.calculateDeltaY(headingDiff, deltaX, deltaY)
                    PoseEstimator.addOdometryObservationDirect(
                        state = state.poseEstimator,
                        timestampMs = action.timestampMs,
                        deltaX = deltaFieldX,
                        deltaY = deltaFieldY,
                        deltaHeadingRad = deltaHeading,
                        pitchDegrees = action.pitchDegrees,
                        rollDegrees = action.rollDegrees
                    )
                }

                state.copy(
                    odometryX = action.xMeters,
                    odometryY = action.yMeters,
                    odometryHeading = action.headingRadians,
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
