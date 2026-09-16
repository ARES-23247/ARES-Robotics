package com.acmerobotics.dashboard

import com.acmerobotics.dashboard.telemetry.TelemetryPacket

/**
 * Mock representation of FTC Dashboard [FtcDashboard] helper singleton.
 *
 * Provides headless desktop simulation doubles for FTC Dashboard telemetry broadcasting
 * and canvas rendering operations. In simulation, telemetry packets sent via
 * [sendTelemetryPacket] are safely consumed without requiring network sockets
 * or an active web browser client.
 *
 * Tests and control algorithms interacting with the dashboard singleton can verify
 * field overlays, telemetry graphs, and tuning variable updates without side effects.
 */
object FtcDashboard {
    /**
     * Returns the singleton instance of [FtcDashboard].
     *
     * Mimics the Java-static getter pattern `FtcDashboard.getInstance()` used widely
     * across FTC robot controller codebases and TeamCode opmodes.
     */
    @JvmStatic
    fun getInstance(): FtcDashboard = this

    /**
     * Consumes the given telemetry [packet] in desktop simulation.
     *
     * In headless environments, this method is a safe no-op that discards the packet
     * to avoid memory retention while maintaining API source and binary compatibility.
     */
    fun sendTelemetryPacket(packet: TelemetryPacket) {}
}
