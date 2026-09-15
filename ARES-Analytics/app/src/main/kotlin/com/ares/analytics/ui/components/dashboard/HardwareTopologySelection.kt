package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.*
import com.ares.analytics.service.DatabaseService
import com.areslib.telemetry.schema.HardwareTopology
import kotlinx.coroutines.CancellationException

internal data class HardwareTopologySelection(
    val topology: HardwareTopology?,
    val cached: Boolean,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** The database stores a latest cached map per robot, not a topology snapshot per historical session. */
@Composable
internal fun rememberHardwareTopologySelection(
    live: HardwareTopology?,
    database: DatabaseService,
    sessionId: String?,
): HardwareTopologySelection {
    val cached = sessionId != null && sessionId != "live-telemetry"
    var selection by remember(database, sessionId) {
        mutableStateOf(HardwareTopologySelection(null, cached, loading = cached))
    }
    LaunchedEffect(database, sessionId) {
        if (cached) {
            try {
                val robotId = database.getSessionSummary(requireNotNull(sessionId))?.robotId?.takeIf { it.isNotBlank() }
                val topology = robotId?.let { database.getTopology(it) }
                if (topology != null && topology.robotId != robotId) {
                    selection = HardwareTopologySelection(null, true, failed = true)
                } else selection = HardwareTopologySelection(topology, true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selection = HardwareTopologySelection(null, true, failed = true)
            }
        }
    }
    return if (cached) selection else HardwareTopologySelection(live, false)
}
