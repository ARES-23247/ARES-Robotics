package com.areslib.xrp.hardware

/**
 * IO contract for the XRP HC-SR04 ultrasonic distance sensor.
 */
interface XrpUltrasonicIO {
    val distanceMeters: Double
    fun update()
}

open class XrpUltrasonicDouble(override var distanceMeters: Double = 1.0) : XrpUltrasonicIO {
    override fun update() {}
}

/**
 * IO contract for the 2-channel line tracking reflectance sensor.
 */
interface XrpLineSensorIO {
    val leftReflectance: Double
    val rightReflectance: Double
    val isLeftOnLine: Boolean get() = leftReflectance > 0.5
    val isRightOnLine: Boolean get() = rightReflectance > 0.5

    fun update()
}

open class XrpLineSensorDouble(
    override var leftReflectance: Double = 0.0,
    override var rightReflectance: Double = 0.0
) : XrpLineSensorIO {
    override fun update() {}
}

/**
 * IO contract for XRP servo outputs (Servos 1-4).
 */
interface XrpServoIO {
    val channel: Int
    var positionNormalized: Double // [0.0, 1.0]

    fun update()
}

open class XrpServoDouble(override val channel: Int) : XrpServoIO {
    override var positionNormalized: Double = 0.5
    override fun update() {}
}
