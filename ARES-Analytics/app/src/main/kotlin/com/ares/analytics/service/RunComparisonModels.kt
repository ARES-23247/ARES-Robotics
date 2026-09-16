package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.WorkspaceConfig
import java.io.File

enum class RunAlignmentKind(val label: String) {
    RUN_START("Run start"),
    AUTONOMOUS_START("Autonomous start"),
    MATCH_EVENT("Match event"),
    ANNOTATION("Annotation"),
}

data class RunAlignmentOption(
    val id: String,
    val kind: RunAlignmentKind,
    val label: String,
    val explanation: String,
)

data class RunAlignmentAnchor(
    val sessionId: String,
    val absoluteTimestampMs: Long,
    val label: String,
)

data class RunComparisonRequest(
    val primarySessionId: String,
    val comparisonSessionIds: List<String>,
    val alignmentId: String = RUN_START_ALIGNMENT_ID,
)

data class AlignedRunSample(
    val alignedTimeMs: Long,
    val absoluteTimestampMs: Long,
    val value: Double,
)

data class RunMetricSummary(
    val minimum: Double,
    val maximum: Double,
    val average: Double,
    val p95: Double,
    val sampleCount: Int,
)

data class RunComparisonSeries(
    val sessionId: String,
    val runLabel: String,
    val sourceTopics: List<String>,
    val samples: List<AlignedRunSample>,
    val summary: RunMetricSummary,
)

data class RunComparisonMetric(
    val id: String,
    val label: String,
    val unit: String,
    val explanation: String,
    val series: List<RunComparisonSeries>,
)

data class RunTrajectoryPoint(
    val alignedTimeMs: Long,
    val absoluteTimestampMs: Long,
    val xMeters: Double,
    val yMeters: Double,
)

data class RunTrajectoryOverlay(
    val sessionId: String,
    val runLabel: String,
    val sourceTopics: List<String>,
    val points: List<RunTrajectoryPoint>,
)

data class RunFaultSummary(
    val sessionId: String,
    val runLabel: String,
    val alertCount: Int,
    val firstAlertTimestampMs: Long?,
    val alertKeys: List<String>,
)

enum class ComparisonClaimKind(val label: String) {
    OBSERVATION("Observed difference"),
    CORRELATION("Correlation — cause not proven"),
    LIMITATION("Evidence limitation"),
}

data class RunComparisonEvidenceLink(
    val sessionId: String,
    val absoluteTimestampMs: Long,
    val alignedTimeMs: Long,
    val topics: List<String>,
    val evidenceWindowMs: Long = 0L,
)

data class GuidedComparisonFinding(
    val id: String,
    val kind: ComparisonClaimKind,
    val title: String,
    val explanation: String,
    val evidence: RunComparisonEvidenceLink,
)

data class RunComparisonReport(
    val sessions: List<Session>,
    val primarySessionId: String,
    val selectedAlignment: RunAlignmentOption,
    val availableAlignments: List<RunAlignmentOption>,
    val anchors: List<RunAlignmentAnchor>,
    val trajectories: List<RunTrajectoryOverlay>,
    val metrics: List<RunComparisonMetric>,
    val faults: List<RunFaultSummary>,
    val findings: List<GuidedComparisonFinding>,
    val limitations: List<String>,
)

interface RunComparisonRepository {
    suspend fun compare(workspace: WorkspaceConfig, request: RunComparisonRequest): RunComparisonReport
    suspend fun exportMarkdown(report: RunComparisonReport, destination: File)
}

internal const val RUN_START_ALIGNMENT_ID = "run-start"
internal const val AUTONOMOUS_START_ALIGNMENT_ID = "autonomous-start"
