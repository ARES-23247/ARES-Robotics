package com.areslib.telemetry

/**
 * Lightweight abstraction for human-readable driver station telemetry.
 * Prevents reflection overhead in hardware facades while maintaining FTC OpMode independence.
 */
interface LocalTelemetry {
    fun addData(caption: String, value: Any?)
    fun update()
}
