package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.shared.RobotUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryChartModelTest {
    @Test
    fun `chart groups unlike dimensions into separate aligned bands`() {
        assertEquals(1, telemetryChartGroupCount(listOf("Robot/BatteryVoltage", "Power/BusVoltage")))
        assertEquals(2, telemetryChartGroupCount(listOf("Robot/BatteryVoltage", "Power/TotalCurrentAmps")))
        assertEquals(2, telemetryChartGroupCount(listOf("Subsystem/Output", "Drive/HeadingRad")))
    }

    @Test
    fun `snapshot converts values and computes bounds before drawing`() {
        val snapshot = buildTelemetryChartSnapshot(
            selectedKeys = listOf("Robot/BatteryVoltage"),
            pointsByKey = mapOf(
                "Robot/BatteryVoltage" to listOf(
                    TelemetryPoint(timestampMs = 9_000L, value = 10.0),
                    TelemetryPoint(timestampMs = 10_000L, value = 12.0),
                ),
            ),
            targetUnits = mapOf("Robot/BatteryVoltage" to RobotUnit.MILLIVOLT),
            nowMs = 10_000L,
            windowMs = 2_000L,
        )

        val group = snapshot.groups.single()
        assertEquals("mV", group.unitSymbol)
        assertEquals(9_800.0, group.bounds.min, absoluteTolerance = 0.0001)
        assertEquals(12_200.0, group.bounds.max, absoluteTolerance = 0.0001)
        assertEquals(10_000.0, convertTelemetryChartValue(10.0, group.series.single().sourceUnit, group.series.single().displayUnit))
    }

    @Test
    fun `snapshot keeps one boundary predecessor but drops older history`() {
        val snapshot = buildTelemetryChartSnapshot(
            selectedKeys = listOf("Drive/HeadingRad"),
            pointsByKey = mapOf(
                "Drive/HeadingRad" to listOf(
                    TelemetryPoint(timestampMs = 1_000L, value = 100.0),
                    TelemetryPoint(timestampMs = 7_000L, value = 5.0),
                    TelemetryPoint(timestampMs = 9_000L, value = 10.0),
                ),
            ),
            targetUnits = emptyMap(),
            nowMs = 10_000L,
            windowMs = 2_000L,
        )

        val group = snapshot.groups.single()
        assertEquals(listOf(7_000L, 9_000L), group.series.single().points.map(TelemetryPoint::timestampMs))
        assertTrue(group.bounds.max < 100.0)
    }

    @Test
    fun `snapshot preserves mixed-unit saved channels in separate groups`() {
        val snapshot = buildTelemetryChartSnapshot(
            selectedKeys = listOf("Robot/BatteryVoltage", "Power/TotalCurrentAmps"),
            pointsByKey = emptyMap(),
            targetUnits = emptyMap(),
            nowMs = 0L,
            windowMs = 1_000L,
        )

        assertEquals(2, snapshot.groups.size)
        assertEquals(setOf("V", "A"), snapshot.groups.map { it.unitSymbol }.toSet())
    }
}
