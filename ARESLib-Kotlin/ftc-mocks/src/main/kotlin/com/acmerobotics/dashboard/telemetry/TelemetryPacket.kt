package com.acmerobotics.dashboard.telemetry

import com.acmerobotics.dashboard.canvas.Canvas

/**
 * Mock representation of FTC Dashboard [TelemetryPacket].
 *
 * Encapsulates a structured telemetry frame comprising key-value debug pairs
 * and 2D vector field drawings ([fieldOverlay]) destined for dashboard consumers.
 *
 * In desktop simulation, telemetry packets avoid heap churn and network serialization
 * overhead by keeping operations safely local and retaining deterministic reference instances.
 */
open class TelemetryPacket {
    /**
     * Vector drawing canvas overlay associated with this telemetry packet.
     *
     * Pre-instantiated to avoid heap allocations in high-frequency robot control loops.
     */
    val fieldOverlay: Canvas = Canvas()

    /**
     * Constructs an empty [TelemetryPacket] with a fresh [fieldOverlay] canvas.
     */
    constructor()

    /**
     * Records a key-value data pair into the packet for remote telemetry plotting.
     *
     * In desktop simulation, this method safely discards or logs the value to avoid
     * unbounded collection growth across long continuous simulation runs.
     *
     * @param key Telemetry item identifier or category.
     * @param value Arbitrary scalar, string, or printable object value.
     */
    fun put(key: String, value: Any) {}
}
