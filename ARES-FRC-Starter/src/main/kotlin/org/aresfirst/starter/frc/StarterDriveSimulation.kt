package org.aresfirst.starter.frc

import com.areslib.action.RobotAction
import com.areslib.math.wrapAngle
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotState
import kotlin.math.abs
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
    init {
        require(startX.isFinite() && startY.isFinite() && startHeadingRadians.isFinite()) {
            "FRC simulator pose must contain finite values"
        }
    }

    var xMeters: Double = startX
        private set
    var yMeters: Double = startY
        private set
    var headingRadians: Double = wrapAngle(startHeadingRadians)
        private set

    private var fieldConfig: RobotFieldConfig? = null
    private var collision: StarterDriveCollision? = null

    private val poseUpdate = RobotAction.PoseUpdate(
        xMeters = startX,
        yMeters = startY,
        headingRadians = headingRadians,
        timestampMs = 0L,
        isExternalEstimate = true,
        applyControlHubGyroCorrection = false,
    )

    /** Installs the same canonical field revision used by Studio and robot vision. */
    fun configureField(config: RobotFieldConfig) {
        require(config.fieldType == FieldType.FRC) { "FRC simulation requires an FRC field document" }
        val nextCollision = StarterDriveCollision(config, ROBOT_HALF_LENGTH_METERS, ROBOT_HALF_WIDTH_METERS)
        val c = abs(cos(headingRadians))
        val s = abs(sin(headingRadians))
        val xExtent = c * ROBOT_HALF_LENGTH_METERS + s * ROBOT_HALF_WIDTH_METERS
        val yExtent = s * ROBOT_HALF_LENGTH_METERS + c * ROBOT_HALF_WIDTH_METERS
        val width = config.resolvedWidthMeters
        val height = config.resolvedHeightMeters
        require(width.isFinite() && height.isFinite() && width >= 2.0 * xExtent && height >= 2.0 * yExtent) {
            "FRC simulation field must fit the full bumper footprint"
        }
        // Compute the whole replacement before committing it; a failed configuration retains
        // the previous field and pose instead of leaving an unusable field installed.
        val nextX = xMeters.coerceIn(xExtent, width - xExtent)
        val nextY = yMeters.coerceIn(yExtent, height - yExtent)
        fieldConfig = config
        collision = nextCollision
        xMeters = nextX
        yMeters = nextY
    }

    /** Seeds the ideal chassis from the selected autonomous entry before the first enabled tick. */
    fun resetPose(xMeters: Double, yMeters: Double, headingRadians: Double) {
        require(xMeters.isFinite() && yMeters.isFinite() && headingRadians.isFinite()) {
            "FRC simulator pose must contain finite values"
        }
        val heading = wrapAngle(headingRadians)
        val field = fieldConfig
        if (field != null) {
            val c = abs(cos(heading))
            val s = abs(sin(heading))
            val xExtent = c * ROBOT_HALF_LENGTH_METERS + s * ROBOT_HALF_WIDTH_METERS
            val yExtent = s * ROBOT_HALF_LENGTH_METERS + c * ROBOT_HALF_WIDTH_METERS
            require(
                xMeters in xExtent..(field.resolvedWidthMeters - xExtent) &&
                    yMeters in yExtent..(field.resolvedHeightMeters - yExtent)
            ) { "FRC autonomous start pose leaves the configured field" }
            require(collision?.isPoseFree(xMeters, yMeters, heading) != false) {
                "FRC autonomous start pose overlaps a blocking obstacle"
            }
        }
        this.xMeters = xMeters
        this.yMeters = yMeters
        this.headingRadians = heading
    }

    /**
     * Integrates a constant drive twist over a bounded frame and reuses the pose action.
     * Reported translation is the actual mean field velocity over the accepted interval,
     * including collision rejection. A nonpositive/invalid interval is not a fresh motion sample.
     */
    fun step(state: RobotState, dtSeconds: Double, timestampMs: Long): RobotAction.PoseUpdate {
        val dt = if (dtSeconds.isFinite()) dtSeconds.coerceIn(0.0, 0.05) else 0.0
        val drive = state.drive
        val commandedVx = drive.xVelocityMetersPerSecond.takeIf(Double::isFinite) ?: 0.0
        val commandedVy = drive.yVelocityMetersPerSecond.takeIf(Double::isFinite) ?: 0.0
        val omega = drive.angularVelocityRadiansPerSecond.takeIf(Double::isFinite) ?: 0.0
        val previousX = xMeters
        val previousY = yMeters
        val deltaHeading = omega * dt
        val deltaX: Double
        val deltaY: Double
        if (drive.isFieldCentric) {
            deltaX = commandedVx * dt
            deltaY = commandedVy * dt
        } else {
            // SE(2) exponential for body-frame velocity while the chassis turns. The small-angle
            // series retains curvature that would be lost to cancellation in 1 - cos(theta).
            val sinc: Double
            val cosc: Double
            if (abs(deltaHeading) < 1e-6) {
                val squared = deltaHeading * deltaHeading
                sinc = 1.0 - squared / 6.0
                cosc = deltaHeading * (0.5 - squared / 24.0)
            } else {
                sinc = sin(deltaHeading) / deltaHeading
                val halfSine = sin(deltaHeading / 2.0)
                cosc = 2.0 * halfSine * halfSine / deltaHeading
            }
            val forward = commandedVx * dt
            val strafe = commandedVy * dt
            val bodyX = forward * sinc - strafe * cosc
            val bodyY = forward * cosc + strafe * sinc
            val c = cos(headingRadians)
            val s = sin(headingRadians)
            deltaX = bodyX * c - bodyY * s
            deltaY = bodyX * s + bodyY * c
        }
        val proposedX = xMeters + deltaX
        val proposedY = yMeters + deltaY
        // Discrete educational contact response: sweep X, then Y, then the rotation arc. Free
        // motion has the exact constant-twist endpoint; contact uses conservative envelopes,
        // not a continuous rigid-body solver for the original curved center trajectory.
        val currentCollision = collision
        if (proposedX.isFinite() && proposedY.isFinite()) {
            if (currentCollision?.isTranslationFree(xMeters, yMeters, proposedX, yMeters, headingRadians) != false) xMeters = proposedX
            if (currentCollision?.isTranslationFree(xMeters, yMeters, xMeters, proposedY, headingRadians) != false) yMeters = proposedY
        }
        val rotationAccepted = currentCollision?.isRotationFree(xMeters, yMeters, headingRadians, deltaHeading) != false
        if (rotationAccepted) headingRadians = wrapAngle(headingRadians + deltaHeading)
        poseUpdate.xMeters = xMeters
        poseUpdate.yMeters = yMeters
        poseUpdate.headingRadians = headingRadians
        poseUpdate.timestampMs = timestampMs
        val measuredVx = if (dt > 0.0) (xMeters - previousX) / dt else 0.0
        val measuredVy = if (dt > 0.0) (yMeters - previousY) / dt else 0.0
        val measurementsValid = dt > 0.0 && measuredVx.isFinite() && measuredVy.isFinite()
        poseUpdate.xVelocityMetersPerSecond = if (measurementsValid) measuredVx else 0.0
        poseUpdate.yVelocityMetersPerSecond = if (measurementsValid) measuredVy else 0.0
        poseUpdate.angularVelocityRadiansPerSecond = if (measurementsValid && rotationAccepted) omega else 0.0
        poseUpdate.motionMeasurementsValid = measurementsValid
        poseUpdate.imuMeasurementsValid = true
        return poseUpdate
    }

    private companion object {
        /** The generic starter's documented 0.75 m × 0.65 m bumper footprint. */
        const val ROBOT_HALF_LENGTH_METERS = 0.375
        const val ROBOT_HALF_WIDTH_METERS = 0.325
    }
}
