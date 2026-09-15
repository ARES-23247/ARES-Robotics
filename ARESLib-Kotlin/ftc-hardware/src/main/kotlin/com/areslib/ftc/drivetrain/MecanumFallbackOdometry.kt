package com.areslib.ftc.drivetrain

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.wrapAngle

/**
 * Fallback dead-reckoning pose estimator using Mecanum wheel encoder forward kinematics.
 *
 * Provides backup localization when primary hardware (e.g. GoBilda Pinpoint odometry computer) is unavailable or offline.
 *
 * ### Mathematical Formulation (Forward Kinematics):
 * Given wheel encoder displacement deltas $[\Delta s_{FL}, \Delta s_{FR}, \Delta s_{RL}, \Delta s_{RR}]^T$ in meters ($m$):
 * $$\Delta x_{robot} = \frac{\Delta s_{FL} + \Delta s_{FR} + \Delta s_{RL} + \Delta s_{RR}}{4}$$
 * $$\Delta y_{robot} = \frac{-\Delta s_{FL} + \Delta s_{FR} + \Delta s_{RL} - \Delta s_{RR}}{4}$$
 * Rotated into field coordinates using the midpoint heading, then scaled by
 * sinc(half the wrapped heading change) to integrate the SE(2) arc:
 * $$\Delta x_{field} = \Delta x_{robot} \cos(\theta) - \Delta y_{robot} \sin(\theta)$$
 * $$\Delta y_{field} = \Delta x_{robot} \sin(\theta) + \Delta y_{robot} \cos(\theta)$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position: Meters ($m$).
 * - Heading: Radians ($rad$), **CCW-positive** standard ($0 = +X$, $\pi/2 = +Y$).
 * - Encoders: Cumulative ticks ($ticks$) converted using configured $ticks/m$.
 * - Samples require continuous encoder positions and less than half a turn between headings.
 *   Explicitly reset after an encoder reset/rollover or a localization epoch change.
 *
 * ### Allocation behavior:
 * Integration uses primitive fields and locals. Each call returns a new, independently owned
 * PoseUpdate action; callers may retain or enrich it without corrupting later odometry samples.
 *
 * @see RobotAction.PoseUpdate
 */
class MecanumFallbackOdometry {
    private var fallbackX = 0.0
    private var fallbackY = 0.0
    private var lastFlTicks = 0.0
    private var lastFrTicks = 0.0
    private var lastRlTicks = 0.0
    private var lastRrTicks = 0.0
    private var isFallbackInitialized = false
    private var headingOffsetRadians = 0.0
    private var lastAlignedHeadingRadians = 0.0
    private var lastTimestampMs = 0L

    /**
     * Computes field-centric pose updates from drive wheel encoder tick counts.
     *
     * Invalid encoder/heading data, an unusable selected scale, clock rewind/elapsed overflow,
     * or unrepresentable pose/motion is rejected before changing the baseline. The FTC robot's
     * existing fatal-loop handler neutralizes on these failures. Reset explicitly starts a new epoch.
     * Duplicate timestamps return the last pose with unavailable motion, without consuming inputs.
     *
     * @param timestampMs RobotClock timestamp in milliseconds ($ms$).
     * @param flPosTicks Front-left cumulative encoder ticks.
     * @param frPosTicks Front-right cumulative encoder ticks.
     * @param rlPosTicks Rear-left cumulative encoder ticks.
     * @param rrPosTicks Rear-right cumulative encoder ticks.
     * @param ticksPerMeterSetting Positive finite resolution applied to this interval's raw tick delta.
     * @param defaultTicksPerMeter Positive finite fallback used when the primary scale is unusable.
     * @param headingRadians Current gyro/IMU heading angle in CCW-positive radians ($rad$).
     * @return Formatted [RobotAction.PoseUpdate] containing calculated field positions.
     */
    fun getFallbackPoseUpdate(
        timestampMs: Long,
        flPosTicks: Double,
        frPosTicks: Double,
        rlPosTicks: Double,
        rrPosTicks: Double,
        ticksPerMeterSetting: Double,
        defaultTicksPerMeter: Double,
        headingRadians: Double,
        angularVelocityRadiansPerSecond: Double = 0.0
    ): RobotAction.PoseUpdate {
        require(flPosTicks.isFinite() && frPosTicks.isFinite() &&
            rlPosTicks.isFinite() && rrPosTicks.isFinite()) { "Encoder positions must be finite" }
        require(headingRadians.isFinite()) { "Raw heading must be finite" }
        val ticks = if (ticksPerMeterSetting.isFinite() && ticksPerMeterSetting > 0.0) {
            ticksPerMeterSetting
        } else defaultTicksPerMeter
        require(ticks.isFinite() && ticks > 0.0) { "Encoder resolution must be positive and finite" }
        val elapsedMs = if (isFallbackInitialized) timestampMs - lastTimestampMs else 0L
        require(!isFallbackInitialized || (timestampMs >= lastTimestampMs && elapsedMs >= 0L)) {
            "Odometry clock rewound or elapsed time overflowed; reset before changing epochs"
        }
        if (isFallbackInitialized && elapsedMs == 0L) {
            return RobotAction.PoseUpdate(
                xMeters = fallbackX,
                yMeters = fallbackY,
                headingRadians = lastAlignedHeadingRadians,
                timestampMs = timestampMs,
                motionMeasurementsValid = false
            )
        }

        // Normalize before adding the offset so a large raw heading cannot swallow that offset.
        val alignedHeading = wrapAngle(wrapAngle(headingRadians) + headingOffsetRadians)
        var nextX = fallbackX
        var nextY = fallbackY
        var fieldVelocityX = 0.0
        var fieldVelocityY = 0.0
        if (isFallbackInitialized) {
            val dFl = displacement(flPosTicks, lastFlTicks, ticks)
            val dFr = displacement(frPosTicks, lastFrTicks, ticks)
            val dRl = displacement(rlPosTicks, lastRlTicks, ticks)
            val dRr = displacement(rrPosTicks, lastRrTicks, ticks)
            require(dFl.isFinite() && dFr.isFinite() && dRl.isFinite() && dRr.isFinite()) {
                "Encoder displacement is not representable"
            }
            // Scale each term before summing, avoiding an overflowing intermediate wheel sum.
            val dx = dFl * 0.25 + dFr * 0.25 + dRl * 0.25 + dRr * 0.25
            val dy = -dFl * 0.25 + dFr * 0.25 + dRl * 0.25 - dRr * 0.25
            val deltaHeading = wrapAngle(alignedHeading - lastAlignedHeadingRadians)
            val halfHeading = deltaHeading * 0.5
            val chordScale = if (kotlin.math.abs(halfHeading) < 1e-6) {
                1.0 - halfHeading * halfHeading / 6.0
            } else kotlin.math.sin(halfHeading) / halfHeading
            val midpointHeading = lastAlignedHeadingRadians + halfHeading
            val cos = kotlin.math.cos(midpointHeading) * chordScale
            val sin = kotlin.math.sin(midpointHeading) * chordScale
            val deltaFieldX = dx * cos - dy * sin
            val deltaFieldY = dx * sin + dy * cos
            nextX += deltaFieldX
            nextY += deltaFieldY
            val dtSeconds = elapsedMs / 1000.0
            fieldVelocityX = deltaFieldX / dtSeconds
            fieldVelocityY = deltaFieldY / dtSeconds
            require(nextX.isFinite() && nextY.isFinite() &&
                fieldVelocityX.isFinite() && fieldVelocityY.isFinite()) {
                "Odometry pose or velocity is not representable"
            }
        }
        val motionValid = angularVelocityRadiansPerSecond.isFinite()
        val result = RobotAction.PoseUpdate(
            xMeters = nextX,
            yMeters = nextY,
            headingRadians = alignedHeading,
            timestampMs = timestampMs,
            angularVelocityRadiansPerSecond = if (motionValid) angularVelocityRadiansPerSecond else 0.0,
            xVelocityMetersPerSecond = fieldVelocityX,
            yVelocityMetersPerSecond = fieldVelocityY,
            motionMeasurementsValid = motionValid
        )
        fallbackX = nextX
        fallbackY = nextY
        lastFlTicks = flPosTicks
        lastFrTicks = frPosTicks
        lastRlTicks = rlPosTicks
        lastRrTicks = rrPosTicks
        lastAlignedHeadingRadians = alignedHeading
        lastTimestampMs = timestampMs
        isFallbackInitialized = true
        return result
    }

    private fun displacement(current: Double, previous: Double, ticksPerMeter: Double): Double {
        val delta = current - previous
        // Opposite finite extremes can overflow subtraction even when the scaled delta is finite.
        return if (delta.isFinite()) delta / ticksPerMeter else current / ticksPerMeter - previous / ticksPerMeter
    }

    /**
     * Re-bases drivetrain odometry at the current fused field pose. The raw IMU heading
     * is retained through a software offset so switching away from Pinpoint is continuous.
     * Nonfinite pose components or raw IMU heading are rejected without changing current state.
     */
    @JvmOverloads
    fun reset(pose: Pose2d = Pose2d(), rawHeadingRadians: Double = pose.heading.radians) {
        require(pose.x.isFinite() && pose.y.isFinite() &&
            pose.heading.rawRadians.isFinite() && rawHeadingRadians.isFinite()) {
            "Reset pose and raw heading must be finite"
        }
        val heading = pose.heading.radians
        val offset = wrapAngle(heading - wrapAngle(rawHeadingRadians))
        fallbackX = pose.x
        fallbackY = pose.y
        headingOffsetRadians = offset
        lastAlignedHeadingRadians = heading
        lastTimestampMs = 0L
        lastFlTicks = 0.0
        lastFrTicks = 0.0
        lastRlTicks = 0.0
        lastRrTicks = 0.0
        isFallbackInitialized = false
    }
}

