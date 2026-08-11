package com.areslib.ftc.drivetrain

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.areslib.action.RobotAction
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import com.areslib.math.wrapAngle

/**
 * Hardware IO abstraction layer for the GoBilda Pinpoint Odometry Computer.
 *
 * Provides high-frequency, zero-GC hardware reads for dead-wheel tracking pods and onboard 6-DOF IMU fusion.
 *
 * ### Hardware Boundary & Coordinate System:
 * - **Position**: $X$ = Forward ($m$), $Y$ = Left ($m$).
 * - **Velocity**: $v_x$ ($m/s$), $v_y$ ($m/s$).
 * - **Heading Convention**: Normalized to **Counter-Clockwise (CCW) Positive** math standard:
 *   $$\theta \in [-\pi, \pi], \quad 0 \text{ rad} = +X, \quad +\frac{\pi}{2} \text{ rad} = +Y$$
 * - **Polarity Correction**: A normally mounted and configured GoBilda Pinpoint outputs
 *   counter-clockwise-positive heading. [isHeadingCcwPositive] exists only for an unusual physical
 *   installation whose observed heading polarity is reversed.
 *
 * ### Physical Units & Setup:
 * - Pod offsets: $X, Y$ mounting offsets in millimeters ($mm$).
 * - Encoder resolution: Ticks per millimeter ($ticks/mm$). A null value selects the FTC SDK's named
 *   GoBilda 4-Bar pod calibration instead of duplicating a vendor calibration constant.
 * - Velocities: Linear $m/s$, Angular $rad/s$.
 * - Time: Milliseconds ($ms$).
 *
 * ### Zero-GC Compliance:
 * The [getPoseUpdate] loop operates without allocating heap objects during 50Hz–100Hz execution.
 * Pose updates reuse internal primitive accumulators and return immutable state structures.
 *
 * @param driver The physical GoBilda Pinpoint driver instance.
 * @param xOffsetMm X offset of the odometry pod relative to robot center ($mm$).
 * @param yOffsetMm Y offset of the odometry pod relative to robot center ($mm$).
 * @param encoderResolution Encoder resolution ($ticks/mm$). Pass `null` for factory default.
 * @param xDirection Encoder direction for X pod.
 * @param yDirection Encoder direction for Y pod.
 * @param isHeadingCcwPositive `true` for the normal native CCW-positive Pinpoint convention.
 *
 * @see com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
 * @see RobotAction.PoseUpdate
 */
class PinpointIO @kotlin.jvm.JvmOverloads constructor(
    private val driver: GoBildaPinpointDriver,
    xOffsetMm: Double = 0.0,
    yOffsetMm: Double = 0.0,
    encoderResolution: Double? = null,
    xDirection: GoBildaPinpointDriver.EncoderDirection = GoBildaPinpointDriver.EncoderDirection.FORWARD,
    yDirection: GoBildaPinpointDriver.EncoderDirection = GoBildaPinpointDriver.EncoderDirection.FORWARD,
    private val isHeadingCcwPositive: Boolean = true
) : AutoCloseable {
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
        // Allow one delayed loop or a simulation step without false failover. The
        // velocity-scaled term still grows with dt; multi-metre/large-angle one-frame
        // discontinuities remain rejected.
        private const val POSITION_JUMP_TOLERANCE_METERS = 0.75
        private const val HEADING_JUMP_TOLERANCE_RADIANS = 0.75
    }

    private var offsetX = 0.0
    private var offsetY = 0.0
    private var offsetHeading = 0.0

    init {
        setOffsets(xOffsetMm, yOffsetMm)
        if (encoderResolution != null && encoderResolution > 0.0) {
            driver.setEncoderResolution(encoderResolution, DistanceUnit.MM)
        } else {
            driver.setEncoderResolution(GoBildaPinpointDriver.GoBildaOdometryPods.goBILDA_4_BAR_POD)
        }
        driver.setEncoderDirections(xDirection, yDirection)
        driver.resetPosAndIMU()
    }

    private var lastWarningTime = 0L

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

    @Volatile
    var healthStatus: HealthStatus = HealthStatus.STARTING
        private set

    @Volatile
    var consecutiveReadFailures: Int = 0
        private set

    @Volatile
    var lastInitializeSucceeded: Boolean = true
        private set
    
    private val reusablePoseUpdate = RobotAction.PoseUpdate(0.0, 0.0, 0.0, 0L)

    init {
        com.areslib.hardware.HardwareRegistry.registerCloseable(this)
    }

    /**
     * Polls the GoBilda Pinpoint hardware computer and returns the updated pose as an immutable action.
     *
     * Applies coordinate transformations, pod offsets, and CCW-positive polarity corrections.
     * Zero-GC compliance: performs zero heap allocations during the 100Hz hardware sampling cycle.
     *
     * @return [RobotAction.PoseUpdate] containing positions ($m$), CCW+ heading ($rad$), and velocities ($m/s, rad/s$).
     */
    fun getPoseUpdate(): RobotAction.PoseUpdate {
        try {
            driver.update()
            val now = com.areslib.util.RobotClock.currentTimeMillis()
            val rawX = driver.getPosX(DistanceUnit.METER)
            val rawY = driver.getPosY(DistanceUnit.METER)
            val headingMult = if (isHeadingCcwPositive) 1.0 else -1.0
            val rawHeading = headingMult * driver.getHeading(AngleUnit.RADIANS)
            val rawHeadingVelocity = headingMult * driver.getHeadingVelocity(org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit.RADIANS)

            val cosH = kotlin.math.cos(offsetHeading)
            val sinH = kotlin.math.sin(offsetHeading)
            val x = rawX * cosH - rawY * sinH + offsetX
            val y = rawX * sinH + rawY * cosH + offsetY
            val nextHeading = wrapAngle(rawHeading + offsetHeading)
            val rawVelX = driver.getVelX(DistanceUnit.METER)
            val rawVelY = driver.getVelY(DistanceUnit.METER)
            val velX = rawVelX * cosH - rawVelY * sinH
            val velY = rawVelX * sinH + rawVelY * cosH

            val finite = x.isFinite() && y.isFinite() && nextHeading.isFinite() &&
                rawHeadingVelocity.isFinite() && velX.isFinite() && velY.isFinite()
            val velocityPlausible = finite &&
                kotlin.math.hypot(velX, velY) <= MAX_LINEAR_SPEED_METERS_PER_SECOND &&
                kotlin.math.abs(rawHeadingVelocity) <= MAX_ANGULAR_SPEED_RADIANS_PER_SECOND

            var deltaPlausible = true
            if (finite && hasObservationBaseline && now > observationTimestampMs) {
                val dtSeconds = (now - observationTimestampMs) / 1000.0
                val maxDistance = MAX_LINEAR_SPEED_METERS_PER_SECOND * dtSeconds + POSITION_JUMP_TOLERANCE_METERS
                val maxHeading = MAX_ANGULAR_SPEED_RADIANS_PER_SECOND * dtSeconds + HEADING_JUMP_TOLERANCE_RADIANS
                deltaPlausible = kotlin.math.hypot(x - observationX, y - observationY) <= maxDistance &&
                    kotlin.math.abs(wrapAngle(nextHeading - observationHeading)) <= maxHeading
            }

            // Always advance the observation baseline for finite packets. After a reconnect,
            // this lets several mutually-consistent samples establish health without ever
            // publishing the potentially discontinuous first packet.
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
        } catch (e: Exception) {
            markReadFailure(HealthStatus.COMMUNICATION_FAILURE)
            val now = com.areslib.util.RobotClock.currentTimeMillis()
            if (now - lastWarningTime > 2000L) {
                System.err.println("PinpointIO: Communication failure with GoBildaPinpointDriver. Using last known coordinates. Error: ${e.message}")
                lastWarningTime = now
            }
        }

        var ts = lastTimestampMs
        if (ts == 0L) ts = com.areslib.util.RobotClock.currentTimeMillis()

        reusablePoseUpdate.xMeters = lastX
        reusablePoseUpdate.yMeters = lastY
        reusablePoseUpdate.headingRadians = lastHeading
        reusablePoseUpdate.angularVelocityRadiansPerSecond = lastHeadingVelocity
        reusablePoseUpdate.timestampMs = ts
        reusablePoseUpdate.xVelocityMetersPerSecond = lastVelX
        reusablePoseUpdate.yVelocityMetersPerSecond = lastVelY
        reusablePoseUpdate.isReset = false

        return reusablePoseUpdate
    }

    /** Returns true only when the latest hardware transaction was valid and recent. */
    @JvmOverloads
    fun isHealthy(
        nowMs: Long = com.areslib.util.RobotClock.currentTimeMillis(),
        maxSampleAgeMs: Long = DEFAULT_MAX_SAMPLE_AGE_MS
    ): Boolean {
        if (healthStatus != HealthStatus.HEALTHY || !hasTrustedSample) return false
        val ageMs = nowMs - lastTimestampMs
        if (ageMs < 0L || ageMs > maxSampleAgeMs) {
            healthStatus = HealthStatus.STALE
            return false
        }
        return true
    }

    private fun markReadFailure(status: HealthStatus) {
        consecutiveReadFailures++
        healthStatus = status
    }

    /**
     * Recalibrates the internal Pinpoint IMU while the robot is stationary.
     */
    fun recalibrateIMU() {
        try {
            driver.recalibrateIMU()
        } catch (_: Exception) {}
    }

    /**
     * Resets the Pinpoint tracking computer pose and optionally re-zeros physical IMU hardware registers.
     *
     * @param pose The field starting pose $(x, y, \theta)$ in meters ($m$) and CCW-positive radians ($rad$).
     * @param resetHardware If `true`, triggers a physical IMU and encoder reset on the Pinpoint board.
     */
    @kotlin.jvm.JvmOverloads
    fun initialize(pose: com.areslib.math.geometry.Pose2d = com.areslib.math.geometry.Pose2d(), resetHardware: Boolean = false) {
        try {
            if (resetHardware) {
                driver.resetPosAndIMU()
                offsetX = pose.x
                offsetY = pose.y
                offsetHeading = pose.heading.radians
                lastX = pose.x
                lastY = pose.y
                lastHeading = pose.heading.radians
                lastHeadingVelocity = 0.0
                lastTimestampMs = com.areslib.util.RobotClock.currentTimeMillis()
            } else {
                driver.update()
                val rawX = driver.getPosX(DistanceUnit.METER)
                val rawY = driver.getPosY(DistanceUnit.METER)
                val headingMult = if (isHeadingCcwPositive) 1.0 else -1.0
                val rawHeading = headingMult * driver.getHeading(AngleUnit.RADIANS)

                offsetHeading = wrapAngle(pose.heading.radians - rawHeading)
                val cosH = kotlin.math.cos(offsetHeading)
                val sinH = kotlin.math.sin(offsetHeading)
                
                offsetX = pose.x - (rawX * cosH - rawY * sinH)
                offsetY = pose.y - (rawX * sinH + rawY * cosH)
                
                lastX = pose.x
                lastY = pose.y
                lastHeading = pose.heading.radians
            }
            hasObservationBaseline = false
            consecutiveReadFailures = 0
            healthStatus = HealthStatus.STARTING
            lastInitializeSucceeded = true
        } catch (_: Exception) {
            lastInitializeSucceeded = false
            markReadFailure(HealthStatus.COMMUNICATION_FAILURE)
        }
    }

    /**
     * Configures the physical mounting offsets of the dead-wheel pods relative to the robot rotational center.
     *
     * @param xOffsetMm X offset (forward/backward distance from tracking center) in millimeters ($mm$).
     * @param yOffsetMm Y offset (left/right distance from tracking center) in millimeters ($mm$).
     */
    fun setOffsets(xOffsetMm: Double, yOffsetMm: Double) {
        try {
            // GoBilda Pinpoint setOffsets expects:
            // 1st arg: X-pod offset (sideways distance from tracking center, i.e., yOffsetMm)
            // 2nd arg: Y-pod offset (forward distance from tracking center, i.e., xOffsetMm)
            driver.setOffsets(yOffsetMm, xOffsetMm, DistanceUnit.MM)
        } catch (_: Exception) {}
    }

    /**
     * Sets the physical encoder resolution for the dead-wheel tracking pods.
     *
     * @param resolution Encoder resolution in ticks per millimeter ($ticks/mm$).
     */
    fun setEncoderResolution(resolution: Double) {
        try {
            if (resolution > 0.0) {
                driver.setEncoderResolution(resolution, DistanceUnit.MM)
            }
        } catch (_: Exception) {}
    }

    /**
     * Unregisters hardware resources.
     */
    override fun close() {
    }
}

