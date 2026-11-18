package com.ares.analytics.ui.components.history

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunHistoryMeasurementsTest {
    private val session = Session("run", "team", "season", "robot", 0)
    private val summary = SessionSummary("run", "team", "season", "robot", 0)
    private val rows = RunDataDictionary.buildBaseRowDefinitions()
    private val diagnostics = emptyMap<String, Double>()

    @Test
    fun `absent summary measurements have no display or chart value`() {
        val labels = listOf(
            "Min Battery Voltage (V)", "Battery Resistance (Ω)", "Avg Loop Time (ms)", "P95 Loop Time (ms)",
            "Max EKF Drift (m)", "Avg Cross-Track Error (m)", "Vision Latency (ms)", "Vision Acceptance (%)",
        )
        for (label in labels) {
            val row = rows.single { it.label == label }
            for (missing in listOf(null, summary, summary.copy(observedMetrics = emptySet(), minBatteryVoltage = 12.0))) {
                assertEquals("N/A", row.getValue(session, missing, diagnostics), label)
                assertNull(row.getNumericValue(session, missing, diagnostics), label)
            }
        }
    }

    @Test
    fun `measured zero remains a chart point and can trigger a voltage alert`() {
        val row = rows.single { it.label == "Min Battery Voltage (V)" }
        val recorded = summary.copy(observedMetrics = setOf("battery"))
        assertEquals("0.00V", row.getValue(session, recorded, diagnostics))
        assertEquals(0.0, row.getNumericValue(session, recorded, diagnostics))
        assertTrue(row.isAnomaly(0.0))
        assertNull(row.getNumericValue(session, recorded.copy(minBatteryVoltage = Double.NaN), diagnostics))
    }

    @Test
    fun `legacy resistance measurements retain ohms in both label and value`() {
        val row = rows.single { it.label == "Battery Resistance (Ω)" }
        val legacy = summary.copy(avgBatteryResistance = 0.02)
        assertEquals("0.020 Ω", row.getValue(session, legacy, diagnostics))
        assertEquals(0.02, row.getNumericValue(session, legacy, diagnostics))
    }
}
