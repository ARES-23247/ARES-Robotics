package com.areslib.subsystem

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.state.RobotState

/**
 * Single-loop Redux command facade. Sensor acquisition and physical outputs belong to the
 * registered platform IO; this facade performs neither. Reuses its synchronous drive action.
 */
class DriveSubsystem(private val store: Store) : DrivetrainSubsystem {
    var maxSpeedMps: Double = 3.5
    var maxAngularSpeedRadiansPerSecond: Double = 9.5
    private val driveIntent = RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0)

    val xVelocity: Double 
        get() = store.state.drive.measuredFieldXVelocityMetersPerSecond

    val yVelocity: Double 
        get() = store.state.drive.measuredFieldYVelocityMetersPerSecond

    val odometryX: Double 
        get() = store.state.drive.odometryX

    val odometryY: Double 
        get() = store.state.drive.odometryY

    val odometryHeading: Double 
        get() = store.state.drive.odometryHeading

    val odometryPose: Pose2d 
        get() = store.state.drive.poseEstimator.estimatedPose

    val angularVelocity: Double
        get() = store.state.drive.measuredAngularVelocityRadiansPerSecond

    /**
     * Dispatches m/s and rad/s, with one circular translation limit and a separate angular limit.
     * Invalid inputs or nonpositive/nonfinite limits neutralize the complete command. Every call
     * refreshes its RobotClock timestamp; subscribers retaining actions must snapshot them.
     */
    fun joystickDrive(x: Double, y: Double, rot: Double, isFieldCentric: Boolean = true, isXLock: Boolean = false) {
        driveIntent.setLimitedVelocities(x, y, rot, maxSpeedMps, maxAngularSpeedRadiansPerSecond)
        driveIntent.isFieldCentric = isFieldCentric
        driveIntent.isXLock = isXLock
        driveIntent.fromHeadingHold = false
        driveIntent.fromPositionHold = false
        driveIntent.timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
        store.dispatch(driveIntent)
    }

    override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) {
        joystickDrive(
            x = vx,
            y = vy,
            rot = omega,
            isFieldCentric = false
        )
    }

    override fun getEstimatedPose(): Pose2d {
        return odometryPose
    }

    override fun readSensors(store: Store, timestampMs: Long) {}
    override fun writeOutputs(state: RobotState, scale: Double) {}
}
