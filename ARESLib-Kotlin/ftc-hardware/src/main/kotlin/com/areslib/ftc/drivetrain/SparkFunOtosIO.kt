package com.areslib.ftc.drivetrain

import com.qualcomm.hardware.sparkfun.SparkFunOTOS
import com.areslib.action.RobotAction
import com.areslib.hardware.drive.OdometryIO
import com.areslib.hardware.drive.OdometryInputs
import com.areslib.math.geometry.Pose2d
import com.areslib.math.wrapAngle
import com.areslib.util.RobotClock
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit

/**
 * Hardware IO abstraction layer for the SparkFun Optical Tracking Odometry Sensor (OTOS).
 *
 * Input-container refresh reuses primitive state without allocating an ARES action. The SDK
 * getters can allocate internally; [getPoseUpdate] deliberately returns an owned action snapshot.
 * Reset invalidates motion/freshness until a successful read, and a failed reset requires an
 * explicit successful initialization. Close stops sampling without closing the caller-owned SDK
 * driver. Results from reads started before reset/close cannot republish into the new lifecycle.
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
        private const val MAX_LINEAR_SPEED_METERS_PER_SECOND = 8.0
        private const val MAX_ANGULAR_SPEED_RADIANS_PER_SECOND = 4.0 * Math.PI
        private const val POSITION_JUMP_TOLERANCE_METERS = 0.75
        private const val HEADING_JUMP_TOLERANCE_RADIANS = 0.75
    }

    private var offsetX = 0.0
    private var offsetY = 0.0
    private var offsetHeading = 0.0
    private val sampleLock = Any()
    private var generation = 0L
    private var samplingEnabled = true
    private var closed = false

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
        require(xOffsetMeters.isFinite() && yOffsetMeters.isFinite()) { "OTOS mounting offsets must be finite" }
        require(linearScalar.isFinite() && angularScalar.isFinite()) { "OTOS scalars must be finite" }
        driver.setLinearUnit(DistanceUnit.METER)
        driver.setAngularUnit(AngleUnit.RADIANS)
        driver.setOffset(SparkFunOTOS.Pose2D(xOffsetMeters, yOffsetMeters, 0.0))
        check(driver.setLinearScalar(linearScalar)) { "OTOS rejected the linear scalar" }
        check(driver.setAngularScalar(angularScalar)) { "OTOS rejected the angular scalar" }
        check(driver.calibrateImu()) { "OTOS IMU calibration failed" }
        driver.resetTracking()
    }

    @Synchronized
    override fun initialize(startPose: Pose2d) {
        val x = startPose.x
        val y = startPose.y
        val heading = startPose.heading.radians
        require(x.isFinite() && y.isFinite() && heading.isFinite()) { "OTOS starting pose must be finite" }
        val token = synchronized(sampleLock) {
            check(!closed) { "OTOS IO is closed" }
            generation++
            samplingEnabled = false
            invalidateSample()
            healthStatus = HealthStatus.STARTING
            consecutiveReadFailures = 0
            generation
        }
        try {
            driver.resetTracking()
        } catch (failure: Exception) {
            synchronized(sampleLock) {
                if (generation == token) markReadFailure(HealthStatus.COMMUNICATION_FAILURE)
            }
            throw failure
        }
        synchronized(sampleLock) {
            check(!closed && generation == token) { "OTOS initialization was superseded by close" }
            offsetX = x; offsetY = y; offsetHeading = heading
            lastX = x; lastY = y; lastHeading = heading
            samplingEnabled = true
        }
    }

    /** Polls once and returns an independently owned snapshot; prefer [updateInputs] in hot loops. */
    fun getPoseUpdate(): RobotAction.PoseUpdate {
        refreshSample()
        return synchronized(sampleLock) {
            val trusted = hasTrustedSample && healthStatus == HealthStatus.HEALTHY
            RobotAction.PoseUpdate(
                xMeters = lastX, yMeters = lastY, headingRadians = lastHeading,
                timestampMs = if (trusted) lastTimestampMs else 0L,
                xVelocityMetersPerSecond = lastVelX, yVelocityMetersPerSecond = lastVelY,
                angularVelocityRadiansPerSecond = lastHeadingVelocity,
                motionMeasurementsValid = trusted,
                applyControlHubGyroCorrection = false,
            )
        }
    }

    private fun refreshSample() {
        val token = synchronized(sampleLock) {
            if (!samplingEnabled || closed) return
            generation
        }
        try {
            val now = RobotClock.currentTimeMillis()
            val rawPos = driver.getPosition()
            if (!isCurrentGeneration(token)) return
            val rawVel = driver.getVelocity()
            synchronized(sampleLock) {
                if (generation != token || !samplingEnabled || closed) return
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
                if (finite && hasObservationBaseline) {
                    if (now < observationTimestampMs) {
                        deltaPlausible = false
                    } else {
                        val dtSeconds = (now - observationTimestampMs) / 1000.0
                        val maxDistance = MAX_LINEAR_SPEED_METERS_PER_SECOND * dtSeconds + POSITION_JUMP_TOLERANCE_METERS
                        val maxHeading = MAX_ANGULAR_SPEED_RADIANS_PER_SECOND * dtSeconds + HEADING_JUMP_TOLERANCE_RADIANS
                        deltaPlausible = kotlin.math.hypot(x - observationX, y - observationY) <= maxDistance &&
                            kotlin.math.abs(wrapAngle(nextHeading - observationHeading)) <= maxHeading
                    }
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
            }
        } catch (_: Exception) {
            synchronized(sampleLock) {
                if (generation == token && samplingEnabled && !closed) {
                    markReadFailure(HealthStatus.COMMUNICATION_FAILURE)
                }
            }
        }
    }

    override fun updateInputs(inputs: OdometryInputs) {
        refreshSample()
        synchronized(sampleLock) {
            val trusted = hasTrustedSample && healthStatus == HealthStatus.HEALTHY
            inputs.posX = lastX; inputs.posY = lastY; inputs.heading = lastHeading
            inputs.velX = lastVelX; inputs.velY = lastVelY; inputs.headingVelocity = lastHeadingVelocity
            inputs.timestampMs = if (trusted) lastTimestampMs else 0L
        }
    }

    private fun invalidateSample() {
        lastVelX = 0.0; lastVelY = 0.0; lastHeadingVelocity = 0.0
        lastTimestampMs = 0L
        hasTrustedSample = false
        hasObservationBaseline = false
    }

    private fun isCurrentGeneration(token: Long): Boolean = synchronized(sampleLock) {
        generation == token && samplingEnabled && !closed
    }

    private fun markReadFailure(failureStatus: HealthStatus) {
        lastVelX = 0.0; lastVelY = 0.0; lastHeadingVelocity = 0.0
        consecutiveReadFailures++
        healthStatus = failureStatus
    }

    override fun close() {
        synchronized(sampleLock) {
            if (closed) return
            closed = true
            generation++
            samplingEnabled = false
            invalidateSample()
            healthStatus = HealthStatus.STALE
        }
    }
}
