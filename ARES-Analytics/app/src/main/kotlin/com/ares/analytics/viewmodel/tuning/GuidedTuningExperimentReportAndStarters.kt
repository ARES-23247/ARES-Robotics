package com.ares.analytics.viewmodel.tuning

import com.ares.analytics.service.tuning.ExperimentDirection
import com.ares.analytics.service.tuning.ExperimentValue
import com.ares.analytics.service.tuning.GuidedTuningExperiment
import com.ares.analytics.service.tuning.GuidedTuningProposalPolicy
import com.ares.analytics.service.tuning.ResolvedTuningValue
import com.ares.analytics.shared.models.Session
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Interactive 1-Click "Symptom-to-Experiment" starters for novice students in Step 7 (PID & Feedforward Tuning).
 *
 * Pre-fills the Guided Tuning Experiment question, testable hypothesis, direction, matching parameter,
 * and evaluation metric without auto-starting capture until the student confirms.
 */
enum class StudentSymptomExperimentStarter(
    val title: String,
    val symptomSummary: String,
    val recommendedActionLabel: String,
    val preferredGainSuffixes: List<String>,
    val direction: ExperimentDirection,
    val preferredMetricId: String,
    val question: String,
    val hypothesis: String,
) {
    STOPS_SHORT_SLUGGISH(
        title = "Mechanism Stops Short / Sluggish",
        symptomSummary = "Reaches near the target slowly or stops slightly short of the setpoint.",
        recommendedActionLabel = "Increase kP (+1 bounded step)",
        preferredGainSuffixes = listOf("kp", "k_p", "proportional"),
        direction = ExperimentDirection.INCREASE,
        preferredMetricId = "mechanism_tracking_error",
        question = "Will a small bounded increase in proportional gain (kP) reduce steady-state tracking error without causing overshoot?",
        hypothesis = "Increasing kP by one bounded step will reduce P95 mechanism tracking error by at least 5% during the same routine.",
    ),
    OSCILLATES_AROUND_TARGET(
        title = "Oscillates / Shakes Around Target",
        symptomSummary = "Overshoots and bounces back and forth around the target angle or position.",
        recommendedActionLabel = "Increase kD (+1 bounded step)",
        preferredGainSuffixes = listOf("kd", "k_d", "derivative", "kp", "k_p"),
        direction = ExperimentDirection.INCREASE,
        preferredMetricId = "mechanism_tracking_error",
        question = "Will adding derivative damping (kD) reduce oscillation and overshoot around the setpoint?",
        hypothesis = "Increasing kD by one bounded step will damp oscillation and lower P95 mechanism tracking error by at least 5%.",
    ),
    ARM_ELEVATOR_GRAVITY_DROOP(
        title = "Arm / Elevator Droops Under Gravity",
        symptomSummary = "Sags downward when holding a horizontal arm angle or raised elevator height.",
        recommendedActionLabel = "Increase kG (+1 bounded step)",
        preferredGainSuffixes = listOf("kg", "k_g", "gravity", "ks", "kp"),
        direction = ExperimentDirection.INCREASE,
        preferredMetricId = "mechanism_tracking_error",
        question = "Will increasing gravity feedforward (kG) hold the mechanism closer to its target against gravity?",
        hypothesis = "Increasing kG by one bounded step will compensate for gravity torque and reduce P95 tracking error by at least 5%.",
    ),
    SLOW_FLYWHEEL_RECOVERY(
        title = "Slow Flywheel / Launch Recovery",
        symptomSummary = "Shooter wheel or velocity mechanism takes too long to spin back up to target speed.",
        recommendedActionLabel = "Increase kV (+1 bounded step)",
        preferredGainSuffixes = listOf("kv", "k_v", "velocity", "kp", "k_p"),
        direction = ExperimentDirection.INCREASE,
        preferredMetricId = "mechanism_tracking_error",
        question = "Will increasing velocity feedforward (kV) help the mechanism reach and hold target speed with less lag?",
        hypothesis = "Increasing kV by one bounded step will reduce velocity tracking error by at least 5% across the test routine.",
    ),
}

fun applySymptomStarterToExperimentState(
    state: GuidedTuningExperimentState,
    starter: StudentSymptomExperimentStarter,
    editableRows: List<ResolvedTuningValue>,
): GuidedTuningExperimentState {
    val matchedRow = starter.preferredGainSuffixes.firstNotNullOfOrNull { suffix ->
        editableRows.firstOrNull { row ->
            val uidLower = row.declaration.uid.lowercase()
            val keyLower = row.declaration.key.lowercase()
            val nameLower = row.declaration.displayName.lowercase()
            uidLower.endsWith(suffix) ||
                keyLower.endsWith(suffix) ||
                uidLower.contains(".$suffix") ||
                keyLower.contains("/$suffix") ||
                nameLower.endsWith(" $suffix")
        }
    } ?: editableRows.firstOrNull { it.declaration.uid == state.parameterUid } ?: editableRows.firstOrNull()

    val selectedUid = matchedRow?.declaration?.uid ?: state.parameterUid
    val availableMetrics = state.seed?.availableMetrics.orEmpty()
    val selectedMetricId = availableMetrics.firstOrNull { it.id == starter.preferredMetricId }?.id
        ?: state.metricId
        ?: availableMetrics.firstOrNull()?.id

    val preview = matchedRow?.sourceTypedValue?.let { value ->
        runCatching { GuidedTuningProposalPolicy.propose(matchedRow.declaration, value, starter.direction) }
            .getOrNull()
    }

    return state.copy(
        parameterUid = selectedUid,
        direction = starter.direction,
        metricId = selectedMetricId,
        question = starter.question,
        hypothesis = starter.hypothesis,
        proposalPreview = preview ?: state.proposalPreview,
        statusMessage = "Applied '${starter.title}' starter (${starter.recommendedActionLabel}). Review the prediction, then click Create Snapshot & Stage Candidate when ready.",
        errorMessage = if (matchedRow == null) "This project has no editable numeric tuning declarations." else null,
    )
}

internal fun renderGuidedTuningExperimentReport(experiment: GuidedTuningExperiment): String = buildString {
    appendLine("# ARES guided tuning experiment")
    appendLine()
    appendLine("Experiment: ${experiment.title}")
    appendLine("Workspace: ${experiment.teamId} / ${experiment.seasonId} / ${experiment.robotId}")
    appendLine("Configuration snapshot: ${experiment.snapshot.snapshotSha256}")
    appendLine("Canonical profile: ${experiment.snapshot.profileUid} @ ${experiment.snapshot.profileContentSha256}")
    appendLine()
    appendLine("> This report records simulation evidence. It is not proof of causation or physical-hardware validation.")
    appendLine()
    appendLine("## Evidence and uncertainty")
    appendLine("- ${experiment.finding.claimKind}: ${experiment.finding.title}")
    appendLine("- ${experiment.finding.explanation}")
    appendLine("- Replay: ${experiment.finding.sessionId} at ${experiment.finding.absoluteTimestampMs} ms")
    appendLine("- Topics: ${experiment.finding.sourceTopics.joinToString()}")
    appendLine()
    appendLine("## Controlled change")
    appendLine("- Question: ${experiment.question.ifBlank { "What effect will the one bounded change have on the intended metric?" }}")
    appendLine("- Hypothesis: ${experiment.hypothesis}")
    appendLine("- Parameter: ${experiment.change.displayName} (${experiment.change.key})")
    appendLine("- Before: ${experiment.change.before.display()} ${experiment.change.unit}")
    appendLine("- Candidate: ${experiment.change.proposed.display()} ${experiment.change.unit}")
    appendLine("- Maximum bounded step used: ${experiment.change.boundedStepLimit.reportNumber()} ${experiment.change.unit}")
    appendLine("- Apply policy: ${experiment.change.applyPolicy}")
    appendLine("- Success threshold: ${experiment.successThresholdPercent.reportNumber()}% improvement")
    appendLine("- Held constant:")
    experiment.heldConstants.forEach { appendLine("  - $it") }
    appendLine("- Safety boundary: ${experiment.safetyNotes.ifBlank { "Local Sim only; physical validation requires a separate documented safety procedure." }}")
    appendLine("- Optional peer review: ${experiment.peerReviewState.name.replace('_', ' ')}")
    appendLine()
    appendLine("## Baseline and candidate")
    appendLine("- Baseline run: ${experiment.baselineSessionId}")
    appendLine("- Candidate run: ${experiment.candidateSessionId ?: "not recorded"}")
    appendLine("- Intended metric: ${experiment.metric.metricLabel} / ${experiment.metric.statistic} / ${experiment.metric.goal}")
    experiment.evaluation?.let { evaluation ->
        appendLine("- Baseline value: ${evaluation.baselineValue.reportNumber()} ${evaluation.unit}")
        appendLine("- Candidate value: ${evaluation.candidateValue.reportNumber()} ${evaluation.unit}")
        appendLine("- Delta: ${evaluation.absoluteDelta.reportNumber()} ${evaluation.unit} (${evaluation.percentDelta.reportNumber()}%)")
        appendLine("- Result: ${evaluation.summary}")
        appendLine("- Outcome: ${evaluation.outcome}")
        appendLine()
        appendLine("## Limitations")
        evaluation.limitations.forEach { appendLine("- $it") }
    }
    appendLine()
    appendLine("## Student/mentor decision")
    appendLine("- Decision: ${experiment.decision}")
    appendLine("- Reason: ${experiment.decisionNote.ifBlank { "Not recorded" }}")
    appendLine("- Next safe test or review: ${experiment.nextTest.ifBlank { "Not recorded" }}")
    appendLine("- Rollback path: stop the managed simulator and remove the local proposal; reload the unchanged canonical profile before another run.")
    appendLine("- Canonical profile changed by this workflow: no")
}

private val guidedExperimentTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a")

fun Session.guidedExperimentLabel(): String {
    val recordedAt = guidedExperimentTimeFormatter.format(Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()))
    return "Simulation · $recordedAt · ${sessionId.take(8)}"
}

private fun ExperimentValue.display(): String = when {
    doubleValue != null -> doubleValue.reportNumber()
    intValue != null -> intValue.toString()
    booleanValue != null -> booleanValue.toString()
    else -> textValue.orEmpty()
}

private fun Double?.reportNumber(): String = when {
    this == null || !isFinite() -> "unavailable"
    else -> BigDecimal.valueOf(this)
        .setScale(6, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}
