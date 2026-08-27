package com.ares.analytics.service

import com.ares.analytics.shared.SessionSummary
import com.ares.analytics.shared.TelemetryFrame
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

data class AdvancedAnalyticsReport(
    val sessionId: String,
    val comparison: SessionComparison?,
    val regressions: List<RegressionSignal>,
    val correlations: List<SignalCorrelation>,
    val driverScore: DriverPerformanceScore?,
    val pathHeatmap: List<PathHeatmapCell>,
    val diagnostics: List<DiagnosticInsight>,
    val tuningSuggestions: List<TuningSuggestion>
)

data class SessionComparison(
    val baselineSessionIds: List<String>,
    val metrics: List<MetricComparison>
)

data class MetricComparison(
    val metric: String,
    val unit: String,
    val current: Double,
    val baselineAverage: Double,
    val percentChange: Double,
    val lowerIsBetter: Boolean
)

data class RegressionSignal(
    val metric: String,
    val percentRegression: Double,
    val current: Double,
    val baseline: Double,
    val severity: InsightSeverity
)

data class SignalCorrelation(
    val leftTopic: String,
    val rightTopic: String,
    val coefficient: Double,
    val samples: Int
)

data class DriverPerformanceScore(
    val total: Double,
    val smoothness: Double,
    val decisiveness: Double,
    val consistency: Double,
    val samples: Int
)

data class PathHeatmapCell(
    val xIndex: Int,
    val yIndex: Int,
    val visits: Int,
    val averageSpeedMetersPerSecond: Double
)

enum class InsightSeverity { INFO, WARNING, CRITICAL }

data class DiagnosticInsight(
    val severity: InsightSeverity,
    val category: String,
    val message: String,
    val evidence: String
)

data class TuningSuggestion(
    val parameter: String,
    val recommendation: String,
    val confidence: Double,
    val rationale: String,
    val evidenceSamples: Int
)

/**
 * Produces a bounded, evidence-carrying analysis report for one recorded session. Every raw
 * signal query is viewport/downsample limited so report generation remains stable for long logs.
 */
class AdvancedAnalyticsService(private val databaseService: TelemetryAnalyticsRepository) {
    suspend fun analyzeAgainstRecent(
        sessionId: String,
        baselineCount: Int = 3
    ): OperationResult<AdvancedAnalyticsReport> {
        val current = databaseService.getSessionSummary(sessionId)
        val baselineIds = if (current == null) emptyList() else databaseService.getAllSessionSummaries()
            .asSequence()
            .filter { it.sessionId != sessionId }
            // Comparing another team, season, or robot can manufacture a persuasive but invalid
            // regression. A guided review is allowed to say that no compatible baseline exists.
            .filter {
                it.teamId == current.teamId &&
                    it.seasonId == current.seasonId &&
                    it.robotId == current.robotId
            }
            .sortedByDescending { it.createdAt }
            .take(baselineCount.coerceAtLeast(0))
            .map { it.sessionId }
            .toList()
        return analyzeSafely(sessionId, baselineIds)
    }

    suspend fun analyzeSafely(
        sessionId: String,
        baselineSessionIds: List<String> = emptyList()
    ): OperationResult<AdvancedAnalyticsReport> = try {
        if (databaseService.getSessionTimestampRange(sessionId) == null) {
            OperationResult.Unavailable("NO_TELEMETRY", "Session $sessionId has no telemetry frames")
        } else {
            OperationResult.Success(analyze(sessionId, baselineSessionIds))
        }
    } catch (error: Exception) {
        OperationResult.Failure("ANALYTICS_FAILED", error.message ?: "Analytics failed", error)
    }

    suspend fun analyze(sessionId: String, baselineSessionIds: List<String> = emptyList()): AdvancedAnalyticsReport {
        val range = databaseService.getSessionTimestampRange(sessionId)
        val summary = databaseService.getSessionSummary(sessionId)
        val baselines = baselineSessionIds.distinct()
            .filter { it != sessionId }
            .mapNotNull { databaseService.getSessionSummary(it) }
            .filter { baseline ->
                summary != null &&
                    baseline.teamId == summary.teamId &&
                    baseline.seasonId == summary.seasonId &&
                    baseline.robotId == summary.robotId
            }

        val comparison = summary?.let { compareSummaries(it, baselines) }
        val regressions = comparison?.metrics.orEmpty()
            .mapNotNull(::detectRegression)
            .sortedByDescending { it.percentRegression }

        if (range == null) {
            return AdvancedAnalyticsReport(
                sessionId, comparison, regressions, emptyList(), null, emptyList(),
                listOf(DiagnosticInsight(InsightSeverity.WARNING, "data", "No telemetry frames were recorded.", "Session range is empty")),
                emptyList()
            )
        }

        val keys = databaseService.getDistinctTelemetryKeys(sessionId)
        val seriesCache = HashMap<String, List<TelemetryFrame>>()
        suspend fun series(key: String): List<TelemetryFrame> = seriesCache.getOrPutSuspend(key) {
            databaseService.getTelemetrySeries(sessionId, key, range.first, range.second, MAX_SIGNAL_POINTS)
        }

        val correlations = buildCorrelations(keys, ::series)
        val driverScore = buildDriverScore(keys, ::series)
        val heatmap = buildPathHeatmap(keys, ::series)
        val diagnostics = buildDiagnostics(summary, regressions, correlations, driverScore)
        val suggestions = buildTuningSuggestions(summary, driverScore, correlations, baselines.size)

        return AdvancedAnalyticsReport(
            sessionId = sessionId,
            comparison = comparison,
            regressions = regressions,
            correlations = correlations,
            driverScore = driverScore,
            pathHeatmap = heatmap,
            diagnostics = diagnostics,
            tuningSuggestions = suggestions
        )
    }

    fun renderDiagnosticMarkdown(report: AdvancedAnalyticsReport): String = buildString {
        appendLine("# ARES analytics report: ${report.sessionId}")
        appendLine()
        report.driverScore?.let {
            appendLine("Driver score: ${format(it.total)}/100 (${it.samples} samples)")
            appendLine()
        }
        appendLine("## Diagnostics")
        if (report.diagnostics.isEmpty()) appendLine("No actionable diagnostics.")
        report.diagnostics.forEach { appendLine("- [${it.severity}] ${it.category}: ${it.message} (${it.evidence})") }
        appendLine()
        appendLine("## Regressions")
        if (report.regressions.isEmpty()) appendLine("No material regressions against the selected baseline.")
        report.regressions.forEach { appendLine("- ${it.metric}: +${format(it.percentRegression)}% regression [${it.severity}]") }
        appendLine()
        appendLine("## Tuning suggestions")
        if (report.tuningSuggestions.isEmpty()) appendLine("No tuning changes recommended.")
        report.tuningSuggestions.forEach {
            appendLine("- ${it.parameter}: ${it.recommendation} (confidence ${format(it.confidence * 100)}%, n=${it.evidenceSamples})")
        }
    }

    private fun compareSummaries(current: SessionSummary, baselines: List<SessionSummary>): SessionComparison? {
        if (baselines.isEmpty()) return null
        val definitions = listOf(
            MetricDefinition("minimum battery voltage", "V", current.minBatteryVoltage, false) { it.minBatteryVoltage },
            MetricDefinition("average loop time", "ms", current.avgLoopTimeMs, true) { it.avgLoopTimeMs },
            MetricDefinition("p95 loop time", "ms", current.p95LoopTimeMs, true) { it.p95LoopTimeMs },
            MetricDefinition("EKF drift", "m", current.maxEkfDrift, true) { it.maxEkfDrift },
            MetricDefinition("cross-track error", "m", current.avgCrossTrackError, true) { it.avgCrossTrackError },
            MetricDefinition("battery resistance", "ohm", current.avgBatteryResistance, true) { it.avgBatteryResistance },
            MetricDefinition("vision latency", "ms", current.avgVisionLatencyMs, true) { it.avgVisionLatencyMs },
            MetricDefinition("vision acceptance", "fraction", current.visionAcceptanceRate, false) { it.visionAcceptanceRate },
            MetricDefinition("run duration", "s", if (current.durationMs > 0) current.durationMs / 1000.0 else Double.NaN, true) { if (it.durationMs > 0) it.durationMs / 1000.0 else Double.NaN }
        )
        val metrics = definitions.mapNotNull { definition ->
            val values = baselines.map(definition.extract).filter { it.isFinite() && it != 0.0 }
            if (!definition.current.isFinite() || values.isEmpty()) return@mapNotNull null
            val baseline = values.average()
            MetricComparison(
                metric = definition.name,
                unit = definition.unit,
                current = definition.current,
                baselineAverage = baseline,
                percentChange = ((definition.current - baseline) / abs(baseline)) * 100.0,
                lowerIsBetter = definition.lowerIsBetter
            )
        }
        return SessionComparison(baselines.map { it.sessionId }, metrics)
    }

    private fun detectRegression(metric: MetricComparison): RegressionSignal? {
        val regression = if (metric.lowerIsBetter) metric.percentChange else -metric.percentChange
        if (!regression.isFinite() || regression < REGRESSION_WARNING_PERCENT) return null
        return RegressionSignal(
            metric.metric,
            regression,
            metric.current,
            metric.baselineAverage,
            if (regression >= REGRESSION_CRITICAL_PERCENT) InsightSeverity.CRITICAL else InsightSeverity.WARNING
        )
    }

    private suspend fun buildCorrelations(
        keys: List<String>,
        series: suspend (String) -> List<TelemetryFrame>
    ): List<SignalCorrelation> {
        val results = mutableListOf<SignalCorrelation>()
        val voltageKey = keys.firstOrNull { it.contains("BatteryVoltage", true) }
            ?: keys.firstOrNull { it.endsWith("/Voltage", true) }
        val currentKeys = keys.filter { it.endsWith("/CurrentAmps", true) || it.endsWith("/Current", true) }.take(MAX_MOTORS)
        for (currentKey in currentKeys) {
            val current = series(currentKey)
            val base = currentKey.substringBeforeLast('/')
            val velocityKey = keys.firstOrNull { it.equals("$base/Velocity", true) }
            if (velocityKey != null) correlation(currentKey, current, velocityKey, series(velocityKey))?.let(results::add)
            if (voltageKey != null) correlation(currentKey, current, voltageKey, series(voltageKey))?.let(results::add)
        }
        return results.sortedByDescending { abs(it.coefficient) }
    }

    private suspend fun buildDriverScore(
        keys: List<String>,
        series: suspend (String) -> List<TelemetryFrame>
    ): DriverPerformanceScore? {
        val input = driverInputSource(keys) ?: return null
        val paired = align(series(input.xKey), series(input.yKey))
        if (paired.size < MIN_CORRELATION_SAMPLES) return null
        val magnitudes = paired.map {
            hypot(it.first / input.fullScale, it.second / input.fullScale).coerceIn(0.0, 1.5)
        }
        val deltas = magnitudes.zipWithNext { left, right -> abs(right - left) }
        val smoothness = score100(1.0 - deltas.average().coerceIn(0.0, 1.0))
        val active = magnitudes.filter { it >= DRIVER_DEADBAND }
        val decisiveness = score100(active.size.toDouble() / magnitudes.size)
        val mean = magnitudes.average()
        val variance = magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size
        val consistency = score100(1.0 - sqrt(variance).coerceIn(0.0, 1.0))
        return DriverPerformanceScore(
            total = smoothness * 0.45 + decisiveness * 0.20 + consistency * 0.35,
            smoothness = smoothness,
            decisiveness = decisiveness,
            consistency = consistency,
            samples = paired.size
        )
    }

    private suspend fun buildPathHeatmap(
        keys: List<String>,
        series: suspend (String) -> List<TelemetryFrame>
    ): List<PathHeatmapCell> {
        val xKey = findKey(keys, "Drive/Pose_X", "ARES/EstimatedPose/0") ?: return emptyList()
        val yKey = findKey(keys, "Drive/Pose_Y", "ARES/EstimatedPose/1") ?: return emptyList()
        val paired = alignFrames(series(xKey), series(yKey))
        if (paired.isEmpty()) return emptyList()
        data class Accumulator(var visits: Int = 0, var speedTotal: Double = 0.0)
        val cells = HashMap<Pair<Int, Int>, Accumulator>()
        var previous: Pair<TelemetryFrame, TelemetryFrame>? = null
        paired.forEach { point ->
            val cell = floor(point.first.value / HEATMAP_CELL_METERS).toInt() to
                floor(point.second.value / HEATMAP_CELL_METERS).toInt()
            val accumulator = cells.getOrPut(cell) { Accumulator() }
            accumulator.visits++
            previous?.let { prior ->
                val dt = (point.first.timestampMs - prior.first.timestampMs) / 1_000.0
                if (dt > 0.0) accumulator.speedTotal += hypot(
                    point.first.value - prior.first.value,
                    point.second.value - prior.second.value
                ) / dt
            }
            previous = point
        }
        return cells.map { (cell, accumulator) ->
            PathHeatmapCell(cell.first, cell.second, accumulator.visits, accumulator.speedTotal / accumulator.visits)
        }.sortedByDescending { it.visits }
    }

    private fun buildDiagnostics(
        summary: SessionSummary?,
        regressions: List<RegressionSignal>,
        correlations: List<SignalCorrelation>,
        driver: DriverPerformanceScore?
    ): List<DiagnosticInsight> = buildList {
        if (summary != null && summary.minBatteryVoltage in 0.1..BATTERY_WARNING_VOLTS) add(
            DiagnosticInsight(InsightSeverity.WARNING, "power", "Battery sagged below the competition margin.", "minimum ${format(summary.minBatteryVoltage)} V")
        )
        if (summary != null && summary.p95LoopTimeMs > LOOP_WARNING_MS) add(
            DiagnosticInsight(InsightSeverity.CRITICAL, "control loop", "p95 loop time exceeds the real-time budget.", "p95 ${format(summary.p95LoopTimeMs)} ms")
        )
        if (summary != null && summary.avgBatteryResistance > BATTERY_RESISTANCE_WARNING_OHMS) add(
            DiagnosticInsight(InsightSeverity.WARNING, "battery health", "Average internal resistance is elevated.", "resistance ${format(summary.avgBatteryResistance)} ohm")
        )
        if (summary != null && summary.avgVisionLatencyMs > VISION_LATENCY_WARNING_MS) add(
            DiagnosticInsight(InsightSeverity.WARNING, "vision pipeline", "Camera processing latency is elevated.", "latency ${format(summary.avgVisionLatencyMs)} ms")
        )
        correlations.filter { it.leftTopic.contains("Current", true) && it.rightTopic.contains("Voltage", true) && it.coefficient < -0.65 }
            .take(3).forEach {
                add(DiagnosticInsight(InsightSeverity.WARNING, "electrical", "Current draw strongly tracks voltage sag.", "r=${format(it.coefficient)}, n=${it.samples}, ${it.leftTopic}"))
            }
        if (driver != null && driver.smoothness < 60.0) add(
            DiagnosticInsight(InsightSeverity.WARNING, "driver", "Drive input contains abrupt command changes.", "smoothness ${format(driver.smoothness)}/100")
        )
        regressions.take(5).forEach {
            add(DiagnosticInsight(it.severity, "regression", "${it.metric} regressed against baseline.", "+${format(it.percentRegression)}%"))
        }
    }

    private fun buildTuningSuggestions(
        summary: SessionSummary?,
        driver: DriverPerformanceScore?,
        correlations: List<SignalCorrelation>,
        baselineCount: Int
    ): List<TuningSuggestion> = buildList {
        if (summary != null && summary.avgCrossTrackError > CROSS_TRACK_WARNING_METERS) add(
            suggestion("path follower", "Review translation feedback gains and acceleration constraints.", summary.avgCrossTrackError, baselineCount, "average cross-track error ${format(summary.avgCrossTrackError)} m")
        )
        if (summary != null && summary.maxEkfDrift > EKF_WARNING_METERS) add(
            suggestion("pose estimator", "Recalibrate odometry scale and vision covariance before increasing controller gains.", summary.maxEkfDrift, baselineCount, "maximum EKF drift ${format(summary.maxEkfDrift)} m")
        )
        if (summary != null && summary.avgBatteryResistance > BATTERY_RESISTANCE_WARNING_OHMS) add(
            suggestion("battery maintenance", "Inspect terminal connections and cycle battery pack.", summary.avgBatteryResistance, baselineCount, "resistance ${format(summary.avgBatteryResistance)} ohm")
        )
        if (driver != null && driver.smoothness < 60.0) add(
            TuningSuggestion("driver shaping", "Increase center deadband exponent or reduce slew rate.", confidence(driver.samples, baselineCount), "input smoothness ${format(driver.smoothness)}/100", driver.samples)
        )
        val electrical = correlations.firstOrNull { it.rightTopic.contains("Voltage", true) && it.coefficient < -0.65 }
        if (electrical != null) add(
            TuningSuggestion("current limits", "Inspect mechanical load and reduce acceleration/current limits for the implicated motor.", confidence(electrical.samples, baselineCount), "current/voltage correlation r=${format(electrical.coefficient)}", electrical.samples)
        )
    }.sortedByDescending { it.confidence }

    private fun suggestion(parameter: String, recommendation: String, evidenceValue: Double, baselines: Int, rationale: String) =
        TuningSuggestion(parameter, recommendation, confidence((evidenceValue * 1_000).toInt(), baselines), rationale, (evidenceValue * 1_000).toInt())

    private fun confidence(samples: Int, baselineCount: Int): Double =
        (0.35 + (samples.coerceAtMost(500) / 500.0) * 0.45 + baselineCount.coerceAtMost(4) * 0.05).coerceIn(0.0, 0.95)

    private fun correlation(
        leftKey: String,
        left: List<TelemetryFrame>,
        rightKey: String,
        right: List<TelemetryFrame>
    ): SignalCorrelation? {
        val samples = align(left, right)
        if (samples.size < MIN_CORRELATION_SAMPLES) return null
        val leftMean = samples.sumOf { it.first } / samples.size
        val rightMean = samples.sumOf { it.second } / samples.size
        var covariance = 0.0
        var leftVariance = 0.0
        var rightVariance = 0.0
        samples.forEach { (x, y) ->
            val dx = x - leftMean
            val dy = y - rightMean
            covariance += dx * dy
            leftVariance += dx * dx
            rightVariance += dy * dy
        }
        val denominator = sqrt(leftVariance * rightVariance)
        if (denominator <= 1e-12) return null
        return SignalCorrelation(leftKey, rightKey, (covariance / denominator).coerceIn(-1.0, 1.0), samples.size)
    }

    private fun align(left: List<TelemetryFrame>, right: List<TelemetryFrame>): List<Pair<Double, Double>> =
        alignFrames(left, right).map { it.first.value to it.second.value }

    private fun alignFrames(left: List<TelemetryFrame>, right: List<TelemetryFrame>): List<Pair<TelemetryFrame, TelemetryFrame>> {
        if (left.isEmpty() || right.isEmpty()) return emptyList()
        val result = ArrayList<Pair<TelemetryFrame, TelemetryFrame>>(minOf(left.size, right.size))
        var rightIndex = 0
        for (leftFrame in left) {
            while (rightIndex + 1 < right.size && right[rightIndex + 1].timestampMs <= leftFrame.timestampMs) rightIndex++
            val candidates = listOfNotNull(right.getOrNull(rightIndex), right.getOrNull(rightIndex + 1))
            val match = candidates.minByOrNull { abs(it.timestampMs - leftFrame.timestampMs) } ?: continue
            if (abs(match.timestampMs - leftFrame.timestampMs) <= MAX_ALIGNMENT_GAP_MS) result.add(leftFrame to match)
        }
        return result
    }

    private fun findKey(keys: List<String>, vararg candidates: String): String? =
        candidates.firstNotNullOfOrNull { candidate -> keys.firstOrNull { it.equals(candidate, ignoreCase = true) } }

    private fun driverInputSource(keys: List<String>): DriverInputSource? = listOf(
        DriverInputSource("Gamepad1/LeftStickX", "Gamepad1/LeftStickY", 1.0),
        DriverInputSource("Gamepad1/LeftX", "Gamepad1/LeftY", 1.0),
        DriverInputSource(
            "ARES/Input/driveFrame/4",
            "ARES/Input/driveFrame/5",
            DASHBOARD_DRIVE_FULL_SCALE_METERS_PER_SECOND
        )
    ).firstNotNullOfOrNull { candidate ->
        val xKey = findKey(keys, candidate.xKey) ?: return@firstNotNullOfOrNull null
        val yKey = findKey(keys, candidate.yKey) ?: return@firstNotNullOfOrNull null
        candidate.copy(xKey = xKey, yKey = yKey)
    }

    private fun score100(fraction: Double): Double = (fraction.coerceIn(0.0, 1.0) * 100.0)
    private fun format(value: Double): String = "%.2f".format(java.util.Locale.US, value)

    private data class MetricDefinition(
        val name: String,
        val unit: String,
        val current: Double,
        val lowerIsBetter: Boolean,
        val extract: (SessionSummary) -> Double
    )

    private data class DriverInputSource(
        val xKey: String,
        val yKey: String,
        val fullScale: Double
    )

    private companion object {
        const val MAX_SIGNAL_POINTS = 5_000
        const val MAX_MOTORS = 16
        const val MIN_CORRELATION_SAMPLES = 10
        const val MAX_ALIGNMENT_GAP_MS = 100L
        const val REGRESSION_WARNING_PERCENT = 10.0
        const val REGRESSION_CRITICAL_PERCENT = 25.0
        const val DRIVER_DEADBAND = 0.08
        const val DASHBOARD_DRIVE_FULL_SCALE_METERS_PER_SECOND = 4.0
        const val HEATMAP_CELL_METERS = 0.5
        const val BATTERY_WARNING_VOLTS = 10.5
        const val LOOP_WARNING_MS = 20.0
        const val CROSS_TRACK_WARNING_METERS = 0.25
        const val EKF_WARNING_METERS = 0.30
        const val BATTERY_RESISTANCE_WARNING_OHMS = 0.050
        const val VISION_LATENCY_WARNING_MS = 100.0
    }
}

private suspend fun <K, V> MutableMap<K, V>.getOrPutSuspend(key: K, block: suspend () -> V): V {
    this[key]?.let { return it }
    return block().also { this[key] = it }
}
