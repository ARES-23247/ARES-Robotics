package com.areslib.xrp.hardware

/**
 * Hardware IO abstraction for an individual XRP DC motor channel. Effort is normalized to
 * [-1, 1]; feedback getters expose cached radians/radians per second without device reads.
 */
interface XrpMotorIO {
    val channel: Int
    var effort: Double
    val positionRadians: Double
    val velocityRadiansPerSecond: Double

    fun update()
    fun stop()
}

/**
 * Deterministic in-memory fixture: each [update] advances one 20 ms step at an ideal
 * 30 rad/s per unit effort. It does not model elapsed wall time, inertia or electrical response.
 * Finite commands clamp to [-1, 1]; nonfinite commands neutralize. Feedback remains writable
 * for fault injection, including unknown/nonfinite values. Each update recomputes ideal
 * velocity; an injected unknown position is not silently repaired by update or stop.
 */
open class XrpMotorDouble(override val channel: Int) : XrpMotorIO {
    override var effort: Double = 0.0
        set(value) { field = if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0 }
    override var positionRadians: Double = 0.0
    override var velocityRadiansPerSecond: Double = 0.0

    override fun update() {
        // Simulated ideal velocity response
        val maxRadPerSec = 30.0
        velocityRadiansPerSecond = effort * maxRadPerSec
        positionRadians += velocityRadiansPerSecond * 0.02
    }

    override fun stop() {
        effort = 0.0
        velocityRadiansPerSecond = 0.0
    }
}
