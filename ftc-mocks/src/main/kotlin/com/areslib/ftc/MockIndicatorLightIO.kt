package com.areslib.ftc

import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.hardware.actuator.IndicatorLightIO
import com.areslib.telemetry.ITelemetry

/**
 * In-memory mock implementation of [IndicatorLightIO] for desktop simulation.
 * Simply stores the current position without any hardware interaction.
 *
 * @param name The logical name of this indicator light (matches the hardware map name).
 */
class MockIndicatorLightIO(val name: String) : IndicatorLightIO {
    override var currentPosition: Double = 0.0
        private set

    /** Stores [position] after clamping it to the servo-compatible `[0, 1]` range. */
    override fun setPosition(position: Double) {
        currentPosition = position.coerceIn(0.0, 1.0)
    }

    /** Moves the simulated light to the canonical off position. */
    override fun safe() {
        setPosition(IndicatorLightColor.OFF.position)
    }

    /** No-op because the double is write-only and already cached. */
    override fun refresh() {
        // Write-only device — no sensor reads needed
    }

    /** Publishes the cached normalized position without performing hardware IO. */
    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/Position", currentPosition)
    }
}
