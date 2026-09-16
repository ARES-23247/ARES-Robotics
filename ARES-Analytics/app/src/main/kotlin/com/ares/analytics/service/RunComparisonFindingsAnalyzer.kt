package com.ares.analytics.service

import kotlin.math.abs

internal object RunComparisonFindingsAnalyzer {
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

    private enum class EvidenceExtreme { MINIMUM, MAXIMUM }

    private data class MaterialDifference(
        val evidence: RunComparisonSeries,
        val extreme: EvidenceExtreme,
        val explanation: String,
    )

    fun buildFindings(
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

    private fun Double.formatComparison(): String = "%.3f".format(this)
}
