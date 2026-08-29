package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SingleSignalWidgetTest {
    @Test
    fun `replay sample wins without mixing live timestamps`() {
        val live = TelemetryFrame(
            timestampMs = 100,
            sessionId = "live",
            key = "/Robot/LoopTimeMs",
            value = 19.5,
        )
        val replay = ReplayFrame(
            timestampMs = 2_000,
            playheadMs = 2_075,
            sessionId = "recording",
            values = mapOf("Robot/LoopTimeMs" to 24.25),
        )

        val sample = resolveDashboardSignalSample("/Robot/LoopTimeMs", live, replay)

        assertEquals(DashboardSignalSource.REPLAY, sample?.source)
        assertEquals(24.25, sample?.value)
        assertEquals(2_000, sample?.sourceTimestampMs)
        assertEquals(2_075, sample?.playheadTimestampMs)
    }

    @Test
    fun `live sample keeps its source timestamp and canonical topic`() {
        val live = TelemetryFrame(
            timestampMs = 1_250,
            sessionId = "live",
            key = "/Drive/Pose_X",
            value = 1.75,
        )

        val sample = resolveDashboardSignalSample("Drive/Pose_X", live, replayFrame = null)

        assertEquals(DashboardSignalSource.LIVE, sample?.source)
        assertEquals("Drive/Pose_X", sample?.topic)
        assertEquals(1_250, sample?.sourceTimestampMs)
        assertEquals(1_250, sample?.playheadTimestampMs)
        assertNull(resolveDashboardSignalSample("Drive/Pose_Y", live, replayFrame = null))
    }

    @Test
    fun `configuration round trip preserves display metadata without converting values`() {
        val configuration = SingleSignalConfiguration(
            topic = "/Drive/Pose_Heading",
            label = "Heading",
            unit = "rad",
            displayMode = "bar",
            minimum = -3.2,
            maximum = 3.2,
            warningLow = -3.0,
            warningHigh = 3.0,
        )

        assertEquals(configuration.copy(topic = "Drive/Pose_Heading"), singleSignalConfiguration(configuration.toProperties()))
    }
}
