package com.ares.analytics.service.tuning

import com.ares.analytics.service.ComparisonClaimKind
import com.ares.analytics.service.GuidedComparisonFinding
import com.ares.analytics.service.RUN_START_ALIGNMENT_ID
import com.ares.analytics.service.RunComparisonReport
import com.ares.analytics.service.RunComparisonRepository
import com.ares.analytics.service.RunComparisonRequest
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.AppJsonPretty
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.util.Sha256
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningProfileDocumentCodec
import com.areslib.tuning.TuningValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

const val GUIDED_TUNING_EXPERIMENT_SCHEMA_VERSION = 1

@Serializable
enum class ExperimentDirection { INCREASE, DECREASE }

@Serializable
enum class ExperimentMetricStatistic { MINIMUM, AVERAGE, P95, MAXIMUM }

@Serializable
enum class ExperimentMetricGoal { LOWER_IS_BETTER, HIGHER_IS_BETTER, OBSERVE_ONLY }

@Serializable
enum class ExperimentDecision { UNDECIDED, ACCEPT, REVISE, REJECT, ROLL_BACK }

@Serializable
enum class ExperimentOutcome { IMPROVED, REGRESSED, INCONCLUSIVE }

@Serializable
enum class PeerReviewState { NOT_REQUESTED, REQUESTED }

@Serializable
enum class ExperimentPhase {
    DRAFT,
    READY_FOR_SIMULATION,
    CANDIDATE_RECORDED,
    EVALUATED,
    ACCEPTED,
    REJECTED,
    REVISION_REQUESTED,
    ROLLED_BACK,
}

@Serializable
data class ExperimentValue(
    val doubleValue: Double? = null,
    val intValue: Int? = null,
    val booleanValue: Boolean? = null,
    val textValue: String? = null,
) {
    fun toTuningValue() = TuningValue(doubleValue, intValue, booleanValue, textValue)

    companion object {
        fun from(value: TuningValue) = ExperimentValue(
            doubleValue = value.doubleValue,
            intValue = value.intValue,
            booleanValue = value.booleanValue,
            textValue = value.textValue,
        )
    }
}

@Serializable
data class ExperimentFindingEvidence(
    val findingId: String,
    val claimKind: String,
    val title: String,
    val explanation: String,
    val sessionId: String,
    val absoluteTimestampMs: Long,
    val alignedTimestampMs: Long,
    val sourceTopics: List<String>,
)

@Serializable
data class ExperimentConfigurationDigest(
    val projectRelativePath: String,
    val sha256: String,
)

@Serializable
data class ExperimentProfileValue(
    val parameterUid: String,
    val key: String,
    val displayName: String,
    val unit: String,
    val value: ExperimentValue,
)

@Serializable
data class ExperimentSnapshot(
    val profileUid: String,
    val profileContentSha256: String,
    val resolvedProfileValues: List<ExperimentProfileValue>,
    val configurationFiles: List<ExperimentConfigurationDigest>,
    val snapshotSha256: String,
)

@Serializable
data class ExperimentParameterChange(
    val parameterUid: String,
    val key: String,
    val displayName: String,
    val unit: String,
    val type: String,
    val applyPolicy: String,
    val before: ExperimentValue,
    val proposed: ExperimentValue,
    val declaredMinimum: Double? = null,
    val declaredMaximum: Double? = null,
    val boundedStepLimit: Double,
)

@Serializable
data class ExperimentMetricIntent(
    val metricId: String,
    val metricLabel: String,
    val unit: String = "",
    val statistic: ExperimentMetricStatistic,
    val goal: ExperimentMetricGoal,
)

@Serializable
data class ExperimentEvaluation(
    val comparisonAlignmentId: String,
    val baselineValue: Double?,
    val candidateValue: Double?,
    val unit: String,
    val absoluteDelta: Double?,
    val percentDelta: Double?,
    val improvedIntendedMetric: Boolean?,
    val summary: String,
    val evidenceTimestampMs: Long?,
    val evidenceTopics: List<String>,
    val limitations: List<String>,
    val outcome: ExperimentOutcome = ExperimentOutcome.INCONCLUSIVE,
)

@Serializable
data class GuidedTuningExperiment(
    val schemaVersion: Int = GUIDED_TUNING_EXPERIMENT_SCHEMA_VERSION,
    val uid: String,
    val teamId: String,
    val seasonId: String,
    val robotId: String,
    val title: String,
    val hypothesis: String,
    val question: String = "",
    val heldConstants: List<String> = emptyList(),
    val successThresholdPercent: Double = 0.0,
    val safetyNotes: String = "",
    val peerReviewState: PeerReviewState = PeerReviewState.NOT_REQUESTED,
    val nextTest: String = "",
    val finding: ExperimentFindingEvidence,
    val snapshot: ExperimentSnapshot,
    val change: ExperimentParameterChange,
    val metric: ExperimentMetricIntent,
    val baselineSessionId: String,
    val candidateSessionId: String? = null,
    val phase: ExperimentPhase = ExperimentPhase.READY_FOR_SIMULATION,
    val decision: ExperimentDecision = ExperimentDecision.UNDECIDED,
    val decisionNote: String = "",
    val evaluation: ExperimentEvaluation? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class GuidedTuningExperimentSeed(
    val finding: GuidedComparisonFinding,
    val baselineSessionId: String,
    val availableMetrics: List<ExperimentMetricOption>,
)

data class GuidedTuningExperimentPlan(
    val question: String,
    val hypothesis: String,
    val heldConstants: List<String>,
    val successThresholdPercent: Double,
    val safetyNotes: String,
    val peerReviewState: PeerReviewState,
)

/** Rehydrates the evidence boundary needed by the UI without rerunning or altering old analysis. */
fun GuidedTuningExperiment.restoredSeed(): GuidedTuningExperimentSeed = GuidedTuningExperimentSeed(
    finding = GuidedComparisonFinding(
        id = finding.findingId,
        kind = runCatching { ComparisonClaimKind.valueOf(finding.claimKind) }
            .getOrDefault(ComparisonClaimKind.LIMITATION),
        title = finding.title,
        explanation = finding.explanation,
        evidence = com.ares.analytics.service.RunComparisonEvidenceLink(
            sessionId = finding.sessionId,
            absoluteTimestampMs = finding.absoluteTimestampMs,
            alignedTimeMs = finding.alignedTimestampMs,
            topics = finding.sourceTopics,
        ),
    ),
    baselineSessionId = baselineSessionId,
    availableMetrics = listOf(
        ExperimentMetricOption(
            id = metric.metricId,
            label = metric.metricLabel,
            unit = metric.unit.ifBlank { evaluation?.unit.orEmpty() },
            statistic = metric.statistic,
            goal = metric.goal,
        ),
    ),
)

data class ExperimentMetricOption(
    val id: String,
    val label: String,
    val unit: String,
    val statistic: ExperimentMetricStatistic,
    val goal: ExperimentMetricGoal,
)

data class BoundedTuningProposal(
    val declaration: TuningParameterDeclaration,
    val before: TuningValue,
    val proposed: TuningValue,
    val stepLimit: Double,
)

/**
 * Produces one deliberately small numeric change. It never guesses that a finding proves a cause;
 * the student still chooses the parameter and writes the hypothesis.
 */
object GuidedTuningProposalPolicy {
    fun propose(
        declaration: TuningParameterDeclaration,
        current: TuningValue,
        direction: ExperimentDirection,
    ): BoundedTuningProposal {
        require(declaration.applyPolicy != TuningApplyPolicy.READ_ONLY_VENDOR) {
            "${declaration.displayName} is vendor-owned and cannot be used in an experiment."
        }
        require(declaration.type == TuningParameterType.DOUBLE || declaration.type == TuningParameterType.INT) {
            "Guided experiments currently change one numeric parameter at a time."
        }
        val currentNumber = current.numericValue()
            ?: error("${declaration.displayName} does not have a resolved numeric value.")
        require(currentNumber.isFinite()) { "${declaration.displayName} must have a finite source value." }
        val declaredSpan = if (declaration.minimum != null && declaration.maximum != null) {
            requireNotNull(declaration.maximum) - requireNotNull(declaration.minimum)
        } else null
        val relativeStep = abs(currentNumber) * MAX_RELATIVE_STEP
        val spanStep = declaredSpan?.takeIf { it.isFinite() && it > 0.0 }?.times(MAX_RANGE_STEP)
        val stepLimit = max(MIN_NUMERIC_STEP, listOfNotNull(relativeStep, spanStep).minOrNull() ?: relativeStep)
        val signedStep = if (direction == ExperimentDirection.INCREASE) stepLimit else -stepLimit
        val bounded = (currentNumber + signedStep)
            .coerceAtLeast(declaration.minimum ?: -Double.MAX_VALUE)
            .coerceAtMost(declaration.maximum ?: Double.MAX_VALUE)
        require(bounded != currentNumber) {
            "${declaration.displayName} is already at its declared ${direction.name.lowercase()} limit."
        }
        val proposed = when (declaration.type) {
            TuningParameterType.INT -> {
                val integer = if (direction == ExperimentDirection.INCREASE) {
                    kotlin.math.ceil(bounded).toInt().coerceAtLeast(requireNotNull(current.intValue) + 1)
                } else {
                    kotlin.math.floor(bounded).toInt().coerceAtMost(requireNotNull(current.intValue) - 1)
                }.coerceAtLeast(declaration.minimum?.toInt() ?: Int.MIN_VALUE)
                    .coerceAtMost(declaration.maximum?.toInt() ?: Int.MAX_VALUE)
                require(integer != current.intValue) { "${declaration.displayName} cannot move one whole step within its declared bounds." }
                TuningValue(intValue = integer)
            }
            else -> TuningValue(doubleValue = bounded)
        }
        return BoundedTuningProposal(declaration, current, proposed, abs(proposed.numericValue()!! - currentNumber))
    }

    private const val MAX_RELATIVE_STEP = 0.10
    private const val MAX_RANGE_STEP = 0.05
    private const val MIN_NUMERIC_STEP = 1e-6
}

class GuidedTuningExperimentRepository(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idProvider: () -> String = { UUID.randomUUID().toString() },
) {
    fun create(
        workspace: WorkspaceConfig,
        profile: TuningProfileDocument,
        profiles: List<TuningProfileDocument>,
        declarations: List<TuningParameterDeclaration>,
        seed: GuidedTuningExperimentSeed,
        proposal: BoundedTuningProposal,
        metric: ExperimentMetricOption,
        plan: GuidedTuningExperimentPlan,
    ): GuidedTuningExperiment {
        require(workspace.projectPath.isNotBlank()) { "Choose a robot project before creating an experiment." }
        require(plan.question.isNotBlank()) { "Write the question this experiment should answer." }
        require(plan.hypothesis.isNotBlank()) { "Write what you expect this one change to improve." }
        require(plan.heldConstants.isNotEmpty() && plan.heldConstants.all(String::isNotBlank)) {
            "List at least one condition that must stay constant between runs."
        }
        require(plan.successThresholdPercent.isFinite() && plan.successThresholdPercent in 0.1..100.0) {
            "Choose a success threshold from 0.1% through 100%."
        }
        require(plan.safetyNotes.isNotBlank()) { "Record the simulator safety boundary for this experiment." }
        require(metric.id in seed.availableMetrics.map(ExperimentMetricOption::id)) {
            "Choose a metric that exists in the baseline comparison."
        }
        val snapshot = snapshot(workspace.projectPath, profile, profiles, declarations)
        val now = nowMillis()
        val finding = seed.finding
        val experiment = GuidedTuningExperiment(
            uid = "experiment-${idProvider()}",
            teamId = workspace.teamId,
            seasonId = workspace.seasonId,
            robotId = workspace.robotId,
            title = "${proposal.declaration.displayName}: controlled simulation experiment",
            hypothesis = plan.hypothesis.trim(),
            question = plan.question.trim(),
            heldConstants = plan.heldConstants.map(String::trim).filter(String::isNotBlank).distinct(),
            successThresholdPercent = plan.successThresholdPercent,
            safetyNotes = plan.safetyNotes.trim(),
            peerReviewState = plan.peerReviewState,
            finding = ExperimentFindingEvidence(
                findingId = finding.id,
                claimKind = finding.kind.name,
                title = finding.title,
                explanation = finding.explanation,
                sessionId = finding.evidence.sessionId,
                absoluteTimestampMs = finding.evidence.absoluteTimestampMs,
                alignedTimestampMs = finding.evidence.alignedTimeMs,
                sourceTopics = finding.evidence.topics,
            ),
            snapshot = snapshot,
            change = ExperimentParameterChange(
                parameterUid = proposal.declaration.uid,
                key = proposal.declaration.key,
                displayName = proposal.declaration.displayName,
                unit = proposal.declaration.unit.orEmpty(),
                type = proposal.declaration.type.name,
                applyPolicy = proposal.declaration.applyPolicy.name,
                before = ExperimentValue.from(proposal.before),
                proposed = ExperimentValue.from(proposal.proposed),
                declaredMinimum = proposal.declaration.minimum,
                declaredMaximum = proposal.declaration.maximum,
                boundedStepLimit = proposal.stepLimit,
            ),
            metric = ExperimentMetricIntent(metric.id, metric.label, metric.unit, metric.statistic, metric.goal),
            baselineSessionId = seed.baselineSessionId,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        save(workspace.projectPath, experiment)
        return experiment
    }

    fun save(projectPath: String, experiment: GuidedTuningExperiment): File {
        validate(experiment)
        val directory = experimentDirectory(projectPath)
        directory.mkdirs()
        val target = File(directory, "${safeUid(experiment.uid)}.arestuningexperiment.json")
        atomicWrite(target, AppJsonPretty.encodeToString(experiment).trimEnd() + System.lineSeparator())
        return target
    }

    fun load(projectPath: String, uid: String): GuidedTuningExperiment {
        val file = File(experimentDirectory(projectPath), "${safeUid(uid)}.arestuningexperiment.json")
        require(file.isFile) { "The selected tuning experiment no longer exists." }
        return AppJson.decodeFromString<GuidedTuningExperiment>(file.readText()).also(::validate)
    }

    fun list(projectPath: String): List<GuidedTuningExperiment> = experimentDirectory(projectPath)
        .listFiles { file -> file.isFile && file.name.endsWith(".arestuningexperiment.json") }
        .orEmpty()
        .map { file ->
            runCatching { AppJson.decodeFromString<GuidedTuningExperiment>(file.readText()).also(::validate) }
                .getOrElse { failure ->
                    throw IllegalStateException("Tuning experiment ${file.name} is unreadable. Preserve or repair it before continuing.", failure)
                }
        }
        .sortedByDescending(GuidedTuningExperiment::updatedAtEpochMs)

    fun update(projectPath: String, experiment: GuidedTuningExperiment): GuidedTuningExperiment {
        val updated = experiment.copy(updatedAtEpochMs = nowMillis())
        save(projectPath, updated)
        return updated
    }

    fun relativePath(experiment: GuidedTuningExperiment): String =
        ".ares/local/tuning/experiments/${safeUid(experiment.uid)}.arestuningexperiment.json"

    fun sha256(projectPath: String, experiment: GuidedTuningExperiment): String =
        Sha256.fileHex(File(projectPath, relativePath(experiment)))

    private fun snapshot(
        projectPath: String,
        profile: TuningProfileDocument,
        profiles: List<TuningProfileDocument>,
        declarations: List<TuningParameterDeclaration>,
    ): ExperimentSnapshot {
        val resolved = com.areslib.tuning.resolveTuningProfiles(profiles, declarations).getValue(profile.uid)
        val values = declarations.mapNotNull { declaration ->
            resolved[declaration.uid]?.let { value ->
                ExperimentProfileValue(
                    declaration.uid,
                    declaration.key,
                    declaration.displayName,
                    declaration.unit.orEmpty(),
                    ExperimentValue.from(value),
                )
            }
        }.sortedBy(ExperimentProfileValue::parameterUid)
        val digests = configurationFiles(projectPath).map { file ->
            ExperimentConfigurationDigest(
                projectRelativePath = File(projectPath).canonicalFile.toPath().relativize(file.canonicalFile.toPath())
                    .toString().replace(File.separatorChar, '/'),
                sha256 = Sha256.fileHex(file),
            )
        }.sortedBy(ExperimentConfigurationDigest::projectRelativePath)
        val profileHash = TuningProfileDocumentCodec.contentHash(profile, declarations)
        val canonical = buildString {
            appendLine(profile.uid)
            appendLine(profileHash)
            values.forEach { appendLine("${it.parameterUid}|${it.key}|${AppJson.encodeToString(it.value)}") }
            digests.forEach { appendLine("${it.projectRelativePath}|${it.sha256}") }
        }
        return ExperimentSnapshot(profile.uid, profileHash, values, digests, Sha256.hex(canonical))
    }

    private fun configurationFiles(projectPath: String): List<File> {
        val root = File(projectPath).canonicalFile
        val ares = File(root, ".ares")
        if (!ares.isDirectory) return emptyList()
        return ares.walkTopDown()
            .filter(File::isFile)
            .filter { file ->
                val relative = ares.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                CONFIGURATION_ROOTS.any { relative == it || relative.startsWith("$it/") } &&
                    !relative.startsWith("local/") && !relative.startsWith("history/")
            }
            .sortedBy(File::getPath)
            .toList()
    }

    private fun validate(experiment: GuidedTuningExperiment) {
        require(experiment.schemaVersion == GUIDED_TUNING_EXPERIMENT_SCHEMA_VERSION) { "Unsupported tuning experiment schema." }
        safeUid(experiment.uid)
        require(experiment.teamId.isNotBlank() && experiment.seasonId.isNotBlank() && experiment.robotId.isNotBlank()) {
            "The tuning experiment is missing workspace identity."
        }
        require(experiment.hypothesis.isNotBlank()) { "The tuning experiment is missing a hypothesis." }
        require(experiment.question.isNotBlank()) { "The tuning experiment is missing its controlled question." }
        require(experiment.heldConstants.isNotEmpty() && experiment.heldConstants.all(String::isNotBlank)) {
            "The tuning experiment must record at least one held constant."
        }
        require(experiment.safetyNotes.isNotBlank()) { "The tuning experiment is missing its safety boundary." }
        require(experiment.successThresholdPercent.isFinite() && experiment.successThresholdPercent in 0.1..100.0) {
            "The tuning experiment success threshold is invalid."
        }
        require(experiment.change.before != experiment.change.proposed) { "A controlled experiment must change exactly one value." }
        require(experiment.snapshot.profileContentSha256.matches(SHA256)) { "The tuning profile snapshot hash is invalid." }
        require(experiment.snapshot.snapshotSha256.matches(SHA256)) { "The configuration snapshot hash is invalid." }
        require(experiment.snapshot.configurationFiles.all { it.sha256.matches(SHA256) && !it.projectRelativePath.startsWith("/") && ".." !in it.projectRelativePath.split('/') }) {
            "The configuration snapshot contains an unsafe path or digest."
        }
    }

    private fun experimentDirectory(projectPath: String): File {
        require(projectPath.isNotBlank()) { "Choose a robot project before using tuning experiments." }
        return File(File(projectPath).canonicalFile, ".ares/local/tuning/experiments")
    }

    private fun safeUid(uid: String): String {
        require(uid.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "The tuning experiment ID is invalid." }
        return uid
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.writeText(content)
        runCatching {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
        val CONFIGURATION_ROOTS = setOf(
            "project.json",
            "drivetrains",
            "subsystems",
            "superstructures",
            "controls",
            "routines",
            "tuning",
            "tuning-components",
            "fields",
            "autonomous-catalog.json",
            "action-catalog.json",
        )
    }
}

class GuidedTuningExperimentEvaluator(
    private val comparisonRepository: RunComparisonRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun evaluate(
        workspace: WorkspaceConfig,
        experiment: GuidedTuningExperiment,
        candidateSessionId: String,
        alignmentId: String = RUN_START_ALIGNMENT_ID,
    ): Pair<GuidedTuningExperiment, RunComparisonReport> = withContext(Dispatchers.IO) {
        require(candidateSessionId.isNotBlank() && candidateSessionId != experiment.baselineSessionId) {
            "Choose a distinct simulation run as the candidate."
        }
        require(experiment.teamId == workspace.teamId && experiment.seasonId == workspace.seasonId && experiment.robotId == workspace.robotId) {
            "This experiment belongs to another workspace."
        }
        val report = comparisonRepository.compare(
            workspace,
            RunComparisonRequest(experiment.baselineSessionId, listOf(candidateSessionId), alignmentId),
        )
        val reportSessions = report.sessions.associateBy(Session::sessionId)
        require(report.primarySessionId == experiment.baselineSessionId) {
            "The comparison service returned a different baseline run."
        }
        require(reportSessions.keys.containsAll(listOf(experiment.baselineSessionId, candidateSessionId))) {
            "The comparison result is missing the baseline or candidate run."
        }
        require(report.sessions.all { session ->
            session.teamId == workspace.teamId && session.seasonId == workspace.seasonId && session.robotId == workspace.robotId
        }) {
            "The comparison result contains a run from another workspace."
        }
        val candidateSession = requireNotNull(reportSessions[candidateSessionId])
        require(candidateSession.tags.any { it.equals(SIMULATION_EVIDENCE_TAG, ignoreCase = true) }) {
            "The candidate is not a Studio-recorded Local Sim run. Live or imported runs cannot be substituted."
        }
        require(candidateSession.createdAt >= experiment.createdAtEpochMs) {
            "The candidate run predates the experiment snapshot. Record a new simulation run after staging the candidate."
        }
        val metric = report.metrics.firstOrNull { it.id == experiment.metric.metricId }
        require(metric == null || experiment.metric.unit.isBlank() || metric.unit.isBlank() || metric.unit == experiment.metric.unit) {
            "The candidate metric unit changed from ${experiment.metric.unit} to ${metric?.unit}; choose compatible evidence."
        }
        val baseline = metric?.series?.firstOrNull { it.sessionId == experiment.baselineSessionId }
        val candidate = metric?.series?.firstOrNull { it.sessionId == candidateSessionId }
        val before = baseline?.summary?.statistic(experiment.metric.statistic)
        val after = candidate?.summary?.statistic(experiment.metric.statistic)
        val delta = if (before != null && after != null) after - before else null
        val percent = if (delta != null && abs(before!!) > 1e-12) delta / abs(before) * 100.0 else null
        val improvementPercent = when {
            before == null || after == null || abs(before) <= 1e-12 || experiment.metric.goal == ExperimentMetricGoal.OBSERVE_ONLY -> null
            experiment.metric.goal == ExperimentMetricGoal.LOWER_IS_BETTER -> (before - after) / abs(before) * 100.0
            else -> (after - before) / abs(before) * 100.0
        }
        val outcome = when {
            improvementPercent == null -> ExperimentOutcome.INCONCLUSIVE
            improvementPercent >= experiment.successThresholdPercent -> ExperimentOutcome.IMPROVED
            improvementPercent < 0.0 -> ExperimentOutcome.REGRESSED
            else -> ExperimentOutcome.INCONCLUSIVE
        }
        val improved = when (outcome) {
            ExperimentOutcome.IMPROVED -> true
            ExperimentOutcome.REGRESSED -> false
            ExperimentOutcome.INCONCLUSIVE -> null
        }
        val evidence = report.findings.firstOrNull { it.evidence.sessionId == candidateSessionId }
        val evaluation = ExperimentEvaluation(
            comparisonAlignmentId = report.selectedAlignment.id,
            baselineValue = before,
            candidateValue = after,
            unit = metric?.unit.orEmpty(),
            absoluteDelta = delta,
            percentDelta = percent,
            improvedIntendedMetric = improved,
            summary = when (outcome) {
                ExperimentOutcome.IMPROVED -> "The candidate met the declared ${experiment.successThresholdPercent}% improvement threshold. This supports the experiment result but does not prove the parameter caused it."
                ExperimentOutcome.REGRESSED -> "The candidate moved the selected recorded metric in the wrong direction. Reject, revise, or roll back before another test."
                ExperimentOutcome.INCONCLUSIVE -> when {
                    improvementPercent != null -> "The candidate changed in the intended direction but did not meet the declared ${experiment.successThresholdPercent}% threshold. The result is inconclusive."
                    else -> "ARES could not make a directional improvement claim from the selected metric; inspect the evidence and missing signals."
                }
            },
            evidenceTimestampMs = evidence?.evidence?.absoluteTimestampMs,
            evidenceTopics = evidence?.evidence?.topics.orEmpty(),
            limitations = (report.limitations + listOf(
                "A one-factor experiment reduces confounding but does not prove causation.",
                "Simulation evidence does not certify physical hardware safety or performance.",
                "Canonical tuning remains unchanged until a separate reviewed promotion.",
            )).distinct(),
            outcome = outcome,
        )
        experiment.copy(
            candidateSessionId = candidateSessionId,
            phase = ExperimentPhase.EVALUATED,
            evaluation = evaluation,
            updatedAtEpochMs = nowMillis(),
        ) to report
    }
}

fun RunComparisonReport.toExperimentMetricOptions(): List<ExperimentMetricOption> = metrics.map { metric ->
    val (statistic, goal) = when (metric.id) {
        "battery_voltage" -> ExperimentMetricStatistic.MINIMUM to ExperimentMetricGoal.HIGHER_IS_BETTER
        "loop_time", "total_motor_current", "localization_error", "mechanism_tracking_error" ->
            ExperimentMetricStatistic.P95 to ExperimentMetricGoal.LOWER_IS_BETTER
        else -> if (metric.id.contains("current", ignoreCase = true) || metric.label.contains("current", ignoreCase = true)) {
            ExperimentMetricStatistic.P95 to ExperimentMetricGoal.LOWER_IS_BETTER
        } else {
            ExperimentMetricStatistic.AVERAGE to ExperimentMetricGoal.OBSERVE_ONLY
        }
    }
    ExperimentMetricOption(metric.id, metric.label, metric.unit, statistic, goal)
}

fun GuidedComparisonFinding.toExperimentSeed(report: RunComparisonReport): GuidedTuningExperimentSeed =
    GuidedTuningExperimentSeed(this, report.primarySessionId, report.toExperimentMetricOptions())

fun GuidedTuningExperiment.belongsTo(workspace: WorkspaceConfig): Boolean =
    teamId == workspace.teamId && seasonId == workspace.seasonId && robotId == workspace.robotId

fun GuidedTuningExperiment.canAcceptSimulationResult(): Boolean =
    evaluation?.improvedIntendedMetric == true &&
        evaluation.baselineValue?.isFinite() == true &&
        evaluation.candidateValue?.isFinite() == true

fun GuidedTuningExperiment.candidateRuns(sessions: List<Session>): List<Session> = sessions
    .filter { session ->
        session.teamId == teamId &&
            session.seasonId == seasonId &&
            session.robotId == robotId &&
            session.sessionId != baselineSessionId &&
            session.createdAt >= createdAtEpochMs &&
            session.tags.any { it.equals(SIMULATION_EVIDENCE_TAG, ignoreCase = true) }
    }
    .sortedByDescending(Session::createdAt)

const val SIMULATION_EVIDENCE_TAG = "simulation"

private fun com.ares.analytics.service.RunMetricSummary.statistic(statistic: ExperimentMetricStatistic): Double = when (statistic) {
    ExperimentMetricStatistic.MINIMUM -> minimum
    ExperimentMetricStatistic.AVERAGE -> average
    ExperimentMetricStatistic.P95 -> p95
    ExperimentMetricStatistic.MAXIMUM -> maximum
}
