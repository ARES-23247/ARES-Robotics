package org.aresfirst.starter.frc

import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic educational chassis model used before a team chooses physical hardware.
 *
 * It consumes the same immutable Redux drive intent as the robot runtime. This intentionally models
 * ideal motion, not wheel slip or current draw; the UI labels its results as simulation evidence.
 */
class StarterDriveSimulation(
    startX: Double = 1.0,
    startY: Double = 1.0,
    startHeadingRadians: Double = 0.0,
) {
    var xMeters: Double = startX
        private set
    var yMeters: Double = startY
        private set
    var headingRadians: Double = startHeadingRadians
        private set

    private val poseUpdate = RobotAction.PoseUpdate(
        xMeters = startX,
        yMeters = startY,
        headingRadians = startHeadingRadians,
        timestampMs = 0L,
        isExternalEstimate = true,
        applyControlHubGyroCorrection = false,
    )

    /** Advances one bounded frame and returns a caller-reused pose action. */
    fun step(state: RobotState, dtSeconds: Double, timestampMs: Long): RobotAction.PoseUpdate {
        val dt = if (dtSeconds.isFinite()) dtSeconds.coerceIn(0.0, 0.05) else 0.0
        val drive = state.drive
        val commandedVx = drive.xVelocityMetersPerSecond.takeIf(Double::isFinite) ?: 0.0
        val commandedVy = drive.yVelocityMetersPerSecond.takeIf(Double::isFinite) ?: 0.0
        val omega = drive.angularVelocityRadiansPerSecond.takeIf(Double::isFinite) ?: 0.0
        val fieldVx: Double
        val fieldVy: Double
        if (drive.isFieldCentric) {
            fieldVx = commandedVx
            fieldVy = commandedVy
        } else {
            val c = cos(headingRadians)
            val s = sin(headingRadians)
            fieldVx = commandedVx * c - commandedVy * s
            fieldVy = commandedVx * s + commandedVy * c
        }
        xMeters += fieldVx * dt
        yMeters += fieldVy * dt
        headingRadians = wrapRadians(headingRadians + omega * dt)
        poseUpdate.xMeters = xMeters
        poseUpdate.yMeters = yMeters
        poseUpdate.headingRadians = headingRadians
        poseUpdate.timestampMs = timestampMs
        poseUpdate.xVelocityMetersPerSecond = fieldVx
        poseUpdate.yVelocityMetersPerSecond = fieldVy
        poseUpdate.angularVelocityRadiansPerSecond = omega
        poseUpdate.motionMeasurementsValid = true
        poseUpdate.imuMeasurementsValid = true
        return poseUpdate
    }

    private fun wrapRadians(value: Double): Double {
        var result = value
        while (result > PI) result -= 2.0 * PI
        while (result < -PI) result += 2.0 * PI
        return result
    }
}
