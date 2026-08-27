package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.RobotTopicContract
import kotlin.test.Test
import kotlin.test.assertEquals

class AutonomousSelectorCardTest {
    @Test
    fun `available routine publications accept csv or json and normalize deterministically`() {
        assertEquals(listOf("safe", "score"), parseAvailableAutoDocuments("score, safe,score"))
        assertEquals(listOf("safe", "score"), parseAvailableAutoDocuments("[\"score\",\"safe\"]"))
        assertEquals(emptyList(), parseAvailableAutoDocuments("  "))
    }

    @Test
    fun `FRC selector request matches the robot SmartDashboard contract`() {
        assertEquals("ARES/Auto/Requested", RobotTopicContract.FRC_AUTONOMOUS_REQUEST)
        assertEquals(
            "SmartDashboard/SelectedAuto",
            RobotTopicContract.FRC_SMART_DASHBOARD_AUTONOMOUS_REQUEST,
        )
        assertEquals("ARES/Input/selectedAuto", RobotTopicContract.FTC_AUTONOMOUS_REQUEST)
    }
}
