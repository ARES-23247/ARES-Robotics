package com.areslib.hardware.actuator

import com.areslib.hardware.SubsystemIO

/**
 * Pre-configured PWM pulse width presets (in microseconds) for the goBILDA Prism RGB LED Driver (SKU 3118-2855-0001).
 * Standard Servo PWM range is 500µs to 2500µs.
 */
enum class PrismPwmPreset(val pulseWidthUs: Int) {
    ARTBOARD_0(505),
    ARTBOARD_1(515),
    ARTBOARD_2(525),
    ARTBOARD_3(535),
    ARTBOARD_4(545),
    ARTBOARD_5(555),
    ARTBOARD_6(565),
    ARTBOARD_7(575),
    EMERGENCY_LIGHTS(585),
    AURORA_BOREALIS(595),
    BLINK_COUNTER(605),
    FTC_TIMER(615),
    SPARKLE(650),
    SINE_WAVE(800),
    RAINBOW_RED_GREEN(955),
    RAINBOW_PURPLE_BLUE(965),
    RAINBOW_BLUES(975),
    RAINBOW_GREENS(985),
    RAINBOW_REDS(995),
    RAINBOW_FULL_COLOR(1005),
    RAINBOW_PARTY(1015),
    RAINBOW_OCEAN(1025),
    RAINBOW_LAVA(1035),
    RAINBOW_FOREST(1045),
    SOLID_RED(1050),
    SOLID_YELLOW(1200),
    SOLID_GREEN(1350),
    SOLID_CYAN(1500),
    SOLID_BLUE(1650),
    SOLID_PURPLE(1800),
    SOLID_WHITE(1940),
    SNAKES_DEFAULT(2205),
    RAINBOW_SNAKES(2355);

    /**
     * Normalized servo position (0.0 to 1.0) for standard FTC servo interface (500µs - 2500µs range).
     */
    val normalizedPosition: Double get() = (pulseWidthUs - 500.0) / 2000.0
}

/**
 * Hardware IO interface for the goBILDA Prism RGB LED Driver (SKU 3118-2855-0001).
 * Supports both PWM (Servo pulse width 500–2500µs) and I²C (Address 0x38) modes.
 */
interface PrismDriverIO : SubsystemIO {
    /** Current commanded pulse width in microseconds (500 to 2500). */
    val currentPulseWidthUs: Int

    /** Sets the Prism driver to a raw pulse width in microseconds (500–2500µs). */
    fun setPulseWidthUs(pulseWidthUs: Int)

    /** Sets the Prism driver using normalized servo position (0.0 to 1.0). */
    fun setPosition(position: Double) {
        val clamped = position.coerceIn(0.0, 1.0)
        setPulseWidthUs((500 + clamped * 2000).toInt())
    }

    /** Sets the Prism driver to a predefined PWM preset. */
    fun setPreset(preset: PrismPwmPreset) = setPulseWidthUs(preset.pulseWidthUs)

    /** Sets solid color by RGB (0-255). */
    fun setSolidColorRgb(r: Int, g: Int, b: Int)
}
