package com.ares.analytics.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryMetricCatalogTest {

    @Test
    fun `normalization removes leading separators and resolves aliases`() {
        assertEquals("Drive/Voltage", TelemetryMetricCatalog.normalizeTopic("///Drive/Voltage"))
        assertEquals(
            TelemetryMetricCatalog.BATTERY_VOLTAGE,
            TelemetryMetricCatalog.metricFor("/Battery/Voltage")
        )
    }

    @Test
    fun `drive voltage is not misclassified as battery voltage`() {
        assertEquals(
            TelemetryMetricCatalog.DRIVE_VOLTAGE,
            TelemetryMetricCatalog.metricFor("Drive/Voltage")
        )
        assertTrue("Drive/Voltage" !in TelemetryMetricCatalog.BATTERY_VOLTAGE.keys)
        assertNull(TelemetryMetricCatalog.metricFor("Unknown/Metric"))
    }
}
