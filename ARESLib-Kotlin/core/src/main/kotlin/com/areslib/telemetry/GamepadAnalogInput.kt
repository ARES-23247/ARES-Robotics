package com.areslib.telemetry

private const val ANALOG_DEFAULT_FIRST_UPDATE_SECONDS = 0.02
private const val ANALOG_MAX_SLEW_DT_SECONDS = 0.2

internal fun slewIntervalSeconds(timestampMs: Long, previousMs: Long, initialized: Boolean): Double {
    if (!initialized) return ANALOG_DEFAULT_FIRST_UPDATE_SECONDS
    if (timestampMs <= previousMs) return 0.0
    val elapsedMs = timestampMs - previousMs
    // An ordered forward interval that overflows Long is necessarily larger than the cap.
    return if (elapsedMs < 0L || elapsedMs >= 200L) ANALOG_MAX_SLEW_DT_SECONDS else elapsedMs / 1_000.0
}

/** Primitive gamepad-axis source. Its [Float] return avoids boxed values in the robot loop. */
fun interface GamepadAxisSource {
    fun read(state: GamepadState): Float
}
