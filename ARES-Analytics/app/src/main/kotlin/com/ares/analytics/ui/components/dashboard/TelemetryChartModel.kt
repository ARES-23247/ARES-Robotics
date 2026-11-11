package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.Immutable
import com.ares.analytics.shared.RobotUnit
import com.ares.analytics.shared.UnitCategory
import com.ares.analytics.shared.UnitConversion
import kotlin.math.abs
import kotlin.math.max

@Immutable
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
        var groupDisplayUnit: RobotUnit? = null
        for (i in keys.indices) {
            val k = keys[i]
            val u = targetUnits[k] ?: UnitConversion.detectUnitFromKey(k)
            if (u != null) {
                groupDisplayUnit = u
                break
            }
        }
        val series = ArrayList<TelemetryChartSeries>(keys.size)
        for (i in keys.indices) {
            val key = keys[i]
            val allPoints = pointsByKey[key].orEmpty()
            val firstVisible = firstIndexAtOrAfter(allPoints, minTimestamp)
            val startIndex = when {
                allPoints.isEmpty() -> 0
                firstVisible < 0 -> allPoints.lastIndex
                firstVisible == 0 -> 0
                else -> firstVisible - 1
            }
            val visiblePoints = if (allPoints.isEmpty()) emptyList() else allPoints.subList(startIndex, allPoints.size)
            val sourceUnit = UnitConversion.detectUnitFromKey(key)
            series.add(
                TelemetryChartSeries(
                    key = key,
                    points = visiblePoints,
                    sourceUnit = sourceUnit,
                    displayUnit = groupDisplayUnit,
                )
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

private fun firstIndexAtOrAfter(points: List<TelemetryPoint>, minTimestamp: Long): Int {
    val size = points.size
    if (size == 0) return -1
    if (size < 32) {
        for (i in 0 until size) {
            if (points[i].timestampMs >= minTimestamp) return i
        }
        return -1
    }
    if (points[size - 1].timestampMs < minTimestamp) return -1
    if (points[0].timestampMs >= minTimestamp) return 0
    var low = 0
    var high = size - 1
    while (low < high) {
        val mid = (low + high) ushr 1
        if (points[mid].timestampMs < minTimestamp) {
            low = mid + 1
        } else {
            high = mid
        }
    }
    return low
}

private fun chartBounds(series: List<TelemetryChartSeries>): TelemetryChartBounds {
    var rawMin = Double.POSITIVE_INFINITY
    var rawMax = Double.NEGATIVE_INFINITY
    var hasFinite = false

    for (sIdx in series.indices) {
        val chartSeries = series[sIdx]
        val pts = chartSeries.points
        val srcUnit = chartSeries.sourceUnit
        val dstUnit = chartSeries.displayUnit
        val needsConvert = srcUnit != null && dstUnit != null && srcUnit != dstUnit
        for (pIdx in pts.indices) {
            val raw = pts[pIdx].value
            val converted = if (needsConvert) UnitConversion.convert(raw, srcUnit!!, dstUnit!!) else raw
            if (converted.isFinite()) {
                hasFinite = true
                if (converted < rawMin) rawMin = converted
                if (converted > rawMax) rawMax = converted
            }
        }
    }

    if (!hasFinite) return TelemetryChartBounds(0.0, 1.0)
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
    if (sourceUnit == displayUnit) value else UnitConversion.convert(value, sourceUnit, displayUnit)
} else {
    value
}

@Immutable
data class TelemetryPoint(val timestampMs: Long, val value: Double)

fun buildSignalTree(keys: List<String>): SignalNode {
    val root = SignalNode("", "", false)
    val pathBuilder = StringBuilder(64)
    for (kIdx in keys.indices) {
        val topic = keys[kIdx]
        var current = root
        pathBuilder.setLength(0)
        var start = 0
        val len = topic.length
        while (start < len) {
            while (start < len && topic[start] == '/') start++
            if (start >= len) break
            var end = topic.indexOf('/', start)
            if (end < 0) end = len
            val part = topic.substring(start, end)
            pathBuilder.append('/').append(part)
            var nextPos = end
            while (nextPos < len && topic[nextPos] == '/') nextPos++
            val isLeaf = nextPos >= len
            current = current.children.getOrPut(part) {
                SignalNode(part, pathBuilder.toString(), isLeaf)
            }
            start = nextPos
        }
    }
    return root
}
