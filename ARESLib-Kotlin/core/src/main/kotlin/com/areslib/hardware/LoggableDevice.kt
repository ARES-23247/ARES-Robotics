package com.areslib.hardware

import com.areslib.telemetry.ITelemetry

/**
 * Unified interface for any hardware component or sensor that publishes diagnostics or telemetry keys.
 */
interface LoggableDevice {
    /**
     * Publishes telemetry metrics for this device to the provider using the specified prefix key.
     */
    fun logTelemetry(telemetry: ITelemetry, prefix: String) {}
}
