package com.acmerobotics.dashboard.telemetry

import com.acmerobotics.dashboard.canvas.Canvas

/**
 * Mock representation of FTC Dashboard [TelemetryPacket].
 */
open class TelemetryPacket {
    val fieldOverlay = Canvas()
    fun put(key: String, value: Any) {}
}
