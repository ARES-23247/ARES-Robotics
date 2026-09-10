package com.areslib.xrp.hardware

/**
 * Cached distance in meters. [update] owns device reads; unknown values remain observable.
 */
interface XrpUltrasonicIO {
    val distanceMeters: Double
    fun update()
}

/** Mutable input fixture; update preserves the injected reading, including invalid values. */
open class XrpUltrasonicDouble(override var distanceMeters: Double = 1.0) : XrpUltrasonicIO {
    override fun update() {}
}

/**
 * Cached normalized reflectance: 0 is white and 1 is black. The strict >0.5 threshold only
 * asserts a line for valid readings. False does not distinguish unknown from known off-line;
 * controllers must validate the raw cached value before using it for motion decisions.
 */
interface XrpLineSensorIO {
    val leftReflectance: Double
    val rightReflectance: Double
    val isLeftOnLine: Boolean get() {
        val value = leftReflectance
        return value > 0.5 && value <= 1.0
    }
    val isRightOnLine: Boolean get() {
        val value = rightReflectance
        return value > 0.5 && value <= 1.0
    }

    fun update()
}

/** Mutable input fixture; no-op refresh preserves independently injected sensor values. */
open class XrpLineSensorDouble(
    override var leftReflectance: Double = 0.0,
    override var rightReflectance: Double = 0.0
) : XrpLineSensorIO {
    override fun update() {}
}

/**
 * IO contract for XRP servo outputs on ports declared by the canonical controller model.
 */
interface XrpServoIO {
    val channel: Int
    var positionNormalized: Double // [0.0, 1.0]

    fun update()
}

/**
 * In-memory command fixture. Finite positions clamp to [0, 1]; nonfinite commands throw before
 * changing the previous command. Servos have no universal neutral angle: this is not a PWM-off
 * or physical stop operation. Channel identity is validated by the owning hardware configuration.
 */
open class XrpServoDouble(override val channel: Int) : XrpServoIO {
    override var positionNormalized: Double = 0.5
        set(value) {
            require(value.isFinite()) { "Servo position must be finite" }
            field = value.coerceIn(0.0, 1.0)
        }
    override fun update() {}
}
