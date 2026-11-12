package com.ares.analytics.domain.analysis

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary

enum class CohortSplitMode(val label: String) {
    CHRONOLOGICAL_HALVES("Early Runs vs. Recent Runs"),
    TAG_VS_TAG("Tag A vs. Tag B"),
    TAG_VS_REST("Selected Tag vs. Other Runs"),
    ALLIANCE_RED_VS_BLUE("Red Alliance vs. Blue Alliance"),
}

data class CohortMetricSpec(
    val id: String,
    val label: String,
    val unit: String,
    val lowerIsBetter: Boolean?,
    val decimals: Int = 2,
    val extract: (Session, SessionSummary?, Map<String, Double>) -> Double?,
)

data class CohortSamplePoint(
    val sessionId: String,
    val sessionLabel: String,
    val createdAtMs: Long,
    val value: Double,
    val isOutlier: Boolean,
)

data class CohortSummaryStats(
    val cohortName: String,
    val count: Int,
    val min: Double,
    val q1: Double,
    val median: Double,
    val q3: Double,
    val max: Double,
    val iqr: Double,
    val mean: Double,
    val stdDev: Double,
    val outlierSessionIds: List<String>,
    val samples: List<CohortSamplePoint>,
)

data class CohortComparisonResult(
    val metricId: String,
    val metricLabel: String,
    val unit: String,
    val lowerIsBetter: Boolean?,
    val splitMode: CohortSplitMode,
    val splitDescription: String,
    val cohortA: CohortSummaryStats?,
    val cohortB: CohortSummaryStats?,
    val medianDelta: Double?,
    val meanDelta: Double?,
    val percentMedianDelta: Double?,
    val welchT: Double?,
    val cohensD: Double?,
    val effectMagnitudeLabel: String,
    val improved: Boolean?,
    val plainEnglishTakeaway: String,
    val markdownEvidenceBlock: String,
)
