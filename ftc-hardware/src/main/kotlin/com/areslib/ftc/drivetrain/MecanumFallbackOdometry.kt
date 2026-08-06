package com.areslib.ftc.drivetrain

import com.areslib.action.RobotAction

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
        headingRadians: Double
    ): RobotAction.PoseUpdate {
        val ticks = if (ticksPerMeterSetting > 0.0) ticksPerMeterSetting else defaultTicksPerMeter

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

            return RobotAction.PoseUpdate(
                xMeters = 0.0,
                yMeters = 0.0,
                headingRadians = headingRadians,
                timestampMs = timestampMs
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

        // Field-centric rotation transform
        val cos = kotlin.math.cos(headingRadians)
        val sin = kotlin.math.sin(headingRadians)

        val deltaFieldX = dx * cos - dy * sin
        val deltaFieldY = dx * sin + dy * cos

        fallbackX += deltaFieldX
        fallbackY += deltaFieldY

        return RobotAction.PoseUpdate(
            xMeters = fallbackX,
            yMeters = fallbackY,
            headingRadians = headingRadians,
            timestampMs = timestampMs
        )
    }

    /**
     * Resets fallback pose accumulators to field origin $(0.0, 0.0)$.
     */
    fun reset() {
        fallbackX = 0.0
        fallbackY = 0.0
        lastFlPos = 0.0
        lastFrPos = 0.0
        lastRlPos = 0.0
        lastRrPos = 0.0
        isFallbackInitialized = false
    }
}

