package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame
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
    /** Distance/time of observed segments ending in this cell; null when no interval exists. */
    val averageSpeedMetersPerSecond: Double?
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
    val evidenceSamples: Int,
    val evidenceUnit: String = "aligned samples",
) {
    /** Legacy confidence is a bounded support heuristic, never a statistical probability. */
    val evidenceStrength: Double get() = confidence
    val evidenceLabel: String get() = "$evidenceSamples $evidenceUnit"
}

/**
 * Produces a bounded, evidence-carrying analysis report for one recorded session. Every raw
 * signal query is viewport/downsample limited so report generation remains stable for long logs.
 */
class AdvancedAnalyticsService(private val databaseService: TelemetryAnalyticsRepository) {
    suspend fun analyzeAgainstRecent(sessionId: String, baselineCount: Int = 3): OperationResult<AdvancedAnalyticsReport> =
        safely(sessionId) { readReport(sessionId, emptyList(), baselineCount.coerceAtLeast(0), true) }

    suspend fun analyzeSafely(sessionId: String, baselineSessionIds: List<String> = emptyList()): OperationResult<AdvancedAnalyticsReport> =
        safely(sessionId) { readReport(sessionId, baselineSessionIds, null, true) }

    suspend fun analyze(sessionId: String, baselineSessionIds: List<String> = emptyList()): AdvancedAnalyticsReport =
        requireNotNull(readReport(sessionId, baselineSessionIds, null, false))

    private suspend fun safely(sessionId: String, read: suspend () -> AdvancedAnalyticsReport?): OperationResult<AdvancedAnalyticsReport> = try {
        read()?.let { OperationResult.Success(it) }
            ?: OperationResult.Unavailable("NO_TELEMETRY", "Session $sessionId has no telemetry frames")
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        OperationResult.Failure("ANALYTICS_FAILED", error.message ?: "Analytics failed", error)
    }

    private suspend fun readReport(
        sessionId: String,
        baselineSessionIds: List<String>,
        recentCount: Int?,
        requireTelemetry: Boolean,
    ): AdvancedAnalyticsReport? = withContext(Dispatchers.Default) {
        val range = databaseService.getSessionTimestampRange(sessionId)
        if (range == null && requireTelemetry) return@withContext null
        val summary = databaseService.getSessionSummary(sessionId)
        fun compatible(other: SessionSummary): Boolean = summary != null && other.sessionId != sessionId &&
            other.teamId == summary.teamId && other.seasonId == summary.seasonId && other.robotId == summary.robotId
        val baselines = when {
            summary == null || recentCount == 0 -> emptyList()
            recentCount != null -> databaseService.getAllSessionSummaries().asSequence()
                .filter(::compatible).distinctBy { it.sessionId }.sortedByDescending { it.createdAt }.take(recentCount).toList()
            else -> baselineSessionIds.distinct().filter { it != sessionId }
                .mapNotNull { databaseService.getSessionSummary(it) }.filter(::compatible)
        }
        val comparison = summary?.let { compareSummaries(it, baselines) }
        val regressions = comparison?.metrics.orEmpty().mapNotNull(::detectRegression).sortedByDescending { it.percentRegression }
        if (range == null) return@withContext AdvancedAnalyticsReport(
            sessionId, comparison, regressions, emptyList(), null, emptyList(),
            listOf(DiagnosticInsight(InsightSeverity.WARNING, "data", "No telemetry frames were recorded.", "Session range is empty")), emptyList()
        )
        val keys = databaseService.getDistinctTelemetryKeys(sessionId).distinct().sorted()
        val seriesCache = HashMap<String, List<TelemetryFrame>>()
        suspend fun series(key: String): List<TelemetryFrame> = seriesCache.getOrPutSuspend(key) {
            val frames = databaseService.getTelemetrySeries(sessionId, key, range.first, range.second, MAX_SIGNAL_POINTS)
            require(frames.size <= MAX_SIGNAL_POINTS) { "Analytics signal query exceeded its point limit" }
            numericAnalyticsSeries(frames, sessionId, key)
        }
        val correlations = buildCorrelations(keys, ::series)
        val driver = buildDriverScore(keys, ::series)
        val heatmap = buildPathHeatmap(keys, ::series)
        AdvancedAnalyticsReport(sessionId, comparison, regressions, correlations, driver, heatmap,
            buildDiagnostics(summary, regressions, correlations, driver),
            buildTuningSuggestions(summary, driver, correlations))
    }

    fun renderDiagnosticMarkdown(report: AdvancedAnalyticsReport): String = buildString {
        appendLine("# ARES analytics report: ${report.sessionId}")
        appendLine()
        report.driverScore?.let {
            appendLine("Command-pattern score: ${format(it.total)}/100 (${it.samples} retained samples; heuristic, not driver skill)")
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
            appendLine("- ${it.parameter}: ${it.recommendation} (evidence score ${format(it.evidenceStrength * 100)}/100; ${it.evidenceLabel})")
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
            // Legacy summaries use zero for both missing and genuine zero-valued metrics.
            // Without presence metadata, neither side may treat an ambiguous zero as improvement.
            fun valid(value: Double) = value.isFinite() && value > 0.0 && (definition.unit != "fraction" || value <= 1.0)
            val values = baselines.map(definition.extract).filter(::valid)
            if (!valid(definition.current) || values.isEmpty()) return@mapNotNull null
            val largest = values.max()
            val baseline = (values.sumOf { it / largest } / values.size).coerceIn(0.0, 1.0) * largest
            val percent = ((definition.current - baseline) / baseline) * 100.0
            if (!baseline.isFinite() || !percent.isFinite()) return@mapNotNull null
            MetricComparison(
                metric = definition.name,
                unit = definition.unit,
                current = definition.current,
                baselineAverage = baseline,
                percentChange = percent,
                lowerIsBetter = definition.lowerIsBetter
            )
        }
        return if (metrics.isEmpty()) null else SessionComparison(baselines.map { it.sessionId }, metrics)
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
        val voltageKey = findKey(keys, *TelemetryMetricCatalog.BATTERY_VOLTAGE.keys.toTypedArray())
        val currentKeys = keys.filter {
            MOTOR_CURRENT_TOPIC.matches(it.trimStart('/')) || DRIVE_CURRENT_TOPIC.matches(it.trimStart('/'))
        }.take(MAX_MOTORS)
        for (currentKey in currentKeys) {
            val current = series(currentKey)
            val normalized = currentKey.trimStart('/')
            val velocityTopic = if (DRIVE_CURRENT_TOPIC.matches(normalized))
                "Drive/MotorVelocity_${normalized.substringAfter('_')}"
            else "${normalized.substringBeforeLast('/')}/Velocity"
            val velocityKey = findKey(keys, velocityTopic)
            if (velocityKey != null) correlation(currentKey, current, velocityKey, series(velocityKey))?.let(results::add)
            if (voltageKey != null) correlation(currentKey, current, voltageKey, series(voltageKey))?.let(results::add)
        }
        return results.sortedByDescending { abs(it.coefficient) }
    }

    private suspend fun buildDriverScore(
        keys: List<String>,
        series: suspend (String) -> List<TelemetryFrame>,
    ): DriverPerformanceScore? {
        val input = driverInputSource(keys) ?: return null
        val paired = alignAnalyticsSeries(series(input.xKey), series(input.yKey)).filter {
            abs(it.first.value) <= input.fullScale && abs(it.second.value) <= input.fullScale
        }
        if (paired.size < MIN_CORRELATION_SAMPLES) return null
        var totalVariation = 0.0; var observedSeconds = 0.0; var intervals = 0
        var active = 0; var mean = 0.0; var squaredDeviations = 0.0
        var previousX = 0.0; var previousY = 0.0; var previousTime = 0L
        paired.forEachIndexed { index, point ->
            val x = point.first.value / input.fullScale; val y = point.second.value / input.fullScale
            val magnitude = hypot(x, y)
            if (magnitude >= DRIVER_DEADBAND) active++
            val delta = magnitude - mean
            mean += delta / (index + 1); squaredDeviations += delta * (magnitude - mean)
            val time = point.first.timestampUs
            val elapsed = time - previousTime
            if (index > 0 && elapsed in 1L..MAX_DRIVER_INTERVAL_US) {
                totalVariation += hypot(x - previousX, y - previousY)
                observedSeconds += elapsed / 1_000_000.0
                intervals++
            }
            previousX = x; previousY = y; previousTime = time
        }
        if (intervals < MIN_CORRELATION_SAMPLES - 1) return null
        // Average vector variation rate at a fixed 50ms reference, rather than changes per recorded sample.
        val smoothness = score100(1.0 - (totalVariation / observedSeconds * 0.05).coerceIn(0.0, 1.0))
        val activity = score100(active.toDouble() / paired.size)
        val consistency = score100(1.0 - sqrt((squaredDeviations / paired.size).coerceAtLeast(0.0)).coerceIn(0.0, 1.0))
        return DriverPerformanceScore(smoothness * 0.45 + activity * 0.20 + consistency * 0.35,
            smoothness, activity, consistency, paired.size)
    }

    private suspend fun buildPathHeatmap(
        keys: List<String>,
        series: suspend (String) -> List<TelemetryFrame>,
    ): List<PathHeatmapCell> {
        val pair = listOf("ARES/SimulatorPoseFrame/3" to "ARES/SimulatorPoseFrame/4",
            "ARES/EstimatedPose/0" to "ARES/EstimatedPose/1", "Drive/Pose_X" to "Drive/Pose_Y")
            .firstNotNullOfOrNull { (x, y) ->
                val actualX = findKey(keys, x); val actualY = findKey(keys, y)
                if (actualX != null && actualY != null) actualX to actualY else null
            } ?: return emptyList()
        val paired = alignAnalyticsSeries(series(pair.first), series(pair.second))
        data class Accumulator(var visits: Int = 0, var distance: Double = 0.0, var seconds: Double = 0.0)
        val cells = HashMap<Pair<Int, Int>, Accumulator>()
        var previous: Pair<TelemetryFrame, TelemetryFrame>? = null
        for (point in paired) {
            val xCell = floor(point.first.value / HEATMAP_CELL_METERS)
            val yCell = floor(point.second.value / HEATMAP_CELL_METERS)
            if (xCell !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() || yCell !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                previous = null
                continue
            }
            val accumulator = cells.getOrPut(xCell.toInt() to yCell.toInt()) { Accumulator() }
            accumulator.visits++
            previous?.let { prior ->
                val dt = (point.first.timestampUs - prior.first.timestampUs) / 1_000_000.0
                if (dt > 0.0) {
                    accumulator.distance += hypot(point.first.value - prior.first.value, point.second.value - prior.second.value)
                    accumulator.seconds += dt
                }
            }
            previous = point
        }
        return cells.map { (cell, accumulator) -> PathHeatmapCell(cell.first, cell.second, accumulator.visits,
            if (accumulator.seconds > 0.0) accumulator.distance / accumulator.seconds else null)
        }.sortedWith(compareByDescending<PathHeatmapCell> { it.visits }.thenBy { it.xIndex }.thenBy { it.yIndex })
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
        if (summary != null && summary.p95LoopTimeMs.isFinite() && summary.p95LoopTimeMs > LOOP_WARNING_MS) add(
            DiagnosticInsight(InsightSeverity.CRITICAL, "control loop", "p95 loop time exceeds the real-time budget.", "p95 ${format(summary.p95LoopTimeMs)} ms")
        )
        if (summary != null && summary.avgBatteryResistance.isFinite() && summary.avgBatteryResistance > BATTERY_RESISTANCE_WARNING_OHMS) add(
            DiagnosticInsight(InsightSeverity.WARNING, "battery health", "Average internal resistance is elevated.", "resistance ${format(summary.avgBatteryResistance)} ohm")
        )
        if (summary != null && summary.avgVisionLatencyMs.isFinite() && summary.avgVisionLatencyMs > VISION_LATENCY_WARNING_MS) add(
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
    ): List<TuningSuggestion> = buildList {
        if (summary != null && summary.avgCrossTrackError.isFinite() && summary.avgCrossTrackError > CROSS_TRACK_WARNING_METERS) add(
            suggestion("path follower", "Review translation feedback gains and acceleration constraints.", "average cross-track error ${format(summary.avgCrossTrackError)} m")
        )
        if (summary != null && summary.maxEkfDrift.isFinite() && summary.maxEkfDrift > EKF_WARNING_METERS) add(
            suggestion("pose estimator", "Recalibrate odometry scale and vision covariance before increasing controller gains.", "maximum EKF drift ${format(summary.maxEkfDrift)} m")
        )
        if (summary != null && summary.avgBatteryResistance.isFinite() && summary.avgBatteryResistance > BATTERY_RESISTANCE_WARNING_OHMS) add(
            suggestion("battery maintenance", "Inspect terminal connections and cycle battery pack.", "resistance ${format(summary.avgBatteryResistance)} ohm")
        )
        if (driver != null && driver.smoothness < 60.0) add(
            TuningSuggestion("driver shaping", "Review input shaping and slew-rate limits.", evidenceStrength(driver.samples), "input smoothness ${format(driver.smoothness)}/100", driver.samples)
        )
        val electrical = correlations.firstOrNull { it.rightTopic.contains("Voltage", true) && it.coefficient < -0.65 }
        if (electrical != null) add(
            TuningSuggestion("current limits", "Inspect mechanical load and acceleration/current limits for the implicated motor.", evidenceStrength(electrical.samples), "current/voltage correlation r=${format(electrical.coefficient)}; association does not establish cause", electrical.samples)
        )
    }.sortedByDescending { it.confidence }

    private fun suggestion(parameter: String, recommendation: String, rationale: String) =
        TuningSuggestion(parameter, recommendation, 0.35,
            "$rationale; raw sample count is unavailable in the session summary", 1, "summary statistic")

    private fun evidenceStrength(samples: Int): Double =
        0.35 + (samples.coerceIn(0, 500) / 500.0) * 0.45

    private fun correlation(
        leftKey: String, left: List<TelemetryFrame>, rightKey: String, right: List<TelemetryFrame>,
    ): SignalCorrelation? {
        val samples = alignAnalyticsSeries(left, right, MAX_ALIGNMENT_GAP_US)
        if (samples.size < MIN_CORRELATION_SAMPLES) return null
        val coefficient = analyticsCorrelation(samples) ?: return null
        return SignalCorrelation(leftKey, rightKey, coefficient, samples.size)
    }

    private fun findKey(keys: List<String>, vararg candidates: String): String? =
        candidates.firstNotNullOfOrNull { candidate -> keys.firstOrNull { it.trimStart('/').equals(candidate, ignoreCase = true) } }

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
        val MOTOR_CURRENT_TOPIC = Regex("^Hardware/Motors/[^/]+/(CurrentAmps|Current)$", RegexOption.IGNORE_CASE)
        val DRIVE_CURRENT_TOPIC = Regex("^Drive/MotorCurrent_[^/]+$", RegexOption.IGNORE_CASE)
        const val MAX_SIGNAL_POINTS = 5_000
        const val MAX_MOTORS = 16
        const val MIN_CORRELATION_SAMPLES = 10
        const val MAX_ALIGNMENT_GAP_US = 100_000L
        const val MAX_DRIVER_INTERVAL_US = 250_000L
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
