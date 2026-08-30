package com.ares.analytics.service

import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionAnnotation
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

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

/**
 * Deterministic, read-only paired-run analysis. Every selected run is checked against the active
 * workspace before any telemetry is read. Alignment shifts timestamps only; values retain their
 * original source timestamp and topic, and composite metrics join exact source instants.
 */
class RunComparisonService(
    private val databaseService: DatabaseService,
) : RunComparisonRepository {
    override suspend fun compare(
        workspace: WorkspaceConfig,
        request: RunComparisonRequest,
    ): RunComparisonReport = withContext(Dispatchers.IO) {
        val selectedIds = buildList {
            add(request.primarySessionId)
            addAll(request.comparisonSessionIds)
        }.filter(String::isNotBlank).distinct()
        require(selectedIds.size in 2..MAX_COMPARISON_RUNS) {
            "Choose between 2 and $MAX_COMPARISON_RUNS distinct runs to compare"
        }

        val workspaceSessions = databaseService.getSessionsForWorkspace(
            workspace.teamId,
            workspace.seasonId,
            workspace.robotId,
        ).associateBy(Session::sessionId)
        val sessions = selectedIds.map { sessionId ->
            workspaceSessions[sessionId]
                ?: throw IllegalArgumentException("Run $sessionId is not part of the selected team, season, and robot workspace")
        }
        val loaded = sessions.map { session -> loadRun(session) }
        val availableAlignments = commonAlignmentOptions(loaded)
        val selectedAlignment = availableAlignments.firstOrNull { it.id == request.alignmentId }
            ?: throw IllegalArgumentException("The selected alignment marker is not present in every chosen run")
        val anchors = loaded.map { run ->
            val marker = requireNotNull(run.markers[selectedAlignment.id])
            RunAlignmentAnchor(run.session.sessionId, marker.timestampMs, marker.label)
        }
        val anchorBySession = anchors.associateBy(RunAlignmentAnchor::sessionId)
        val signals = loaded.map { run -> loadSignals(run, requireNotNull(anchorBySession[run.session.sessionId])) }

        val metrics = METRIC_DEFINITIONS.mapNotNull { definition ->
            val candidates = signals.mapNotNull { signal ->
                signal.metricSeries[definition.id]?.takeIf { it.samples.isNotEmpty() }
            }
            val primary = candidates.firstOrNull { it.series.sessionId == request.primarySessionId }
                ?: return@mapNotNull null
            val availableSeries = candidates
                .filter { it.compatibilityKey == primary.compatibilityKey }
                .map(CompatibleSeries::series)
            if (availableSeries.size < 2) null else RunComparisonMetric(
                id = definition.id,
                label = definition.label,
                unit = definition.unit,
                explanation = definition.explanation,
                series = availableSeries,
            )
        }
        val faults = loaded.map { run ->
            val alerts = run.alerts
            RunFaultSummary(
                sessionId = run.session.sessionId,
                runLabel = run.session.shortRunLabel(),
                alertCount = alerts.size,
                firstAlertTimestampMs = alerts.firstOrNull()?.triggerTimestampMs,
                alertKeys = alerts.map { it.ruleKey }.distinct().sorted(),
            )
        }
        val findings = buildFindings(
            primarySessionId = request.primarySessionId,
            metrics = metrics,
            faults = faults,
            anchorBySession = anchorBySession,
        )
        val trajectoryCandidates = signals.mapNotNull(RunSignals::trajectory).filter { it.points.isNotEmpty() }
        val trajectories = trajectoryCandidates
            .takeIf { candidates ->
                candidates.size >= 2 && candidates.any { it.sessionId == request.primarySessionId }
            }
            .orEmpty()
        RunComparisonReport(
            sessions = sessions,
            primarySessionId = request.primarySessionId,
            selectedAlignment = selectedAlignment,
            availableAlignments = availableAlignments,
            anchors = anchors,
            trajectories = trajectories,
            metrics = metrics,
            faults = faults,
            findings = findings,
            limitations = buildList {
                add("Aligned time is a viewing coordinate. Every evidence link preserves the original run timestamp and source topics.")
                add("ARES joins trajectory and derived signals only at exact source timestamps; it does not use future samples to fill gaps.")
                add("Each topic contributes at most 1,500 uniformly spaced persisted samples, including both endpoints. ARES creates no interpolated or held samples; charts connect adjacent loaded points only as a visual guide, and summaries describe the bounded sample set.")
                add("Different sample rates and missing topics can limit a comparison. Missing data is never treated as zero or healthy.")
                add("A correlation can suggest what to inspect next, but it does not prove that one signal caused another change.")
                if (metrics.none { it.id == METRIC_MECHANISM_ERROR }) {
                    add("No compatible mechanism target/measurement pair was present in at least two selected runs.")
                } else {
                    add("Mechanism tracking error retains the producer's source unit because these legacy topics do not carry canonical dimension metadata. Compare only the identical listed target/measurement topic pair.")
                }
                if (trajectories.size < 2) {
                    add("At least two runs did not contain a source-consistent X/Y pose pair, so a trajectory overlay is incomplete.")
                }
            },
        )
    }

    override suspend fun exportMarkdown(report: RunComparisonReport, destination: File) = withContext(Dispatchers.IO) {
        writeFileAtomically(destination) { temporary -> temporary.writeText(renderMarkdown(report)) }
    }

    fun renderMarkdown(report: RunComparisonReport): String = buildString {
        appendLine("# ARES mentor/student run comparison")
        appendLine()
        appendLine("Alignment: ${report.selectedAlignment.label.safeComparisonMarkdown()}")
        appendLine("Primary run: ${report.primarySessionId.safeComparisonMarkdown()}")
        appendLine()
        appendLine("> Historical correlation is not proof of cause, and this report is not a physical robot safety certification.")
        appendLine()
        appendLine("## Selected runs and anchors")
        report.sessions.forEach { session ->
            val anchor = report.anchors.first { it.sessionId == session.sessionId }
            appendLine("- ${session.shortRunLabel().safeComparisonMarkdown()} (`${session.sessionId.safeComparisonMarkdown()}`): ${anchor.label.safeComparisonMarkdown()} at ${anchor.absoluteTimestampMs} ms")
        }
        appendLine()
        appendLine("## Comparable telemetry")
        report.metrics.forEach { metric ->
            appendLine("### ${metric.label.safeComparisonMarkdown()} (${metric.unit.safeComparisonMarkdown()})")
            appendLine(metric.explanation.safeComparisonMarkdown())
            metric.series.forEach { series ->
                appendLine("- ${series.runLabel.safeComparisonMarkdown()}: min ${series.summary.minimum.formatComparison()}, max ${series.summary.maximum.formatComparison()}, average ${series.summary.average.formatComparison()}, p95 ${series.summary.p95.formatComparison()} from ${series.summary.sampleCount} samples; topics: ${series.sourceTopics.joinToString().safeComparisonMarkdown()}")
            }
        }
        appendLine()
        appendLine("## Guided findings")
        if (report.findings.isEmpty()) appendLine("- No configured material difference was found. This does not prove the runs are equivalent.")
        report.findings.forEach { finding ->
            appendLine("### ${finding.title.safeComparisonMarkdown()}")
            appendLine("- Claim type: ${finding.kind.label}")
            appendLine("- Explanation: ${finding.explanation.safeComparisonMarkdown()}")
            appendLine("- Replay evidence: session `${finding.evidence.sessionId.safeComparisonMarkdown()}`, timestamp ${finding.evidence.absoluteTimestampMs} ms, aligned ${finding.evidence.alignedTimeMs} ms, topics ${finding.evidence.topics.joinToString().safeComparisonMarkdown()}, window ±${finding.evidence.evidenceWindowMs} ms")
        }
        appendLine()
        appendLine("## Fault and alert records")
        report.faults.forEach { fault ->
            appendLine("- ${fault.runLabel.safeComparisonMarkdown()}: ${fault.alertCount} persisted alert(s)${fault.alertKeys.takeIf(List<String>::isNotEmpty)?.joinToString(prefix = " — ")?.safeComparisonMarkdown().orEmpty()}")
        }
        appendLine()
        appendLine("## Evidence boundaries")
        report.limitations.forEach { appendLine("- ${it.safeComparisonMarkdown()}") }
    }

    private suspend fun loadRun(session: Session): LoadedRun {
        val range = databaseService.getSessionTimestampRange(session.sessionId)
            ?: throw IllegalArgumentException("Run ${session.sessionId} has no persisted telemetry timeline")
        val keys = databaseService.getDistinctTelemetryKeys(session.sessionId)
        val markerKeys = keys.filter(::isMarkerTopic)
        val markerFrames = databaseService.getTelemetryForFilters(
            sessionId = session.sessionId,
            keys = markerKeys,
            prefixes = emptyList(),
            maxFrames = MAX_MARKER_FRAMES,
            maxFramesPerTopic = MAX_MARKER_FRAMES_PER_TOPIC,
        )
        val annotations = databaseService.getAnnotations(session.sessionId)
        val actions = databaseService.getActionsForSession(session.sessionId)
        val markers = linkedMapOf<String, Marker>()
        markers[RUN_START_ALIGNMENT_ID] = Marker(range.first, "Run start")

        val autonomousTimestamp = markerFrames.firstOrNull { it.isAutonomousStart() }?.timestampMs
            ?: actions.firstOrNull { it.actionType.contains("autonomous", ignoreCase = true) && it.actionType.contains("start", ignoreCase = true) }?.timestampMs
        autonomousTimestamp?.let { timestamp ->
            markers[AUTONOMOUS_START_ALIGNMENT_ID] = Marker(timestamp, "Autonomous start")
        }
        markerFrames.firstOrNull { frame -> frame.isTeleOpStart() }?.let { frame ->
            markers["match:teleop-start"] = Marker(frame.timestampMs, "Match event · TeleOp start")
        }
        markerFrames.filter { it.isNamedMatchEvent() }.forEach { frame ->
            val eventName = requireNotNull(frame.stringValue)
            val label = eventName.normalizedMarkerLabel()
            if (label.isNotBlank()) {
                markers.putIfAbsent("match:event:$label", Marker(frame.timestampMs, "Match event · ${eventName.oneLineComparison()}"))
            }
        }
        actions.filter { it.actionType.isNotBlank() && it.actionType.contains("match", ignoreCase = true) }.forEach { action ->
            val label = action.actionType.normalizedMarkerLabel()
            if (label.isNotBlank()) {
                markers.putIfAbsent("match:action:$label", Marker(action.timestampMs, "Match event · ${action.actionType.oneLineComparison()}"))
            }
        }
        annotations.asSequence()
            .filter { it.createdAt in range.first..range.second }
            .mapNotNull { annotation -> annotation.toTimelineMarker() }
            .forEach { (id, marker) -> markers.putIfAbsent(id, marker) }

        val alerts = databaseService.getAlerts(session.sessionId).sortedWith(
            compareBy({ it.triggerTimestampMs }, { it.alertId })
        )
        return LoadedRun(session, range, keys, markers, alerts)
    }

    private fun commonAlignmentOptions(runs: List<LoadedRun>): List<RunAlignmentOption> {
        val commonIds = runs.map { it.markers.keys }.reduce(Set<String>::intersect)
        val primaryMarkers = runs.first().markers
        return commonIds.mapNotNull { id ->
            val marker = primaryMarkers[id] ?: return@mapNotNull null
            val kind = when {
                id == RUN_START_ALIGNMENT_ID -> RunAlignmentKind.RUN_START
                id == AUTONOMOUS_START_ALIGNMENT_ID -> RunAlignmentKind.AUTONOMOUS_START
                id.startsWith("match:") -> RunAlignmentKind.MATCH_EVENT
                id.startsWith("annotation:") -> RunAlignmentKind.ANNOTATION
                else -> return@mapNotNull null
            }
            RunAlignmentOption(
                id = id,
                kind = kind,
                label = marker.label,
                explanation = when (kind) {
                    RunAlignmentKind.RUN_START -> "Compare elapsed time from each recording's first persisted telemetry sample."
                    RunAlignmentKind.AUTONOMOUS_START -> "Compare elapsed time from the first persisted autonomous-running state."
                    RunAlignmentKind.MATCH_EVENT -> "Compare elapsed time from the same persisted match event in every run."
                    RunAlignmentKind.ANNOTATION -> "Compare elapsed time from the same timeline annotation in every run."
                },
            )
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.label.lowercase() }, RunAlignmentOption::id))
    }

    private suspend fun loadSignals(run: LoadedRun, anchor: RunAlignmentAnchor): RunSignals {
        val keys = run.keys
        val batteryKey = firstAvailableKey(keys, TelemetryMetricCatalog.BATTERY_VOLTAGE.keys)
        val loopKey = firstAvailableKey(keys, TelemetryMetricCatalog.LOOP_TIME.keys)
        val currentKeys = keys.filter(::isIndividualCurrentTopic).sorted().take(MAX_DYNAMIC_TOPICS)
        val estimatedPosePair = firstPosePair(keys, ESTIMATED_POSE_PAIRS)
        val truePosePair = firstPosePair(keys, TRUE_POSE_PAIRS)
        val driverAxes = DRIVER_INPUT_KEYS.mapIndexedNotNull { index, candidates ->
            firstAvailableKey(keys, candidates)?.let { index to it }
        }
        val driverKeys = driverAxes.map(Pair<Int, String>::second)
        val mechanismPair = findMechanismPair(keys)
        val selectedKeys = buildList {
            batteryKey?.let(::add)
            loopKey?.let(::add)
            addAll(currentKeys)
            estimatedPosePair?.let { add(it.first); add(it.second) }
            truePosePair?.let { add(it.first); add(it.second) }
            addAll(driverKeys)
            mechanismPair?.let { add(it.first); add(it.second) }
        }.distinct()
        val frames = databaseService.getTelemetryForFilters(
            sessionId = run.session.sessionId,
            keys = selectedKeys,
            prefixes = emptyList(),
            maxFrames = MAX_SIGNAL_FRAMES,
            maxFramesPerTopic = MAX_SIGNAL_FRAMES_PER_TOPIC,
        ).filter { it.value.isFinite() }
        val byKey = frames.groupBy(TelemetryFrame::key)
        val metricSeries = linkedMapOf<String, CompatibleSeries>()

        batteryKey?.let { key ->
            byKey[key]?.telemetryToSeries(run.session, anchor, listOf(key))?.let {
                metricSeries[METRIC_BATTERY] = CompatibleSeries(it, METRIC_BATTERY)
            }
        }
        loopKey?.let { key ->
            byKey[key]?.telemetryToSeries(run.session, anchor, listOf(key))?.let {
                metricSeries[METRIC_LOOP_TIME] = CompatibleSeries(it, METRIC_LOOP_TIME)
            }
        }
        exactAggregate(byKey, currentKeys) { values -> values.sum() }
            .toSeries(run.session, anchor, currentKeys)
            ?.let {
                metricSeries[METRIC_TOTAL_CURRENT] = CompatibleSeries(
                    it,
                    "current:${currentKeys.map(TelemetryMetricCatalog::normalizeTopic).sorted().joinToString("|")}",
                )
            }

        val estimatedPoints = estimatedPosePair?.let { pair -> exactPairs(byKey[pair.first], byKey[pair.second]) }
        val trajectory = estimatedPoints?.map { point ->
            RunTrajectoryPoint(
                alignedTimeMs = point.timestampMs - anchor.absoluteTimestampMs,
                absoluteTimestampMs = point.timestampMs,
                xMeters = point.first,
                yMeters = point.second,
            )
        }?.takeIf(List<RunTrajectoryPoint>::isNotEmpty)?.let { points ->
            RunTrajectoryOverlay(
                sessionId = run.session.sessionId,
                runLabel = run.session.shortRunLabel(),
                sourceTopics = listOf(estimatedPosePair.first, estimatedPosePair.second),
                points = points,
            )
        }
        if (estimatedPosePair != null && truePosePair != null) {
            val estimated = exactPairs(byKey[estimatedPosePair.first], byKey[estimatedPosePair.second])
                .associateBy(ExactPoint::timestampUs)
            val truth = exactPairs(byKey[truePosePair.first], byKey[truePosePair.second])
            truth.mapNotNull { actual ->
                val estimate = estimated[actual.timestampUs] ?: return@mapNotNull null
                NumericPoint(actual.timestampMs, actual.timestampUs, hypot(actual.first - estimate.first, actual.second - estimate.second))
            }.toSeries(run.session, anchor, listOf(truePosePair.first, truePosePair.second, estimatedPosePair.first, estimatedPosePair.second))
                ?.let { metricSeries[METRIC_LOCALIZATION_ERROR] = CompatibleSeries(it, METRIC_LOCALIZATION_ERROR) }
        }
        exactMagnitude(byKey, driverKeys)
            .toSeries(run.session, anchor, driverKeys)
            ?.let {
                metricSeries[METRIC_DRIVER_INPUT] = CompatibleSeries(
                    it,
                    "driver-axes:${driverAxes.map(Pair<Int, String>::first).joinToString(",")}",
                )
            }
        mechanismPair?.let { pair ->
            val measured = byKey[pair.first].orEmpty().lastSamplePerTimestamp()
                .associateBy(TelemetryFrame::timestampUs)
            byKey[pair.second].orEmpty().lastSamplePerTimestamp().mapNotNull { target ->
                val actual = measured[target.timestampUs] ?: return@mapNotNull null
                NumericPoint(target.timestampMs, target.timestampUs, abs(target.value - actual.value))
            }.toSeries(run.session, anchor, listOf(pair.first, pair.second))
                ?.let {
                    metricSeries[METRIC_MECHANISM_ERROR] = CompatibleSeries(
                        it,
                        "mechanism:${listOf(pair.first, pair.second).map(TelemetryMetricCatalog::normalizeTopic).joinToString("|")}",
                    )
                }
        }
        return RunSignals(metricSeries, trajectory)
    }

    private fun buildFindings(
        primarySessionId: String,
        metrics: List<RunComparisonMetric>,
        faults: List<RunFaultSummary>,
        anchorBySession: Map<String, RunAlignmentAnchor>,
    ): List<GuidedComparisonFinding> = buildList {
        val metricsById = metrics.associateBy(RunComparisonMetric::id)
        val primaryByMetric = metrics.associate { metric -> metric.id to metric.series.firstOrNull { it.sessionId == primarySessionId } }
        metrics.forEach { metric ->
            val primary = primaryByMetric[metric.id] ?: return@forEach
            metric.series.filter { it.sessionId != primarySessionId }.forEach { candidate ->
                val difference = materialDifference(metric.id, primary, candidate) ?: return@forEach
                val evidenceSample = difference.evidence.samples.minByOrNull { sample ->
                    when (difference.extreme) {
                        EvidenceExtreme.MINIMUM -> sample.value
                        EvidenceExtreme.MAXIMUM -> -sample.value
                    }
                } ?: return@forEach
                add(
                    GuidedComparisonFinding(
                        id = "${metric.id}:${difference.evidence.sessionId}",
                        kind = ComparisonClaimKind.OBSERVATION,
                        title = "${difference.evidence.runLabel}: ${metric.label.lowercase()} differed",
                        explanation = "${difference.explanation} This is a measured difference, not a root-cause diagnosis.",
                        evidence = RunComparisonEvidenceLink(
                            sessionId = difference.evidence.sessionId,
                            absoluteTimestampMs = evidenceSample.absoluteTimestampMs,
                            alignedTimeMs = evidenceSample.alignedTimeMs,
                            topics = difference.evidence.sourceTopics,
                        ),
                    )
                )
            }
        }

        val battery = metricsById[METRIC_BATTERY]
        val loop = metricsById[METRIC_LOOP_TIME]
        if (battery != null && loop != null) {
            val primaryBattery = battery.series.firstOrNull { it.sessionId == primarySessionId }
            val primaryLoop = loop.series.firstOrNull { it.sessionId == primarySessionId }
            battery.series.filter { it.sessionId != primarySessionId }.forEach { candidateBattery ->
                val candidateLoop = loop.series.firstOrNull { it.sessionId == candidateBattery.sessionId } ?: return@forEach
                if (primaryBattery == null || primaryLoop == null) return@forEach
                val lowerBattery = listOf(primaryBattery, candidateBattery).minBy { it.summary.minimum }
                val comparisonBattery = if (lowerBattery.sessionId == primaryBattery.sessionId) candidateBattery else primaryBattery
                val lowerBatteryLoop = if (lowerBattery.sessionId == primaryLoop.sessionId) primaryLoop else candidateLoop
                val comparisonLoop = if (lowerBatteryLoop.sessionId == primaryLoop.sessionId) candidateLoop else primaryLoop
                val voltageEvidence = lowerBattery.samples.minByOrNull(AlignedRunSample::value) ?: return@forEach
                val nearbyLoop = lowerBatteryLoop.samples
                    .filter { sample -> abs(sample.alignedTimeMs - voltageEvidence.alignedTimeMs) <= CORRELATION_REVIEW_WINDOW_MS }
                    .maxByOrNull(AlignedRunSample::value)
                if (nearbyLoop != null &&
                    lowerBattery.summary.minimum <= comparisonBattery.summary.minimum - BATTERY_MATERIAL_DROP_VOLTS &&
                    nearbyLoop.value >= comparisonLoop.summary.p95 * LOOP_MATERIAL_RATIO
                ) {
                    add(
                        GuidedComparisonFinding(
                            id = "battery-loop-correlation:${lowerBattery.sessionId}",
                            kind = ComparisonClaimKind.CORRELATION,
                            title = "${lowerBattery.runLabel}: lower voltage and a slower loop sample occurred close together",
                            explanation = "This run's minimum voltage was lower than ${comparisonBattery.runLabel}, and a slower loop sample occurred within ${CORRELATION_REVIEW_WINDOW_MS} ms of the voltage evidence. Inspect both signals; ARES cannot prove the voltage caused the slowdown.",
                            evidence = RunComparisonEvidenceLink(
                                sessionId = lowerBattery.sessionId,
                                absoluteTimestampMs = voltageEvidence.absoluteTimestampMs,
                                alignedTimeMs = voltageEvidence.alignedTimeMs,
                                topics = (lowerBattery.sourceTopics + lowerBatteryLoop.sourceTopics).distinct(),
                                evidenceWindowMs = CORRELATION_REVIEW_WINDOW_MS,
                            ),
                        )
                    )
                }
            }
        }

        val primaryFault = faults.firstOrNull { it.sessionId == primarySessionId }
        faults.filter { it.sessionId != primarySessionId }.forEach { candidateFault ->
            val referenceFault = primaryFault ?: return@forEach
            val fault = listOf(referenceFault, candidateFault).maxBy(RunFaultSummary::alertCount)
            val lowerCount = minOf(referenceFault.alertCount, candidateFault.alertCount)
            if (fault.alertCount <= lowerCount) return@forEach
            val timestamp = fault.firstAlertTimestampMs ?: return@forEach
            val anchor = anchorBySession[fault.sessionId] ?: return@forEach
            add(
                GuidedComparisonFinding(
                    id = "faults:${fault.sessionId}",
                    kind = ComparisonClaimKind.OBSERVATION,
                    title = "${fault.runLabel}: more persisted alerts",
                    explanation = "This run recorded ${fault.alertCount} alert events versus $lowerCount in the compared run. The alert topics identify evidence, not a confirmed repair.",
                    evidence = RunComparisonEvidenceLink(
                        sessionId = fault.sessionId,
                        absoluteTimestampMs = timestamp,
                        alignedTimeMs = timestamp - anchor.absoluteTimestampMs,
                        topics = fault.alertKeys,
                    ),
                )
            )
        }
    }.distinctBy(GuidedComparisonFinding::id)
        .sortedWith(compareBy({ it.evidence.alignedTimeMs }, { it.id }))

    private fun materialDifference(
        id: String,
        primary: RunComparisonSeries,
        candidate: RunComparisonSeries,
    ): MaterialDifference? {
        val lowerMinimum = listOf(primary, candidate).minBy { it.summary.minimum }
        val higherMinimum = if (lowerMinimum.sessionId == primary.sessionId) candidate else primary
        val higherP95 = listOf(primary, candidate).maxBy { it.summary.p95 }
        val lowerP95 = if (higherP95.sessionId == primary.sessionId) candidate else primary
        return when (id) {
            METRIC_BATTERY -> if (higherMinimum.summary.minimum - lowerMinimum.summary.minimum >= BATTERY_MATERIAL_DROP_VOLTS) {
                MaterialDifference(
                    lowerMinimum,
                    EvidenceExtreme.MINIMUM,
                    "${lowerMinimum.runLabel} reached ${lowerMinimum.summary.minimum.formatComparison()} V versus ${higherMinimum.summary.minimum.formatComparison()} V in ${higherMinimum.runLabel}.",
                )
            } else null
            METRIC_LOOP_TIME -> if (higherP95.summary.p95 >= lowerP95.summary.p95 * LOOP_MATERIAL_RATIO && higherP95.summary.p95 - lowerP95.summary.p95 >= 1.0) {
                MaterialDifference(higherP95, EvidenceExtreme.MAXIMUM, "${higherP95.runLabel} had a ${higherP95.summary.p95.formatComparison()} ms p95 loop time versus ${lowerP95.summary.p95.formatComparison()} ms in ${lowerP95.runLabel}.")
            } else null
            METRIC_TOTAL_CURRENT -> if (higherP95.summary.p95 >= lowerP95.summary.p95 * CURRENT_MATERIAL_RATIO && higherP95.summary.p95 - lowerP95.summary.p95 >= 1.0) {
                MaterialDifference(higherP95, EvidenceExtreme.MAXIMUM, "${higherP95.runLabel} had ${higherP95.summary.p95.formatComparison()} A p95 observed actuator current versus ${lowerP95.summary.p95.formatComparison()} A in ${lowerP95.runLabel}.")
            } else null
            METRIC_LOCALIZATION_ERROR -> if (higherP95.summary.p95 - lowerP95.summary.p95 >= LOCALIZATION_MATERIAL_METERS) {
                MaterialDifference(higherP95, EvidenceExtreme.MAXIMUM, "${higherP95.runLabel} had ${higherP95.summary.p95.formatComparison()} m p95 truth-to-estimate error versus ${lowerP95.summary.p95.formatComparison()} m in ${lowerP95.runLabel}.")
            } else null
            METRIC_DRIVER_INPUT -> if (abs(candidate.summary.average - primary.summary.average) >= DRIVER_MATERIAL_INPUT) {
                val stronger = listOf(primary, candidate).maxBy { it.summary.average }
                val other = if (stronger.sessionId == primary.sessionId) candidate else primary
                MaterialDifference(stronger, EvidenceExtreme.MAXIMUM, "${stronger.runLabel} had ${stronger.summary.average.formatComparison()} average driver-input magnitude versus ${other.summary.average.formatComparison()} in ${other.runLabel}.")
            } else null
            METRIC_MECHANISM_ERROR -> if (higherP95.summary.p95 >= lowerP95.summary.p95 * MECHANISM_MATERIAL_RATIO && higherP95.summary.p95 - lowerP95.summary.p95 > 1e-6) {
                MaterialDifference(higherP95, EvidenceExtreme.MAXIMUM, "${higherP95.runLabel} had ${higherP95.summary.p95.formatComparison()} p95 target-tracking error versus ${lowerP95.summary.p95.formatComparison()} in ${lowerP95.runLabel}.")
            } else null
            else -> null
        }
    }

    private data class LoadedRun(
        val session: Session,
        val range: Pair<Long, Long>,
        val keys: List<String>,
        val markers: Map<String, Marker>,
        val alerts: List<com.ares.analytics.shared.models.AlertRecord>,
    )

    private data class Marker(val timestampMs: Long, val label: String)
    private data class RunSignals(
        val metricSeries: Map<String, CompatibleSeries>,
        val trajectory: RunTrajectoryOverlay?,
    )
    private data class CompatibleSeries(
        val series: RunComparisonSeries,
        val compatibilityKey: String,
    ) {
        val samples: List<AlignedRunSample> get() = series.samples
    }

    private enum class EvidenceExtreme { MINIMUM, MAXIMUM }

    private data class MaterialDifference(
        val evidence: RunComparisonSeries,
        val extreme: EvidenceExtreme,
        val explanation: String,
    )

    private data class MetricDefinition(val id: String, val label: String, val unit: String, val explanation: String)
    private data class ExactPoint(val timestampMs: Long, val timestampUs: Long, val first: Double, val second: Double)
    private data class NumericPoint(val timestampMs: Long, val timestampUs: Long, val value: Double)

    private fun List<TelemetryFrame>.telemetryToSeries(
        session: Session,
        anchor: RunAlignmentAnchor,
        topics: List<String>,
    ): RunComparisonSeries? = map { NumericPoint(it.timestampMs, it.timestampUs, it.value) }
        .toSeries(session, anchor, topics)

    private fun List<NumericPoint>.toSeries(
        session: Session,
        anchor: RunAlignmentAnchor,
        topics: List<String>,
    ): RunComparisonSeries? {
        if (isEmpty()) return null
        val values = map(NumericPoint::value)
        return RunComparisonSeries(
            sessionId = session.sessionId,
            runLabel = session.shortRunLabel(),
            sourceTopics = topics.distinct(),
            samples = map { AlignedRunSample(it.timestampMs - anchor.absoluteTimestampMs, it.timestampMs, it.value) },
            summary = RunMetricSummary(
                minimum = values.min(),
                maximum = values.max(),
                average = values.average(),
                p95 = values.sorted()[(ceil(values.size * 0.95).toInt() - 1).coerceIn(0, values.lastIndex)],
                sampleCount = values.size,
            ),
        )
    }

    private fun exactPairs(first: List<TelemetryFrame>?, second: List<TelemetryFrame>?): List<ExactPoint> {
        val secondByTime = second.orEmpty().lastSamplePerTimestamp().associateBy(TelemetryFrame::timestampUs)
        return first.orEmpty().lastSamplePerTimestamp().mapNotNull { a ->
            val b = secondByTime[a.timestampUs] ?: return@mapNotNull null
            ExactPoint(a.timestampMs, a.timestampUs, a.value, b.value)
        }
    }

    private fun exactAggregate(
        byKey: Map<String, List<TelemetryFrame>>,
        keys: List<String>,
        aggregate: (DoubleArray) -> Double,
    ): List<NumericPoint> {
        if (keys.isEmpty()) return emptyList()
        val grouped = keys.flatMap { byKey[it].orEmpty() }.groupBy(TelemetryFrame::timestampUs)
        return grouped.entries.sortedBy(Map.Entry<Long, List<TelemetryFrame>>::key).mapNotNull { (_, frames) ->
            val latestByKey = frames.groupBy(TelemetryFrame::key)
                .mapValues { (_, samples) -> samples.maxBy(TelemetryFrame::sampleOrder) }
            if (keys.any { it !in latestByKey }) return@mapNotNull null
            val values = DoubleArray(keys.size) { index -> requireNotNull(latestByKey[keys[index]]).value }
            val timestamp = requireNotNull(latestByKey[keys.first()])
            NumericPoint(timestamp.timestampMs, timestamp.timestampUs, aggregate(values))
        }
    }

    private fun exactMagnitude(byKey: Map<String, List<TelemetryFrame>>, keys: List<String>): List<NumericPoint> {
        if (keys.size < 2) return emptyList()
        val grouped = keys.flatMap { byKey[it].orEmpty() }.groupBy(TelemetryFrame::timestampUs)
        return grouped.entries.sortedBy(Map.Entry<Long, List<TelemetryFrame>>::key).mapNotNull { (_, frames) ->
            val latestByKey = frames.groupBy(TelemetryFrame::key)
                .mapValues { (_, samples) -> samples.maxBy(TelemetryFrame::sampleOrder) }
            if (keys.any { it !in latestByKey }) return@mapNotNull null
            val timestamp = requireNotNull(latestByKey[keys.first()])
            NumericPoint(
                timestampMs = timestamp.timestampMs,
                timestampUs = timestamp.timestampUs,
                value = kotlin.math.sqrt(keys.sumOf { key -> requireNotNull(latestByKey[key]).value.let { it * it } }),
            )
        }
    }

    private fun List<TelemetryFrame>.lastSamplePerTimestamp(): List<TelemetryFrame> =
        groupBy(TelemetryFrame::timestampUs)
            .toSortedMap()
            .values
            .map { samples -> samples.maxBy(TelemetryFrame::sampleOrder) }

    private fun firstAvailableKey(keys: List<String>, candidates: Set<String>): String? {
        val byNormalized = keys.associateBy(TelemetryMetricCatalog::normalizeTopic)
        return candidates.firstNotNullOfOrNull { byNormalized[TelemetryMetricCatalog.normalizeTopic(it)] }
    }

    private fun firstPosePair(keys: List<String>, candidates: List<Pair<String, String>>): Pair<String, String>? {
        val byNormalized = keys.associateBy(TelemetryMetricCatalog::normalizeTopic)
        return candidates.firstNotNullOfOrNull { (x, y) ->
            val actualX = byNormalized[TelemetryMetricCatalog.normalizeTopic(x)]
            val actualY = byNormalized[TelemetryMetricCatalog.normalizeTopic(y)]
            if (actualX != null && actualY != null) actualX to actualY else null
        }
    }

    private fun findMechanismPair(keys: List<String>): Pair<String, String>? {
        val normalized = keys.associateBy { it.lowercase() }
        keys.sorted().forEach { target ->
            val lower = target.lowercase()
            val token = when {
                lower.endsWith("/target") -> "/target"
                lower.endsWith("/setpoint") -> "/setpoint"
                lower.endsWith("/targetposition") -> "/targetposition"
                lower.endsWith("/targetvelocity") -> "/targetvelocity"
                else -> return@forEach
            }
            val base = lower.removeSuffix(token)
            val candidates = listOf("$base/position", "$base/measurement", "$base/velocity", "$base/actual")
            candidates.firstNotNullOfOrNull(normalized::get)?.let { measured -> return measured to target }
        }
        return null
    }

    private fun isIndividualCurrentTopic(key: String): Boolean {
        val normalized = TelemetryMetricCatalog.normalizeTopic(key)
        if (DRIVE_MOTOR_CURRENT_TOPIC.matches(normalized)) return true
        val lower = normalized.lowercase()
        if (!lower.endsWith("/currentamps") && !lower.endsWith("/current")) return false
        if (lower.startsWith("robot/") || lower.startsWith("pdh/") || lower.startsWith("pdp/")) return false
        return listOf("totalcurrent", "currentlimit", "currentvalid", "currentthreshold")
            .none(lower::contains)
    }

    private fun isMarkerTopic(key: String): Boolean {
        val lower = key.lowercase()
        return lower.contains("mode") || lower.contains("match") || lower.contains("event") ||
            lower.endsWith("driverstation/state") || lower.endsWith("driverstationstate")
    }

    private fun TelemetryFrame.isAutonomousStart(): Boolean {
        val text = stringValue?.trim()?.uppercase() ?: return false
        return text == "AUTO" || text == "AUTONOMOUS" || text == "AUTO_RUNNING" ||
            text == "AUTONOMOUS_RUNNING" || text == "AUTONOMOUS_ENABLED"
    }

    private fun TelemetryFrame.isTeleOpStart(): Boolean {
        val text = stringValue?.trim()?.uppercase() ?: return false
        return text == "TELEOP" || text == "TELEOP_RUNNING" || text == "TELEOP_ENABLED"
    }

    private fun TelemetryFrame.isNamedMatchEvent(): Boolean {
        val text = stringValue?.trim().orEmpty()
        return text.isNotBlank() && key.contains("event", ignoreCase = true)
    }

    private fun SessionAnnotation.toTimelineMarker(): Pair<String, Marker>? {
        val clean = text.replace(Regex("^\\[Event at \\+[0-9.]+s]\\s*", RegexOption.IGNORE_CASE), "")
            .oneLineComparison()
        if (clean.isBlank()) return null
        val normalized = clean.normalizedMarkerLabel()
        if (normalized.isBlank()) return null
        val id = "annotation:$normalized"
        return id to Marker(createdAt, "Annotation · $clean")
    }

    private companion object {
        const val MAX_COMPARISON_RUNS = 6
        const val MAX_MARKER_FRAMES = 12_000
        const val MAX_MARKER_FRAMES_PER_TOPIC = 1_000
        const val MAX_SIGNAL_FRAMES_PER_TOPIC = 1_500
        const val MAX_DYNAMIC_TOPICS = 16
        // battery + loop + dynamic currents + estimated XY + truth XY + three driver axes +
        // one mechanism target/measurement pair. The total bound must retain both endpoints for
        // every independently sampled topic.
        const val MAX_SELECTED_SIGNAL_TOPICS = MAX_DYNAMIC_TOPICS + 11
        const val MAX_SIGNAL_FRAMES = MAX_SIGNAL_FRAMES_PER_TOPIC * MAX_SELECTED_SIGNAL_TOPICS
        const val BATTERY_MATERIAL_DROP_VOLTS = 0.35
        const val LOOP_MATERIAL_RATIO = 1.15
        const val CURRENT_MATERIAL_RATIO = 1.20
        const val LOCALIZATION_MATERIAL_METERS = 0.05
        const val DRIVER_MATERIAL_INPUT = 0.15
        const val MECHANISM_MATERIAL_RATIO = 1.20
        const val CORRELATION_REVIEW_WINDOW_MS = 500L

        const val METRIC_BATTERY = "battery_voltage"
        const val METRIC_LOOP_TIME = "loop_time"
        const val METRIC_TOTAL_CURRENT = "total_motor_current"
        const val METRIC_LOCALIZATION_ERROR = "localization_error"
        const val METRIC_DRIVER_INPUT = "driver_input_magnitude"
        const val METRIC_MECHANISM_ERROR = "mechanism_tracking_error"

        val METRIC_DEFINITIONS = listOf(
            MetricDefinition(METRIC_BATTERY, "Battery voltage", "V", "Recorded battery voltage. Lower values can coincide with reduced actuator authority."),
            MetricDefinition(METRIC_LOOP_TIME, "Control-loop time", "ms", "Recorded periodic-loop duration; p95 highlights repeated slow cycles without hiding spikes."),
            MetricDefinition(METRIC_TOTAL_CURRENT, "Observed actuator current", "A", "Sum of compatible actuator-current topics only when every selected channel shares the exact source timestamp."),
            MetricDefinition(METRIC_LOCALIZATION_ERROR, "Localization error", "m", "Distance between simulator truth and the estimator at an identical source timestamp."),
            MetricDefinition(METRIC_DRIVER_INPUT, "Driver input magnitude", "normalized", "Combined gamepad axes at an identical source timestamp; this describes command style, not driver skill."),
            MetricDefinition(METRIC_MECHANISM_ERROR, "Mechanism tracking error", "source unit", "Absolute target-to-measurement error for one compatible mechanism pair at identical source timestamps."),
        )

        val ESTIMATED_POSE_PAIRS = listOf(
            "ARES/SimulatorPoseFrame/3" to "ARES/SimulatorPoseFrame/4",
            "ARES/EstimatedPose/0" to "ARES/EstimatedPose/1",
            "Drive/Pose_X" to "Drive/Pose_Y",
        )
        val TRUE_POSE_PAIRS = listOf(
            "ARES/SimulatorPoseFrame/0" to "ARES/SimulatorPoseFrame/1",
            "ARES/TruePose/0" to "ARES/TruePose/1",
        )
        val DRIVER_INPUT_KEYS = listOf(
            setOf("Gamepad1/LeftX", "Gamepad1/left_stick_x"),
            setOf("Gamepad1/LeftY", "Gamepad1/left_stick_y"),
            setOf("Gamepad1/RightX", "Gamepad1/right_stick_x"),
        )
        val DRIVE_MOTOR_CURRENT_TOPIC = Regex("^Drive/MotorCurrent_[^/]+$", RegexOption.IGNORE_CASE)
    }
}

internal const val RUN_START_ALIGNMENT_ID = "run-start"
internal const val AUTONOMOUS_START_ALIGNMENT_ID = "autonomous-start"

private fun String.normalizedMarkerLabel(): String = oneLineComparison()
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .take(80)

private fun String.oneLineComparison(): String = replace(Regex("[\\r\\n]+"), " ").trim()
private fun String.safeComparisonMarkdown(): String = oneLineComparison()
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("`", "&#96;")
    .replace("[", "&#91;")
    .replace("]", "&#93;")
    .replace("|", "\\|")
private fun Double.formatComparison(): String = "%.3f".format(this)
