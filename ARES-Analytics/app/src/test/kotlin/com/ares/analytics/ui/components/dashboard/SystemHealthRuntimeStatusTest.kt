package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.telemetry.TelemetryTopicConstants
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemHealthRuntimeStatusTest {
    @Test
    fun `photon is not reported active until robot confirms interception`() {
        val selected = FtcRuntimeDashboardState()
            .accept(text(TelemetryTopicConstants.FTC_HUB_COMMAND_TRANSPORT, "ARES_PHOTON"))
            .accept(number(TelemetryTopicConstants.FTC_PHOTON_ACTIVE, 0.0))

        assertEquals("PHOTON SELECTED · INACTIVE", selected.presentation().transportLabel)
        assertEquals(FtcRuntimeTone.WARNING, selected.presentation().transportTone)

        val active = selected.accept(number(TelemetryTopicConstants.FTC_PHOTON_ACTIVE, 1.0))
        assertEquals("PHOTON ACTIVE", active.presentation().transportLabel)
        assertEquals(FtcRuntimeTone.HEALTHY, active.presentation().transportTone)
    }

    @Test
    fun `standard SDK and Limelight proxy states remain explicit text`() {
        val status = FtcRuntimeDashboardState()
            .accept(text("/${TelemetryTopicConstants.FTC_HUB_COMMAND_TRANSPORT}", "STANDARD_SDK"))
            .accept(number(TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_CONFIGURED, 1.0))
            .accept(number(TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_ACTIVE, 0.0))

        assertEquals("FTC SDK SELECTED", status.presentation().transportLabel)
        assertEquals("LIMELIGHT PROXY SELECTED · INACTIVE", status.presentation().proxyLabel)
    }

    private fun number(key: String, value: Double) = TelemetryFrame(0L, "live", key, value)

    private fun text(key: String, value: String) = TelemetryFrame(0L, "live", key, 0.0, value)
}
