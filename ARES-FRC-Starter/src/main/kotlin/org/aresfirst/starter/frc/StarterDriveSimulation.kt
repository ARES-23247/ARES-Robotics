package org.aresfirst.starter.frc

import com.areslib.action.RobotAction
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldObstacle
import com.areslib.state.RobotState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
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

    private var fieldConfig: RobotFieldConfig? = null

    private val poseUpdate = RobotAction.PoseUpdate(
        xMeters = startX,
        yMeters = startY,
        headingRadians = startHeadingRadians,
        timestampMs = 0L,
        isExternalEstimate = true,
        applyControlHubGyroCorrection = false,
    )

    /** Installs the same canonical field revision used by Studio and robot vision. */
    fun configureField(config: RobotFieldConfig) {
        require(config.fieldType == FieldType.FRC) { "FRC simulation requires an FRC field document" }
        fieldConfig = config
        xMeters = xMeters.coerceIn(ROBOT_HALF_LENGTH_METERS, config.resolvedWidthMeters - ROBOT_HALF_LENGTH_METERS)
        yMeters = yMeters.coerceIn(ROBOT_HALF_WIDTH_METERS, config.resolvedHeightMeters - ROBOT_HALF_WIDTH_METERS)
    }

    /** Seeds the ideal chassis from the selected autonomous entry before the first enabled tick. */
    fun resetPose(xMeters: Double, yMeters: Double, headingRadians: Double) {
        require(xMeters.isFinite() && yMeters.isFinite() && headingRadians.isFinite()) {
            "FRC simulator pose must contain finite values"
        }
        val field = fieldConfig
        if (field != null) {
            require(
                xMeters in ROBOT_HALF_LENGTH_METERS..(field.resolvedWidthMeters - ROBOT_HALF_LENGTH_METERS) &&
                    yMeters in ROBOT_HALF_WIDTH_METERS..(field.resolvedHeightMeters - ROBOT_HALF_WIDTH_METERS)
            ) { "FRC autonomous start pose leaves the configured field" }
            require(isPoseFree(xMeters, yMeters, headingRadians)) {
                "FRC autonomous start pose overlaps a blocking obstacle"
            }
        }
        this.xMeters = xMeters
        this.yMeters = yMeters
        this.headingRadians = wrapRadians(headingRadians)
    }

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
        val proposedX = xMeters + fieldVx * dt
        val proposedY = yMeters + fieldVy * dt
        // Resolve one axis at a time so a novice sees the chassis slide along a wall instead of
        // tunnelling through it or becoming numerically stuck at a diagonal collision.
        if (isPoseFree(proposedX, yMeters, headingRadians)) xMeters = proposedX
        if (isPoseFree(xMeters, proposedY, headingRadians)) yMeters = proposedY
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

    private fun isPoseFree(x: Double, y: Double, heading: Double): Boolean {
        val field = fieldConfig ?: return true
        val xExtent = abs(cos(heading)) * ROBOT_HALF_LENGTH_METERS + abs(sin(heading)) * ROBOT_HALF_WIDTH_METERS
        val yExtent = abs(sin(heading)) * ROBOT_HALF_LENGTH_METERS + abs(cos(heading)) * ROBOT_HALF_WIDTH_METERS
        if (x - xExtent < 0.0 || x + xExtent > field.resolvedWidthMeters) return false
        if (y - yExtent < 0.0 || y + yExtent > field.resolvedHeightMeters) return false
        for (index in field.obstacles.indices) {
            val obstacle = field.obstacles[index]
            if (obstacle.isBlocking && overlapsObstacle(x, y, heading, obstacle)) return false
        }
        return true
    }

    private fun overlapsObstacle(x: Double, y: Double, heading: Double, obstacle: RobotFieldObstacle): Boolean =
        when (obstacle.shape.lowercase()) {
            "circle" -> hypot(x - obstacle.x, y - obstacle.y) <= obstacle.width + ROBOT_BOUNDING_RADIUS_METERS
            "polygon" -> overlapsPolygon(x, y, obstacle)
            else -> overlapsRectangle(x, y, heading, obstacle)
        }

    private fun overlapsRectangle(x: Double, y: Double, heading: Double, obstacle: RobotFieldObstacle): Boolean {
        val obstacleHeading = Math.toRadians(obstacle.rotation)
        val c = cos(obstacleHeading)
        val s = sin(obstacleHeading)
        val dx = x - obstacle.x
        val dy = y - obstacle.y
        val localX = dx * c + dy * s
        val localY = -dx * s + dy * c
        val relativeHeading = heading - obstacleHeading
        val robotExtentX = abs(cos(relativeHeading)) * ROBOT_HALF_LENGTH_METERS +
            abs(sin(relativeHeading)) * ROBOT_HALF_WIDTH_METERS
        val robotExtentY = abs(sin(relativeHeading)) * ROBOT_HALF_LENGTH_METERS +
            abs(cos(relativeHeading)) * ROBOT_HALF_WIDTH_METERS
        return abs(localX) <= obstacle.width / 2.0 + robotExtentX &&
            abs(localY) <= obstacle.height / 2.0 + robotExtentY
    }

    private fun overlapsPolygon(x: Double, y: Double, obstacle: RobotFieldObstacle): Boolean {
        val points = obstacle.points
        if (points.size < 3) return false
        var inside = false
        var previous = points.lastIndex
        for (current in points.indices) {
            val a = points[current]
            val b = points[previous]
            if ((a.y > y) != (b.y > y)) {
                val crossingX = (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x
                if (x < crossingX) inside = !inside
            }
            if (distanceToSegment(x, y, a.x, a.y, b.x, b.y) <= ROBOT_BOUNDING_RADIUS_METERS) return true
            previous = current
        }
        return inside
    }

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-12) return hypot(px - ax, py - ay)
        val t = ((px - ax) * dx + (py - ay) * dy) / lengthSquared
        val clamped = max(0.0, min(1.0, t))
        return hypot(px - (ax + clamped * dx), py - (ay + clamped * dy))
    }

    private fun wrapRadians(value: Double): Double {
        var result = value
        while (result > PI) result -= 2.0 * PI
        while (result < -PI) result += 2.0 * PI
        return result
    }

    private companion object {
        /** The generic starter's documented 0.75 m × 0.65 m bumper footprint. */
        const val ROBOT_HALF_LENGTH_METERS = 0.375
        const val ROBOT_HALF_WIDTH_METERS = 0.325
        val ROBOT_BOUNDING_RADIUS_METERS = hypot(ROBOT_HALF_LENGTH_METERS, ROBOT_HALF_WIDTH_METERS)
    }
}
