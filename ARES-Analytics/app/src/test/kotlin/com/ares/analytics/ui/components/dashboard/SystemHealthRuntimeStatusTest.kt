package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.telemetry.TelemetryTopicConstants
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemHealthRuntimeStatusTest {
    @Test
    fun `photon is not reported active until robot confirms interception`() {
        val tracker = ControllerHealthTracker()
        tracker.accept(text(TelemetryTopicConstants.FTC_HUB_COMMAND_TRANSPORT, "ARES_PHOTON"), 0, 1)
        tracker.accept(number(TelemetryTopicConstants.FTC_PHOTON_ACTIVE, 0.0), 0, 1)
        val selected = tracker.snapshot(0, 1).snapshot.ftcRuntime

        assertEquals("PHOTON SELECTED · INACTIVE", selected.presentation().transportLabel)
        assertEquals(FtcRuntimeTone.WARNING, selected.presentation().transportTone)

        tracker.accept(number(TelemetryTopicConstants.FTC_PHOTON_ACTIVE, 1.0), 1, 1)
        val active = tracker.snapshot(1, 1).snapshot.ftcRuntime
        assertEquals("PHOTON ACTIVE", active.presentation().transportLabel)
        assertEquals(FtcRuntimeTone.HEALTHY, active.presentation().transportTone)
    }

    @Test
    fun `standard SDK and Limelight proxy states remain explicit text`() {
        val tracker = ControllerHealthTracker()
        tracker.accept(text("/${TelemetryTopicConstants.FTC_HUB_COMMAND_TRANSPORT}", "STANDARD_SDK"), 0, 1)
        tracker.accept(number(TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_CONFIGURED, 1.0), 0, 1)
        tracker.accept(number(TelemetryTopicConstants.FTC_LIMELIGHT_PROXY_ACTIVE, 0.0), 0, 1)
        val status = tracker.snapshot(0, 1).snapshot.ftcRuntime

        assertEquals("FTC SDK SELECTED", status.presentation().transportLabel)
        assertEquals("LIMELIGHT PROXY SELECTED · INACTIVE", status.presentation().proxyLabel)
    }

    private fun number(key: String, value: Double) = TelemetryFrame(0L, "live", key, value)

    private fun text(key: String, value: String) = TelemetryFrame(0L, "live", key, 0.0, value)
}
