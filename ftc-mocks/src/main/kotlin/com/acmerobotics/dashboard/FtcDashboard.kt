package com.acmerobotics.dashboard

import com.acmerobotics.dashboard.telemetry.TelemetryPacket

/**
 * Mock representation of [FtcDashboard] helper.
 */
object FtcDashboard {
    @JvmStatic
    fun getInstance(): FtcDashboard = this
    fun sendTelemetryPacket(packet: TelemetryPacket) {}
}
