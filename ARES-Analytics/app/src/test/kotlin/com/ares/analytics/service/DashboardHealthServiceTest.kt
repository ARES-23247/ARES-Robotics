package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardHealthServiceTest {

    @Test
    fun `robot logging metrics remain distinct from dashboard replay drops`() = runTest {
        val store = TelemetryStore()
        suspend fun accept(key: String, value: Double = 0.0, stringValue: String? = null) {
            store.accept(TelemetryFrame(1_000L, "live", key, value, stringValue))
        }
        accept("Diagnostics/Logging/Profile", stringValue = "SIMULATION")
        accept("Diagnostics/Logging/QueueDepth", 17.0)
        accept("Diagnostics/Logging/CurrentFileBytes", 4_096.0)
        accept("Diagnostics/Logging/CompletedBytes", 8_192.0)
        accept("Diagnostics/Logging/DroppedFrames", 3.0)
        accept("Diagnostics/Logging/PrunedFiles", 9.0)

        val health = readRobotLoggingHealth(store)

        assertEquals("SIMULATION", health.profile)
        assertEquals(17, health.queueDepth)
        assertEquals(4_096L, health.currentFileBytes)
        assertEquals(8_192L, health.completedBytes)
        assertEquals(3L, health.droppedFrames)
        assertEquals(9L, health.prunedFiles)
    }
}
