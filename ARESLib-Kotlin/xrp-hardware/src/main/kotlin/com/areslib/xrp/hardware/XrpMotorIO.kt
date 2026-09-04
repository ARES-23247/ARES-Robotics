package com.areslib.xrp.hardware

/**
 * Hardware IO abstraction for an individual XRP DC motor channel.
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
 * Standard in-memory / simulated double for an XRP motor.
 */
open class XrpMotorDouble(override val channel: Int) : XrpMotorIO {
    override var effort: Double = 0.0
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
