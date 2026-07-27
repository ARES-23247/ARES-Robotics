package com.areslib.hardware.actuator

import com.areslib.hardware.SubsystemIO

/**
 * Complete PWM pulse width preset mapping (in microseconds) for the goBILDA Prism RGB LED Driver (SKU 3118-2855-0001)
 * as documented in Product Insight #4. Standard Servo PWM range is 500µs to 2500µs.
 */
enum class PrismPwmPreset(val pulseWidthUs: Int) {
    // Custom Artboard Animations (Saved in EPROM Slots 0-7)
    ARTBOARD_SLOT_0(505),
    ARTBOARD_SLOT_1(515),
    ARTBOARD_SLOT_2(525),
    ARTBOARD_SLOT_3(535),
    ARTBOARD_SLOT_4(545),
    ARTBOARD_SLOT_5(555),
    ARTBOARD_SLOT_6(565),
    ARTBOARD_SLOT_7(575),

    // Fixed Animations
    EMERGENCY_LIGHTS(585),
    AURORA_BOREALIS(595),
    BLINK_COUNTER(605),
    FTC_TIMER(615),

    // Sparkle (620 - 699µs)
    SPARKLE(650),

    // Sine Wave (700 - 949µs)
    SINE_WAVE_RED(710),
    SINE_WAVE_YELLOW(770),
    SINE_WAVE_GREEN(830),
    SINE_WAVE_BLUE(890),
    SINE_WAVE_PURPLE(940),

    // Rainbow Variants (950 - 1049µs)
    RAINBOW_RED_GREEN(955),
    RAINBOW_PURPLE_BLUE(965),
    RAINBOW_BLUES(975),
    RAINBOW_GREENS(985),
    RAINBOW_REDS(995),
    RAINBOW_FULL_COLOR(1005),
    RAINBOW_PARTY(1015),
    RAINBOW_OCEAN(1025),
    RAINBOW_FIRE_LAVA(1035),
    RAINBOW_FOREST(1045),

    // Solid Colors (1050 - 1949µs)
    SOLID_OFF(1055),
    SOLID_RED(1100),
    SOLID_ORANGE(1220),
    SOLID_YELLOW(1350),
    SOLID_GREEN(1480),
    SOLID_CYAN(1600),
    SOLID_BLUE(1720),
    SOLID_PURPLE(1850),
    SOLID_WHITE(1920),

    // Pulse Animations (1950 - 2199µs)
    PULSE_RED(1960),
    PULSE_YELLOW(2040),
    PULSE_GREEN(2100),
    PULSE_BLUE(2160),

    // Snakes 1 - 15 (2200 - 2349µs)
    SNAKE_1_RED_WHITE_BLUE(2205),
    SNAKE_2_RED_GREEN_BLUE(2215),
    SNAKE_3_LIME_GREEN(2225),
    SNAKE_4_BLACK_YELLOW(2235),
    SNAKE_5_RED_WHITE(2245),
    SNAKE_6_PASTEL_PURPLE(2255),
    SNAKE_7_RAINBOW_SPECTRUM(2265),
    SNAKE_8_DARK_RED_ORANGE(2275),
    SNAKE_9_WHITE_NAVY(2285),
    SNAKE_10_RED_TRANSPARENT(2295),
    SNAKE_11_GREEN_TRANSPARENT(2305),
    SNAKE_12_RED_BLUE_WHITE(2315),
    SNAKE_13_BLUE_MAGENTA(2325),
    SNAKE_14_WHITE_PURPLE_BLUE(2335),
    SNAKE_15_WHITE_TRANSPARENT(2345),

    // Rainbow Snakes 1 - 15 (2350 - 2500µs)
    RAINBOW_SNAKE_1_SINGLE(2355),
    RAINBOW_SNAKE_2_DUAL(2365),
    RAINBOW_SNAKE_3_TRIPLE(2375),
    RAINBOW_SNAKE_4_HUE_170_235(2385),
    RAINBOW_SNAKE_5_HUE_0_40(2395),
    RAINBOW_SNAKE_6_HUE_100_160(2405),
    RAINBOW_SNAKE_7_HUE_0_180(2415),
    RAINBOW_SNAKE_8_HUE_180_360(2425),
    RAINBOW_SNAKE_9_INV_SINGLE(2435),
    RAINBOW_SNAKE_10_INV_DUAL(2445),
    RAINBOW_SNAKE_11_INV_TRIPLE(2455),
    RAINBOW_SNAKE_12_INV_HUE_170_235(2465),
    RAINBOW_SNAKE_13_INV_HUE_0_40(2475),
    RAINBOW_SNAKE_14_INV_HUE_100_160(2485),
    RAINBOW_SNAKE_15_INV_HUE_0_180(2495);

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

    /** Maximum allowed brightness cap (0 to 100%). Default is 75% to conserve robot battery power. */
    var maxBrightnessPercent: Int

    /** Sets the Prism driver to a raw pulse width in microseconds (500–2500µs). */
    fun setPulseWidthUs(pulseWidthUs: Int)

    /** Sets the Prism driver using normalized servo position (0.0 to 1.0). */
    fun setPosition(position: Double) {
        val clamped = position.coerceIn(0.0, 1.0)
        setPulseWidthUs((500 + clamped * 2000).toInt())
    }

    /** Sets the Prism driver to a predefined PWM preset. */
    fun setPreset(preset: PrismPwmPreset) = setPulseWidthUs(preset.pulseWidthUs)

    /** Continuously adjusts Sine Wave hue (hueRatio 0.0 to 1.0 maps to 700µs - 949µs). */
    fun setSineWaveHue(hueRatio: Double) {
        val clamped = hueRatio.coerceIn(0.0, 1.0)
        setPulseWidthUs((700 + clamped * 249.0).toInt())
    }

    /** Continuously adjusts Sparkle hue (hueRatio 0.0 to 1.0 maps to 620µs - 699µs). */
    fun setSparkleHue(hueRatio: Double) {
        val clamped = hueRatio.coerceIn(0.0, 1.0)
        setPulseWidthUs((620 + clamped * 79.0).toInt())
    }

    /** Continuously adjusts Pulse hue (hueRatio 0.0 to 1.0 maps to 1950µs - 2199µs). */
    fun setPulseHue(hueRatio: Double) {
        val clamped = hueRatio.coerceIn(0.0, 1.0)
        setPulseWidthUs((1950 + clamped * 249.0).toInt())
    }

    /** Continuously adjusts Solid Color hue (hueRatio 0.0 to 1.0 maps to 1100µs - 1899µs). */
    fun setSolidColorHue(hueRatio: Double) {
        val clamped = hueRatio.coerceIn(0.0, 1.0)
        setPulseWidthUs((1100 + clamped * 799.0).toInt())
    }

    /** Sets solid color by RGB (0-255). */
    fun setSolidColorRgb(r: Int, g: Int, b: Int)
}
