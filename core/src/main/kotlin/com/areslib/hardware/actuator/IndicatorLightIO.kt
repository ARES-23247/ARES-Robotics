package com.areslib.hardware.actuator

import com.areslib.hardware.SubsystemIO

/**
 * Approximate servo positions for the GoBilda RGB Indicator Light (3118-0808-0002).
 * These map the standard 0.0–1.0 servo range to the light's color gradient.
 * Tune these values on real hardware using the GoBilda Product Insight #4 chart.
 */
enum class IndicatorLightColor(val position: Double) {
    OFF(0.0),
    RED(0.279),
    ORANGE(0.333),
    YELLOW(0.388),
    GREEN(0.472),
    CYAN(0.511),
    BLUE(0.611),
    PURPLE(0.722),
    VIOLET(0.722),
    WHITE(0.833),
    RAINBOW(-1.0);
}

/**
 * IO interface for a single GoBilda RGB Indicator Light (PWM-controlled).
 * The light is connected to a standard servo port and its color is set
 * by writing a position value (0.0 to 1.0) corresponding to the color gradient.
 */
interface IndicatorLightIO : SubsystemIO {
    /** Current servo position being commanded (0.0 to 1.0). */
    val currentPosition: Double

    /** Set the light to a specific servo position along the color gradient. */
    fun setPosition(position: Double)

    /** Set the light to a predefined color. */
    fun setColor(color: IndicatorLightColor) = setPosition(color.position)
}
