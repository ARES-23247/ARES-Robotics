package com.ares.analytics.domain.analysis

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pure statistical distribution & two-cohort comparison engine across workspace runs.
 *
 * Computes exact Tukey/linear-interpolated quartiles (`Min`, `Q1`, `Median`, `Q3`, `Max`, `IQR`),
 * `1.5 * IQR` outlier session detection, sample mean & standard deviation, Welch's two-sample
 * t-statistic, and pooled Cohen's d effect magnitude without claiming causation.
 */
object CohortStatisticsAnalyzer {

    fun defaultMetrics(motorNames: List<String> = emptyList()): List<CohortMetricSpec> {
        val core = listOf(
            CohortMetricSpec(
                id = "min_battery_v",
                label = "Min Battery Voltage",
                unit = "V",
                lowerIsBetter = false,
                decimals = 2,
                extract = { _, s, _ -> s?.minBatteryVoltage?.takeIf { it.isFinite() && it > 0.0 } },
            ),
            CohortMetricSpec(
                id = "battery_resistance_mohm",
                label = "Battery Internal Resistance",
                unit = "mΩ",
                lowerIsBetter = true,
                decimals = 1,
                extract = { _, s, _ -> s?.avgBatteryResistance?.takeIf { it.isFinite() && it > 0.0 }?.times(1000.0) },
            ),
            CohortMetricSpec(
                id = "avg_loop_ms",
                label = "Avg Loop Time",
                unit = "ms",
                lowerIsBetter = true,
                decimals = 2,
                extract = { _, s, _ -> s?.avgLoopTimeMs?.takeIf { it.isFinite() && it > 0.0 } },
            ),
            CohortMetricSpec(
                id = "p95_loop_ms",
                label = "P95 Loop Time",
                unit = "ms",
                lowerIsBetter = true,
                decimals = 2,
                extract = { _, s, _ -> s?.p95LoopTimeMs?.takeIf { it.isFinite() && it > 0.0 } },
            ),
            CohortMetricSpec(
                id = "cross_track_cm",
                label = "Avg Cross-Track Error",
                unit = "cm",
                lowerIsBetter = true,
                decimals = 1,
                extract = { _, s, _ -> s?.avgCrossTrackError?.takeIf { it.isFinite() && it >= 0.0 }?.times(100.0) },
            ),
            CohortMetricSpec(
                id = "max_ekf_drift_m",
                label = "Max EKF Drift",
                unit = "m",
                lowerIsBetter = true,
                decimals = 3,
                extract = { _, s, _ -> s?.maxEkfDrift?.takeIf { it.isFinite() && it >= 0.0 } },
            ),
            CohortMetricSpec(
                id = "vision_acceptance_pct",
                label = "Vision Acceptance Rate",
                unit = "%",
                lowerIsBetter = false,
                decimals = 1,
                extract = { _, s, _ -> s?.visionAcceptanceRate?.takeIf { it.isFinite() && it in 0.0..1.0 }?.times(100.0) },
            ),
            CohortMetricSpec(
                id = "vision_latency_ms",
                label = "Avg Vision Latency",
                unit = "ms",
                lowerIsBetter = true,
                decimals = 1,
                extract = { _, s, _ -> s?.avgVisionLatencyMs?.takeIf { it.isFinite() && it >= 0.0 } },
            ),
            CohortMetricSpec(
                id = "duration_s",
                label = "Run Duration",
                unit = "s",
                lowerIsBetter = null,
                decimals = 1,
                extract = { session, summary, _ ->
                    val ms = summary?.durationMs?.takeIf { it > 0L } ?: session.durationMs.takeIf { it > 0L }
                    ms?.toDouble()?.div(1000.0)
                },
            ),
            CohortMetricSpec(
                id = "can_util_pct",
                label = "Max CAN Bus Utilization",
                unit = "%",
                lowerIsBetter = true,
                decimals = 1,
                extract = { _, _, diag ->
                    diag["Diagnostics/System/MaxCANBusUtilization"]
                        ?.takeIf { it.isFinite() && it in 0.0..1.0 }
                        ?.times(100.0)
                },
            ),
        )
        val motors = motorNames.distinct().sorted().map { motor ->
            CohortMetricSpec(
                id = "motor_current_$motor",
                label = "Motor [$motor] Avg Current",
                unit = "A",
                lowerIsBetter = true,
                decimals = 2,
                extract = { _, s, _ -> s?.motorCurrentAverages?.get(motor)?.takeIf { it.isFinite() && it >= 0.0 } },
            )
        }
        return core + motors
    }

    fun discoverTags(sessions: List<Session>): List<String> = sessions
        .flatMap { it.tags }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase(Locale.US) }
        .sortedBy { it.lowercase(Locale.US) }

    fun partitionSessions(
        sessions: List<Session>,
        mode: CohortSplitMode,
        primaryTag: String = "",
        secondaryTag: String = "",
    ): Pair<Pair<String, List<Session>>, Pair<String, List<Session>>> {
        val ordered = sessions.sortedBy(Session::createdAt)
        return when (mode) {
            CohortSplitMode.CHRONOLOGICAL_HALVES -> {
                if (ordered.isEmpty()) {
                    ("Early Runs" to emptyList<Session>()) to ("Recent Runs" to emptyList())
                } else {
                    val splitIdx = (ordered.size / 2).coerceAtLeast(1).coerceAtMost(ordered.size)
                    val early = ordered.subList(0, splitIdx)
                    val recent = ordered.subList(splitIdx, ordered.size)
                    ("Early Runs (n=${early.size})" to early) to ("Recent Runs (n=${recent.size})" to recent)
                }
            }
            CohortSplitMode.TAG_VS_TAG -> {
                val tagA = primaryTag.trim()
                val tagB = secondaryTag.trim()
                val groupA = if (tagA.isEmpty()) emptyList() else ordered.filter { s ->
                    s.tags.any { it.equals(tagA, ignoreCase = true) }
                }
                val groupB = if (tagB.isEmpty()) emptyList() else ordered.filter { s ->
                    s.tags.any { it.equals(tagB, ignoreCase = true) }
                }
                val labelA = if (tagA.isEmpty()) "Tag A (unset)" else "Tag: $tagA"
                val labelB = if (tagB.isEmpty()) "Tag B (unset)" else "Tag: $tagB"
                (labelA to groupA) to (labelB to groupB)
            }
            CohortSplitMode.TAG_VS_REST -> {
                val tag = primaryTag.trim()
                if (tag.isEmpty()) {
                    ("Other Runs" to ordered) to ("Tagged (unset)" to emptyList())
                } else {
                    val matching = ordered.filter { s -> s.tags.any { it.equals(tag, ignoreCase = true) } }
                    val rest = ordered.filterNot { s -> s.tags.any { it.equals(tag, ignoreCase = true) } }
                    // Put "Other Runs" as Baseline (A) and "Tag: <tag>" as Candidate (B)
                    ("Untagged / Other" to rest) to ("Tag: $tag" to matching)
                }
            }
            CohortSplitMode.ALLIANCE_RED_VS_BLUE -> {
                val red = ordered.filter { it.allianceColor?.equals("red", ignoreCase = true) == true }
                val blue = ordered.filter { it.allianceColor?.equals("blue", ignoreCase = true) == true }
                ("Red Alliance" to red) to ("Blue Alliance" to blue)
            }
        }
    }

    fun summarizeCohort(
        cohortName: String,
        sessions: List<Session>,
        summariesById: Map<String, SessionSummary>,
        diagnosticsById: Map<String, Map<String, Double>>,
        metric: CohortMetricSpec,
    ): CohortSummaryStats? {
        val rawSamples = sessions.mapNotNull { session ->
            val summary = summariesById[session.sessionId]
            val diag = diagnosticsById[session.sessionId].orEmpty()
            val value = metric.extract(session, summary, diag)?.takeIf { it.isFinite() } ?: return@mapNotNull null
            Triple(session, sessionShortLabel(session), value)
        }
        if (rawSamples.isEmpty()) return null

        val sortedValues = rawSamples.map { it.third }.sorted()
        val n = sortedValues.size
        val minVal = sortedValues.first()
        val maxVal = sortedValues.last()
        val q1 = percentileLinear(sortedValues, 0.25)
        val median = percentileLinear(sortedValues, 0.50)
        val q3 = percentileLinear(sortedValues, 0.75)
        val iqr = (q3 - q1).coerceAtLeast(0.0)
        val lowerFence = q1 - 1.5 * iqr
        val upperFence = q3 + 1.5 * iqr

        val mean = sortedValues.sum() / n.toDouble()
        val variance = if (n > 1) {
            sortedValues.sumOf { (it - mean) * (it - mean) } / (n - 1).toDouble()
        } else {
            0.0
        }
        val stdDev = sqrt(variance.coerceAtLeast(0.0))

        val points = rawSamples.map { (session, label, value) ->
            val isOutlier = n >= 4 && iqr > 1e-9 && (value < lowerFence || value > upperFence)
            CohortSamplePoint(
                sessionId = session.sessionId,
                sessionLabel = label,
                createdAtMs = session.createdAt,
                value = value,
                isOutlier = isOutlier,
            )
        }

        return CohortSummaryStats(
            cohortName = cohortName,
            count = n,
            min = minVal,
            q1 = q1,
            median = median,
            q3 = q3,
            max = maxVal,
            iqr = iqr,
            mean = mean,
            stdDev = stdDev,
            outlierSessionIds = points.filter(CohortSamplePoint::isOutlier).map(CohortSamplePoint::sessionId),
            samples = points,
        )
    }

    fun compareCohorts(
        sessions: List<Session>,
        summariesById: Map<String, SessionSummary>,
        diagnosticsById: Map<String, Map<String, Double>> = emptyMap(),
        metric: CohortMetricSpec,
        mode: CohortSplitMode,
        primaryTag: String = "",
        secondaryTag: String = "",
    ): CohortComparisonResult {
        val (pairA, pairB) = partitionSessions(sessions, mode, primaryTag, secondaryTag)
        val statsA = summarizeCohort(pairA.first, pairA.second, summariesById, diagnosticsById, metric)
        val statsB = summarizeCohort(pairB.first, pairB.second, summariesById, diagnosticsById, metric)

        val medianDelta = if (statsA != null && statsB != null) statsB.median - statsA.median else null
        val meanDelta = if (statsA != null && statsB != null) statsB.mean - statsA.mean else null
        val pctDelta = if (medianDelta != null && statsA != null && abs(statsA.median) > 1e-9) {
            (medianDelta / abs(statsA.median)) * 100.0
        } else {
            null
        }

        val welchT = computeWelchT(statsA, statsB)
        val cohensD = computeCohensD(statsA, statsB)
        val effectLabel = classifyEffectSize(cohensD, statsA, statsB)

        val improved = when {
            medianDelta == null || metric.lowerIsBetter == null -> null
            abs(medianDelta) <= 1e-9 -> null
            metric.lowerIsBetter -> medianDelta < 0.0
            else -> medianDelta > 0.0
        }

        val splitDesc = "${pairA.first} vs. ${pairB.first}"
        val takeaway = buildPlainEnglishTakeaway(metric, statsA, statsB, medianDelta, pctDelta, effectLabel, improved)
        val markdown = buildMarkdownEvidenceBlock(metric, splitDesc, statsA, statsB, medianDelta, pctDelta, welchT, cohensD, effectLabel, takeaway)

        return CohortComparisonResult(
            metricId = metric.id,
            metricLabel = metric.label,
            unit = metric.unit,
            lowerIsBetter = metric.lowerIsBetter,
            splitMode = mode,
            splitDescription = splitDesc,
            cohortA = statsA,
            cohortB = statsB,
            medianDelta = medianDelta,
            meanDelta = meanDelta,
            percentMedianDelta = pctDelta,
            welchT = welchT,
            cohensD = cohensD,
            effectMagnitudeLabel = effectLabel,
            improved = improved,
            plainEnglishTakeaway = takeaway,
            markdownEvidenceBlock = markdown,
        )
    }

    internal fun percentileLinear(sorted: List<Double>, p: Double): Double {
        require(sorted.isNotEmpty()) { "Cannot compute percentile of empty list" }
        if (sorted.size == 1) return sorted[0]
        val clamped = p.coerceIn(0.0, 1.0)
        val pos = clamped * (sorted.size - 1)
        val lower = floor(pos).toInt().coerceIn(0, sorted.lastIndex)
        val upper = (lower + 1).coerceIn(0, sorted.lastIndex)
        val weight = pos - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    private fun computeWelchT(a: CohortSummaryStats?, b: CohortSummaryStats?): Double? {
        if (a == null || b == null || a.count < 2 || b.count < 2) return null
        val se = hypot(a.stdDev / sqrt(a.count.toDouble()), b.stdDev / sqrt(b.count.toDouble()))
        if (se <= 1e-12) return null
        return (b.mean - a.mean) / se
    }

    private fun computeCohensD(a: CohortSummaryStats?, b: CohortSummaryStats?): Double? {
        if (a == null || b == null || a.count < 2 || b.count < 2) return null
        val df = a.count + b.count - 2
        if (df <= 0) return null
        val pooledVar = (((a.count - 1) * a.stdDev * a.stdDev) + ((b.count - 1) * b.stdDev * b.stdDev)) / df.toDouble()
        val pooledSd = sqrt(pooledVar.coerceAtLeast(0.0))
        if (pooledSd <= 1e-12) return null
        return (b.mean - a.mean) / pooledSd
    }

    private fun classifyEffectSize(d: Double?, a: CohortSummaryStats?, b: CohortSummaryStats?): String {
        if (a == null || b == null) return "Insufficient cohort data"
        if (a.count < 2 || b.count < 2 || d == null) return "Small sample (nA=${a.count}, nB=${b.count})"
        val mag = abs(d)
        return when {
            mag < 0.20 -> "Negligible shift (|d|=${fmt(mag, 2)})"
            mag < 0.50 -> "Small shift (|d|=${fmt(mag, 2)})"
            mag < 0.80 -> "Moderate shift (|d|=${fmt(mag, 2)})"
            else -> "Large shift (|d|=${fmt(mag, 2)})"
        }
    }

    private fun buildPlainEnglishTakeaway(
        metric: CohortMetricSpec,
        a: CohortSummaryStats?,
        b: CohortSummaryStats?,
        medianDelta: Double?,
        pctDelta: Double?,
        effectLabel: String,
        improved: Boolean?,
    ): String {
        if (a == null || b == null || medianDelta == null) {
            return "Select two non-empty run cohorts with recorded ${metric.label} measurements to compare distributions."
        }
        val pctPart = pctDelta?.let { " (${fmtSigned(it, 1)}%)" }.orEmpty()
        val directionWord = when (improved) {
            true -> "favorable shift"
            false -> "unfavorable shift"
            null -> "observed difference"
        }
        return "Across ${a.count} vs. ${b.count} runs, median ${metric.label} moved from " +
            "${fmt(a.median, metric.decimals)} ${metric.unit} to ${fmt(b.median, metric.decimals)} ${metric.unit} " +
            "(${fmtSigned(medianDelta, metric.decimals)} ${metric.unit}$pctPart — $directionWord, $effectLabel). " +
            "Observational cohort statistics summarize recorded runs and do not prove causation."
    }

    private fun buildMarkdownEvidenceBlock(
        metric: CohortMetricSpec,
        splitDesc: String,
        a: CohortSummaryStats?,
        b: CohortSummaryStats?,
        medianDelta: Double?,
        pctDelta: Double?,
        welchT: Double?,
        cohensD: Double?,
        effectLabel: String,
        takeaway: String,
    ): String = buildString {
        appendLine("## Multi-Run Cohort Distribution: ${metric.label} (${metric.unit})")
        appendLine()
        appendLine("- **Cohort Split**: $splitDesc")
        appendLine("- **Summary**: $takeaway")
        if (a != null && b != null) {
            appendLine()
            appendLine("| Cohort | Runs (n) | Min | Q1 (P25) | Median (P50) | Q3 (P75) | Max | IQR | Mean ± SD | Outliers |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            listOf(a, b).forEach { s ->
                val u = metric.unit
                val d = metric.decimals
                val outliers = if (s.outlierSessionIds.isEmpty()) "None" else s.outlierSessionIds.joinToString(", ") { "`${it.take(8)}`" }
                appendLine(
                    "| ${s.cohortName} | ${s.count} | ${fmt(s.min, d)} $u | ${fmt(s.q1, d)} $u | " +
                        "**${fmt(s.median, d)} $u** | ${fmt(s.q3, d)} $u | ${fmt(s.max, d)} $u | " +
                        "${fmt(s.iqr, d)} $u | ${fmt(s.mean, d)} ± ${fmt(s.stdDev, d)} $u | $outliers |"
                )
            }
            if (medianDelta != null) {
                val pctStr = pctDelta?.let { " (${fmtSigned(it, 1)}%)" }.orEmpty()
                val tStr = welchT?.let { " · Welch t=${fmt(it, 2)}" }.orEmpty()
                val dStr = cohensD?.let { " · Cohen's d=${fmt(it, 2)}" }.orEmpty()
                appendLine()
                appendLine("- **Median Delta (B − A)**: `${fmtSigned(medianDelta, metric.decimals)} ${metric.unit}$pctStr` · **$effectLabel**$tStr$dStr")
            }
        }
    }.trimEnd()

    private fun sessionShortLabel(session: Session): String {
        session.matchNumber?.let { return "M#$it" }
        return session.sessionId.take(8)
    }

    private fun fmt(v: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", v)

    private fun fmtSigned(v: Double, decimals: Int): String =
        String.format(Locale.US, "%+.${decimals}f", v)
}
