package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.ReplayFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayDashboardModelsTest {
    @Test
    fun `missing health topics remain unknown rather than healthy zeroes`() {
        val snapshot = ReplayFrame(1_000, mapOf("Robot/Mode" to 0.0)).toReplayHealthSnapshot()
        assertNull(snapshot.loopTimeMs)
        assertNull(snapshot.batteryVoltage)
        assertNull(snapshot.brownoutCount)
        assertNull(snapshot.loopOverruns)
    }

    @Test
    fun `health aliases and runtime modes are reconstructed from one replay frame`() {
        val snapshot = ReplayFrame(
            timestampMs = 1_000,
            values = mapOf(
                "Robot/LoopTimeMs" to 20.0,
                "Robot/BatteryVoltage" to 12.4,
                "Robot/BrownoutCount" to 1.0,
                "Robot/LoopOverruns" to 2.0,
                "ARES/Runtime/FTC/PhotonActive" to 1.0,
            ),
            stringValues = mapOf("ARES/Runtime/FTC/HubCommandTransport" to "ARES_PHOTON"),
        ).toReplayHealthSnapshot()

        assertEquals(20.0, snapshot.loopTimeMs)
        assertEquals(12.4, snapshot.batteryVoltage)
        assertEquals(1, snapshot.brownoutCount)
        assertEquals(2, snapshot.loopOverruns)
        assertTrue(snapshot.ftcRuntime.photonActive == true)
        assertFalse(snapshot.ftcRuntime.presentation().transportLabel.contains("INACTIVE"))
    }
}
