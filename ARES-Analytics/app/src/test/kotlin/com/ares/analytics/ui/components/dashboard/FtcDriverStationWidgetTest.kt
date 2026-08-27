package com.ares.analytics.ui.components.dashboard

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtcDriverStationWidgetTest {
    @Test
    fun `dashboard opmode controls require a connected loopback simulator`() {
        assertTrue(canIssueDashboardDriverStationCommands(isConnected = true, host = "127.0.0.1"))
        assertTrue(canIssueDashboardDriverStationCommands(isConnected = true, host = "[::1]"))
        assertFalse(canIssueDashboardDriverStationCommands(isConnected = false, host = "127.0.0.1"))
        assertFalse(canIssueDashboardDriverStationCommands(isConnected = true, host = "192.168.43.1"))
        assertFalse(canIssueDashboardDriverStationCommands(isConnected = true, host = "10.23.247.2"))
    }
}
