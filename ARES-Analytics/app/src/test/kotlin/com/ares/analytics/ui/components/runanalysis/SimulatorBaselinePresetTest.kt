@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.ares.analytics.ui.components.runanalysis

import androidx.compose.ui.test.*
import com.ares.analytics.shared.models.SIMULATION_SESSION_TAG
import com.ares.analytics.shared.models.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SimulatorBaselinePresetTest {
    private val latest = Session("latest", "team", "season", "robot", 3000)
    private val previous = latest.copy(sessionId = "manual", createdAt = 2000, tags = listOf("manual-import"))
    private val preset = StudentRunComparisonPreset.LATEST_VS_SIMULATOR_BASELINE

    @Test
    fun `manual runs cannot stand in for a simulator baseline`() = runComposeUiTest {
        val sessions = listOf(previous, latest)
        setContent { StudentRunComparisonPresetBar(sessions) {} }
        onNodeWithText(preset.label).assertIsNotEnabled()
        onNodeWithText("No earlier simulator run").assertExists()
        onNodeWithText(StudentRunComparisonPreset.LATEST_VS_PREVIOUS.label).assertIsEnabled()
    }

    @Test
    fun `use only the most recent prior simulator recording`() {
        val simulated = latest.copy(tags = listOf(SIMULATION_SESSION_TAG))
        assertNull(resolveStudentComparisonPreset(listOf(simulated, previous), preset))
        val older = simulated.copy(sessionId = "older-sim", createdAt = 1000)
        val newer = simulated.copy(sessionId = "newer-sim", createdAt = 1500)
        val resolved = resolveStudentComparisonPreset(listOf(older, latest, previous, newer), preset)
        assertEquals(listOf("newer-sim"), resolved?.comparisonSessionIds)
    }
}
