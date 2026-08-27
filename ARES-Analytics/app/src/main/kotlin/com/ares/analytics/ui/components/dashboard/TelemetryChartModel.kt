package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.shared.RobotUnit
import com.ares.analytics.shared.UnitCategory
import com.ares.analytics.shared.UnitConversion
import kotlin.math.abs
import kotlin.math.max

internal data class TelemetryChartBounds(
    val min: Double,
    val max: Double,
)

internal data class TelemetryChartSeries(
    val key: String,
    val points: List<TelemetryPoint>,
    val sourceUnit: RobotUnit?,
    val displayUnit: RobotUnit?,
)

internal data class TelemetryChartGroup(
    val category: UnitCategory?,
    val series: List<TelemetryChartSeries>,
    val bounds: TelemetryChartBounds,
    val unitSymbol: String,
)

internal data class TelemetryChartSnapshot(
    val groups: List<TelemetryChartGroup>,
)

internal fun telemetryChartCategory(key: String): UnitCategory? =
    UnitConversion.detectUnitFromKey(key)?.category?.takeUnless { it == UnitCategory.NONE }

internal fun telemetryChartGroupCount(keys: Collection<String>): Int =
    keys.asSequence().map(::telemetryChartCategory).distinct().count()

/**
 * Builds time-aligned small multiples. Signals with the same physical dimension share a band and
 * display unit; unlike dimensions receive separate Y bounds instead of a misleading common axis.
 */
internal fun buildTelemetryChartSnapshot(
    selectedKeys: List<String>,
    pointsByKey: Map<String, List<TelemetryPoint>>,
    targetUnits: Map<String, RobotUnit>,
    nowMs: Long,
    windowMs: Long,
): TelemetryChartSnapshot {
    require(windowMs > 0L) { "Telemetry chart window must be positive" }
    val minTimestamp = nowMs - windowMs

    val groups = selectedKeys.groupBy(::telemetryChartCategory).map { (category, keys) ->
        val groupDisplayUnit = keys.asSequence()
            .mapNotNull { key -> targetUnits[key] ?: UnitConversion.detectUnitFromKey(key) }
            .firstOrNull()
        val series = keys.map { key ->
            val allPoints = pointsByKey[key].orEmpty()
            val firstVisible = allPoints.indexOfFirst { it.timestampMs >= minTimestamp }
            val startIndex = when {
                allPoints.isEmpty() -> 0
                firstVisible < 0 -> allPoints.lastIndex
                firstVisible == 0 -> 0
                else -> firstVisible - 1
            }
            val visiblePoints = if (allPoints.isEmpty()) emptyList() else allPoints.subList(startIndex, allPoints.size)
            val sourceUnit = UnitConversion.detectUnitFromKey(key)
            TelemetryChartSeries(
                key = key,
                points = visiblePoints,
                sourceUnit = sourceUnit,
                displayUnit = groupDisplayUnit,
            )
        }

        TelemetryChartGroup(
            category = category,
            series = series,
            bounds = chartBounds(series),
            unitSymbol = groupDisplayUnit?.symbol.orEmpty(),
        )
    }

    return TelemetryChartSnapshot(groups)
}

private fun chartBounds(series: List<TelemetryChartSeries>): TelemetryChartBounds {
    val convertedValues = series.asSequence().flatMap { chartSeries ->
        chartSeries.points.asSequence().mapNotNull { point ->
            convertTelemetryChartValue(
                value = point.value,
                sourceUnit = chartSeries.sourceUnit,
                displayUnit = chartSeries.displayUnit,
            ).takeIf(Double::isFinite)
        }
    }.toList()

    if (convertedValues.isEmpty()) return TelemetryChartBounds(0.0, 1.0)
    val rawMin = convertedValues.min()
    val rawMax = convertedValues.max()
    val padding = if (rawMin == rawMax) {
        max(abs(rawMin) * 0.1, 0.1)
    } else {
        (rawMax - rawMin) * 0.1
    }
    return TelemetryChartBounds(rawMin - padding, rawMax + padding)
}

internal fun convertTelemetryChartValue(
    value: Double,
    sourceUnit: RobotUnit?,
    displayUnit: RobotUnit?,
): Double = if (sourceUnit != null && displayUnit != null) {
    UnitConversion.convert(value, sourceUnit, displayUnit)
} else {
    value
}
