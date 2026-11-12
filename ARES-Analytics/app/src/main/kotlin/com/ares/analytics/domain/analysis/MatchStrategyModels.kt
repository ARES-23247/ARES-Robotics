package com.ares.analytics.domain.analysis

import com.ares.analytics.shared.models.League

enum class StrategyHeatmapMode(
    val label: String,
    val unitLabel: String,
) {
    OCCUPANCY("Occupancy / Dwell", "s"),
    SPEED("Robot Speed", "m/s"),
    VOLTAGE_SAG("Voltage Sag", "V drop"),
}

enum class CoachingSeverity {
    CRITICAL,
    WARNING,
    TIP,
    EXCELLENT,
}

enum class ProgressionTrend {
    IMPROVING,
    STABLE,
    REGRESSING,
}

data class StrategyPosePoint(
    val x: Double,
    val y: Double,
    val headingRadians: Double,
    val timestampMs: Long,
)

data class FieldBoundsMeters(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
) {
    val widthMeters: Double get() = (maxX - minX).coerceAtLeast(0.1)
    val heightMeters: Double get() = (maxY - minY).coerceAtLeast(0.1)
}

data class HeatmapCell(
    val col: Int,
    val row: Int,
    val sampleCount: Int,
    val dwellSeconds: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val maxSagVolts: Double,
) {
    fun metricFor(mode: StrategyHeatmapMode): Double = when (mode) {
        StrategyHeatmapMode.OCCUPANCY -> dwellSeconds
        StrategyHeatmapMode.SPEED -> avgSpeedMps
        StrategyHeatmapMode.VOLTAGE_SAG -> maxSagVolts
    }
}

data class SpatialFieldHeatmap(
    val league: League,
    val bounds: FieldBoundsMeters,
    val cols: Int,
    val rows: Int,
    val cells: List<HeatmapCell>,
    val trajectory: List<StrategyPosePoint>,
    val poseSampleCount: Int,
    val totalDistanceMeters: Double,
    val peakSpeedMps: Double,
    val maxSagVolts: Double,
) {
    fun maxCellMetric(mode: StrategyHeatmapMode): Double =
        cells.maxOfOrNull { it.metricFor(mode) }?.takeIf { it > 1e-6 } ?: 1.0
}

data class MatchCoachingInsight(
    val id: String,
    val title: String,
    val detail: String,
    val category: String,
    val severity: CoachingSeverity,
    val metricBadge: String,
    val jumpTimestampMs: Long? = null,
)

data class EventProgressionPoint(
    val sessionId: String,
    val label: String,
    val createdAtMs: Long,
    val value: Double,
)

data class EventProgressionMetricSeries(
    val id: String,
    val title: String,
    val unit: String,
    val higherIsBetter: Boolean,
    val points: List<EventProgressionPoint>,
    val latestFormatted: String,
    val deltaFormatted: String,
    val trend: ProgressionTrend,
)
