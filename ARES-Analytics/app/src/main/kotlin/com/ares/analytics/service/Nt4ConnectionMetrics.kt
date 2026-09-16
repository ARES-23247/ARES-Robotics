package com.ares.analytics.service

/** True only for hosts that cannot directly address a physical robot on the network. */
internal fun isLoopbackDriveControlHost(host: String): Boolean = when (
    host.trim().lowercase().removePrefix("[").removeSuffix("]")
) {
    "127.0.0.1", "localhost", "::1" -> true
    else -> false
}

private val SIMULATOR_ONLY_DRIVER_STATION_TOPICS = setOf(
    "ARES/DriverStation/SelectedOpMode",
    "ARES/DriverStation/Command",
    "ARES/DriverStation/MatchState",
    "ARES/Input/selectedAuto",
)

/** Prevents dashboard OpMode orchestration from reaching a physical robot target. */
internal fun isDashboardDriverStationCommandAllowed(host: String, key: String): Boolean =
    key.removePrefix("/") !in SIMULATOR_ONLY_DRIVER_STATION_TOPICS || isLoopbackDriveControlHost(host)

data class Nt4ConnectionMetrics(
    val attempts: Long,
    val successfulConnections: Long,
    val reconnects: Long,
    val connected: Boolean,
)
