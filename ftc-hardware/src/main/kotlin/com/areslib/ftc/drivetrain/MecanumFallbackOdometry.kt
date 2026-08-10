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
 * Transformed into field-centric coordinates using heading angle $\theta$ (radians):
 * $$\Delta x_{field} = \Delta x_{robot} \cos(\theta) - \Delta y_{robot} \sin(\theta)$$
 * $$\Delta y_{field} = \Delta x_{robot} \sin(\theta) + \Delta y_{robot} \cos(\theta)$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position: Meters ($m$).
 * - Heading: Radians ($rad$), **CCW-positive** standard ($0 = +X$, $\pi/2 = +Y$).
 * - Encoders: Cumulative ticks ($ticks$) converted using configured $ticks/m$.
 *
 * ### Zero-GC Compliance:
 * Computes forward kinematic displacement in-place without dynamic object allocations during loop updates.
 *
 * @see RobotAction.PoseUpdate
 */
class MecanumFallbackOdometry {
    private var fallbackX = 0.0
    private var fallbackY = 0.0
    private var lastFlPos = 0.0
    private var lastFrPos = 0.0
    private var lastRlPos = 0.0
    private var lastRrPos = 0.0
    private var isFallbackInitialized = false
    private var headingOffsetRadians = 0.0
    private var lastAlignedHeadingRadians = 0.0
    private var lastTimestampMs = 0L

    /**
     * Computes field-centric pose updates from drive wheel encoder tick counts.
     *
     * @param timestampMs System clock timestamp in milliseconds ($ms$).
     * @param flPosTicks Front-left cumulative encoder ticks.
     * @param frPosTicks Front-right cumulative encoder ticks.
     * @param rlPosTicks Rear-left cumulative encoder ticks.
     * @param rrPosTicks Rear-right cumulative encoder ticks.
     * @param ticksPerMeterSetting Primary encoder resolution setting ($ticks/m$).
     * @param defaultTicksPerMeter Default fallback resolution ($ticks/m$).
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
        val ticks = if (ticksPerMeterSetting > 0.0) ticksPerMeterSetting else defaultTicksPerMeter
        val alignedHeading = wrapAngle(headingRadians + headingOffsetRadians)

        val flMeters = flPosTicks / ticks
        val frMeters = frPosTicks / ticks
        val rlMeters = rlPosTicks / ticks
        val rrMeters = rrPosTicks / ticks

        if (!isFallbackInitialized) {
            lastFlPos = flMeters
            lastFrPos = frMeters
            lastRlPos = rlMeters
            lastRrPos = rrMeters
            isFallbackInitialized = true
            lastAlignedHeadingRadians = alignedHeading
            lastTimestampMs = timestampMs

            return RobotAction.PoseUpdate(
                xMeters = fallbackX,
                yMeters = fallbackY,
                headingRadians = alignedHeading,
                timestampMs = timestampMs,
                angularVelocityRadiansPerSecond = angularVelocityRadiansPerSecond
            )
        }

        val dFl = flMeters - lastFlPos
        val dFr = frMeters - lastFrPos
        val dRl = rlMeters - lastRlPos
        val dRr = rrMeters - lastRrPos

        lastFlPos = flMeters
        lastFrPos = frMeters
        lastRlPos = rlMeters
        lastRrPos = rrMeters

        // Mecanum forward kinematics: robot-centric dx and dy
        val dx = (dFl + dFr + dRl + dRr) / 4.0
        val dy = (-dFl + dFr + dRl - dRr) / 4.0

        // Rotate the interval displacement at its midpoint heading. This is materially
        // more accurate than using only the end heading during simultaneous translation
        // and rotation, while remaining allocation-free.
        val deltaHeading = wrapAngle(alignedHeading - lastAlignedHeadingRadians)
        val midpointHeading = wrapAngle(lastAlignedHeadingRadians + deltaHeading * 0.5)
        val cos = kotlin.math.cos(midpointHeading)
        val sin = kotlin.math.sin(midpointHeading)

        val deltaFieldX = dx * cos - dy * sin
        val deltaFieldY = dx * sin + dy * cos

        fallbackX += deltaFieldX
        fallbackY += deltaFieldY
        val dtSeconds = if (timestampMs > lastTimestampMs) (timestampMs - lastTimestampMs) / 1000.0 else 0.0
        val fieldVelocityX = if (dtSeconds > 0.0) deltaFieldX / dtSeconds else 0.0
        val fieldVelocityY = if (dtSeconds > 0.0) deltaFieldY / dtSeconds else 0.0
        lastAlignedHeadingRadians = alignedHeading
        lastTimestampMs = timestampMs

        return RobotAction.PoseUpdate(
            xMeters = fallbackX,
            yMeters = fallbackY,
            headingRadians = alignedHeading,
            timestampMs = timestampMs,
            angularVelocityRadiansPerSecond = angularVelocityRadiansPerSecond,
            xVelocityMetersPerSecond = fieldVelocityX,
            yVelocityMetersPerSecond = fieldVelocityY
        )
    }

    /**
     * Re-bases drivetrain odometry at the current fused field pose. The raw IMU heading
     * is retained through a software offset so switching away from Pinpoint is continuous.
     */
    @JvmOverloads
    fun reset(pose: Pose2d = Pose2d(), rawHeadingRadians: Double = pose.heading.radians) {
        fallbackX = pose.x
        fallbackY = pose.y
        headingOffsetRadians = wrapAngle(pose.heading.radians - rawHeadingRadians)
        lastAlignedHeadingRadians = pose.heading.radians
        lastTimestampMs = 0L
        lastFlPos = 0.0
        lastFrPos = 0.0
        lastRlPos = 0.0
        lastRrPos = 0.0
        isFallbackInitialized = false
    }
}

