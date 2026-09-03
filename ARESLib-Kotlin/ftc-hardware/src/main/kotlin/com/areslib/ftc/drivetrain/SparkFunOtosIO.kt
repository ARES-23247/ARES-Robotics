package com.areslib.ftc.drivetrain

import com.qualcomm.hardware.sparkfun.SparkFunOTOS
import com.areslib.action.RobotAction
import com.areslib.hardware.drive.OdometryIO
import com.areslib.hardware.drive.OdometryInputs
import com.areslib.math.geometry.Pose2d
import com.areslib.math.wrapAngle
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit

/**
 * Hardware IO abstraction layer for the SparkFun Optical Tracking Odometry Sensor (OTOS).
 *
 * Provides high-frequency, zero-GC hardware reads for downward-facing optical surface tracking
 * fused with onboard IMU heading.
 *
 * ### Coordinate Conventions:
 * - Position: X = Forward (m), Y = Left (m).
 * - Heading: CCW-positive radians in [-PI, PI].
 */
class SparkFunOtosIO @kotlin.jvm.JvmOverloads constructor(
    private val driver: SparkFunOTOS,
    xOffsetMeters: Double = 0.0,
    yOffsetMeters: Double = 0.0,
    linearScalar: Double = 1.0,
    angularScalar: Double = 1.0,
    private val isHeadingCcwPositive: Boolean = true
) : OdometryIO, AutoCloseable {

    enum class HealthStatus {
        STARTING,
        HEALTHY,
        STALE,
        NONFINITE,
        IMPLAUSIBLE,
        COMMUNICATION_FAILURE
    }

    companion object {
        private const val DEFAULT_MAX_SAMPLE_AGE_MS = 100L
        private const val MAX_LINEAR_SPEED_METERS_PER_SECOND = 8.0
        private const val MAX_ANGULAR_SPEED_RADIANS_PER_SECOND = 4.0 * Math.PI
        private const val POSITION_JUMP_TOLERANCE_METERS = 0.75
        private const val HEADING_JUMP_TOLERANCE_RADIANS = 0.75
    }

    private var offsetX = 0.0
    private var offsetY = 0.0
    private var offsetHeading = 0.0

    @Volatile
    var healthStatus: HealthStatus = HealthStatus.STARTING
        private set

    @Volatile
    var consecutiveReadFailures: Int = 0
        private set

    private var lastX = 0.0
    private var lastY = 0.0
    private var lastHeading = 0.0
    private var lastHeadingVelocity = 0.0
    private var lastVelX = 0.0
    private var lastVelY = 0.0
    private var lastTimestampMs = 0L
    private var hasTrustedSample = false

    private var hasObservationBaseline = false
    private var observationX = 0.0
    private var observationY = 0.0
    private var observationHeading = 0.0
    private var observationTimestampMs = 0L

    init {
        driver.setLinearUnit(DistanceUnit.METER)
        driver.setAngularUnit(AngleUnit.RADIANS)
        driver.setOffset(SparkFunOTOS.Pose2D(xOffsetMeters, yOffsetMeters, 0.0))
        driver.setLinearScalar(linearScalar)
        driver.setAngularScalar(angularScalar)
        driver.calibrateImu()
        driver.resetTracking()
    }

    override fun initialize(startPose: Pose2d) {
        driver.resetTracking()
        offsetX = startPose.x
        offsetY = startPose.y
        offsetHeading = startPose.heading.radians
        lastX = startPose.x
        lastY = startPose.y
        lastHeading = startPose.heading.radians
        lastTimestampMs = com.areslib.util.RobotClock.currentTimeMillis()
        hasTrustedSample = true
        hasObservationBaseline = false
        healthStatus = HealthStatus.STARTING
        consecutiveReadFailures = 0
    }

    fun getPoseUpdate(): RobotAction.PoseUpdate {
        try {
            val now = com.areslib.util.RobotClock.currentTimeMillis()
            val rawPos = driver.getPosition()
            val rawVel = driver.getVelocity()

            val headingMult = if (isHeadingCcwPositive) 1.0 else -1.0
            val rawHeading = headingMult * rawPos.h
            val rawHeadingVelocity = headingMult * rawVel.h

            val cosH = kotlin.math.cos(offsetHeading)
            val sinH = kotlin.math.sin(offsetHeading)
            val x = rawPos.x * cosH - rawPos.y * sinH + offsetX
            val y = rawPos.x * sinH + rawPos.y * cosH + offsetY
            val nextHeading = wrapAngle(rawHeading + offsetHeading)

            val velX = rawVel.x * cosH - rawVel.y * sinH
            val velY = rawVel.x * sinH + rawVel.y * cosH

            val finite = x.isFinite() && y.isFinite() && nextHeading.isFinite() &&
                rawHeadingVelocity.isFinite() && velX.isFinite() && velY.isFinite()
            val velocityPlausible = finite &&
                kotlin.math.hypot(velX, velY) <= MAX_LINEAR_SPEED_METERS_PER_SECOND &&
                kotlin.math.abs(rawHeadingVelocity) <= MAX_ANGULAR_SPEED_RADIANS_PER_SECOND

            var deltaPlausible = true
            if (finite && hasObservationBaseline && now >= observationTimestampMs) {
                val dtSeconds = kotlin.math.max(0.0, (now - observationTimestampMs) / 1000.0)
                val maxDistance = MAX_LINEAR_SPEED_METERS_PER_SECOND * dtSeconds + POSITION_JUMP_TOLERANCE_METERS
                val maxHeading = MAX_ANGULAR_SPEED_RADIANS_PER_SECOND * dtSeconds + HEADING_JUMP_TOLERANCE_RADIANS
                deltaPlausible = kotlin.math.hypot(x - observationX, y - observationY) <= maxDistance &&
                    kotlin.math.abs(wrapAngle(nextHeading - observationHeading)) <= maxHeading
            }

            if (finite) {
                observationX = x
                observationY = y
                observationHeading = nextHeading
                observationTimestampMs = now
                hasObservationBaseline = true
            }

            when {
                !finite -> markReadFailure(HealthStatus.NONFINITE)
                !velocityPlausible || !deltaPlausible -> markReadFailure(HealthStatus.IMPLAUSIBLE)
                else -> {
                    lastX = x
                    lastY = y
                    lastHeading = nextHeading
                    lastHeadingVelocity = rawHeadingVelocity
                    lastVelX = velX
                    lastVelY = velY
                    lastTimestampMs = now
                    hasTrustedSample = true
                    consecutiveReadFailures = 0
                    healthStatus = HealthStatus.HEALTHY
                }
            }
        } catch (_: Exception) {
            markReadFailure(HealthStatus.COMMUNICATION_FAILURE)
        }

        return RobotAction.PoseUpdate(
            xMeters = lastX,
            yMeters = lastY,
            headingRadians = lastHeading,
            timestampMs = lastTimestampMs,
            xVelocityMetersPerSecond = lastVelX,
            yVelocityMetersPerSecond = lastVelY,
            angularVelocityRadiansPerSecond = lastHeadingVelocity,
            applyControlHubGyroCorrection = false
        )
    }

    override fun updateInputs(inputs: OdometryInputs) {
        val update = getPoseUpdate()
        inputs.posX = update.xMeters
        inputs.posY = update.yMeters
        inputs.heading = update.headingRadians
        inputs.velX = update.xVelocityMetersPerSecond
        inputs.velY = update.yVelocityMetersPerSecond
        inputs.headingVelocity = update.angularVelocityRadiansPerSecond
        inputs.timestampMs = update.timestampMs
    }

    private fun markReadFailure(failureStatus: HealthStatus) {
        consecutiveReadFailures++
        healthStatus = failureStatus
    }

    override fun close() {
        // Driver resources released
    }
}
