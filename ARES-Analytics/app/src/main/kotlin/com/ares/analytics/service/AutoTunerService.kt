package com.ares.analytics.service

import com.areslib.control.assist.SysIdMechanism
import com.ares.analytics.service.tuning.TuningParameterKeys
import com.ares.analytics.shared.models.CalculatedSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import com.ares.analytics.service.tuning.ExternalTuningProposal
import com.ares.analytics.service.tuning.TuningProposalInbox
import kotlin.math.abs
import kotlin.math.max

data class AutoTunerPIDFGains(
    val kP: Double,
    val kI: Double,
    val kD: Double,
    val kF: Double = 0.0
)

enum class RecommendationQuality { READY, REVIEW_REQUIRED, REJECTED }

enum class TuningApplyPhase {
    IDLE,
    RECOMMENDED,
    APPLIED_AWAITING_VALIDATION,
    VALIDATED,
    ROLLED_BACK,
    FAILED
}

data class StepResponseMetrics(
    val riseTimeMs: Double = Double.NaN,
    val percentOvershoot: Double = Double.NaN,
    val settlingTimeMs: Double = Double.NaN,
    val deadTimeMs: Double = Double.NaN,
    val timeConstantMs: Double = Double.NaN,
    val processGain: Double = Double.NaN,
    val modelFit: Double = 0.0
) {
    val isUsable: Boolean
        get() = processGain.isFinite() && processGain > 1e-6 &&
            timeConstantMs.isFinite() && timeConstantMs > 0.0 &&
            deadTimeMs.isFinite() && deadTimeMs >= 0.0 &&
            riseTimeMs.isFinite() && riseTimeMs > 0.0 &&
            settlingTimeMs.isFinite() && settlingTimeMs >= 0.0 &&
            percentOvershoot.isFinite() && percentOvershoot >= 0.0 && modelFit in 0.5..1.0
}

data class TuningApplyState(
    val phase: TuningApplyPhase = TuningApplyPhase.IDLE,
    val message: String = "",
    val appliedValues: Map<String, Double> = emptyMap(),
    val previousValues: Map<String, Double> = emptyMap()
)

/**
 * Converts measured SysId and step-response samples into reviewable tuning recommendations.
 * Feedforward coefficients come from [SysIdService]'s OLS fit. Feedback gains come from an
 * identified first-order-plus-dead-time plant and conservative SIMC PI tuning, never fixed constants.
 */
class AutoTunerService(
    private val nt4ClientService: Nt4ClientService,
    private val sysIdService: SysIdService,
    private val proposalInbox: TuningProposalInbox = TuningProposalInbox()
) {
    data class TuningRecommendation(
        val mechanism: SysIdMechanism,
        val mechanismName: String,
        val recommendedGains: AutoTunerPIDFGains,
        val recommendedkS: Double,
        val recommendedkV: Double,
        val recommendedkA: Double,
        val riseTimeMs: Double,
        val percentOvershoot: Double,
        val settlingTimeMs: Double,
        val logSource: String,
        val confidence: Double,
        val quality: RecommendationQuality,
        val rSquared: Double,
        val stepMetrics: StepResponseMetrics,
        val warnings: List<String>,
        val dataQuality: AutoTuningDataQuality,
        val safetyEnvelope: MechanismGainEnvelope,
        val topicValues: Map<String, Double>,
        val studentApproved: Boolean = false
    )

    private val _currentRecommendation = MutableStateFlow<TuningRecommendation?>(null)
    val currentRecommendation: StateFlow<TuningRecommendation?> = _currentRecommendation

    private val _applyState = MutableStateFlow(TuningApplyState())
    val applyState: StateFlow<TuningApplyState> = _applyState

    fun analyzeSamples(
        mechanism: SysIdMechanism,
        samples: List<AlignedDataRow>,
        source: String = "live-nt4"
    ): TuningRecommendation? {
        val analysis = computeSampleAnalysis(mechanism, samples, source)
        publishRecommendation(analysis.recommendation)
        return analysis.recommendation
    }

    internal data class SampleAnalysis(val summary: CalculatedSummary, val recommendation: TuningRecommendation?)

    /** Computes one fit without publishing state; callers may discard obsolete run results. */
    internal fun computeSampleAnalysis(
        mechanism: SysIdMechanism,
        samples: List<AlignedDataRow>,
        source: String = "live-nt4"
    ): SampleAnalysis {
        val prepared = PreparedSysIdData.from(samples)
        val dataQuality = AutoTuningSafetyPolicy.assessPreparedData(mechanism, prepared)
        val finite = prepared.rows
        val summary = sysIdService.analyzePreparedData(prepared)
        if (finite.size < MIN_RECOMMENDATION_SAMPLES) return SampleAnalysis(summary, null)
        val dcGain = if (summary.rSquared >= MIN_REVIEW_R2 && summary.kV > 0.0) (1.0 / summary.kV).takeIf { it.isFinite() } else null
        val metrics = StepResponseAnalysis.identify(finite, dcGain)
        val gains = StepResponseAnalysis.gains(metrics)
        val envelope = AutoTuningSafetyPolicy.envelopeFor(mechanism)
        val needsFeedback = mechanism == SysIdMechanism.FLYWHEEL
        val envelopeViolations = envelope.violations(summary.kS, summary.kV, summary.kA,
            if (needsFeedback) gains else NO_FEEDBACK)
        val topicValues = buildTopicValues(mechanism, summary.kS, summary.kV, summary.kA, gains)
        val warnings = dataQuality.warnings.toMutableList()
        if (topicValues.isEmpty()) warnings += "This mechanism requires a dedicated model and declaration mapping; gravity or custom mechanism gains cannot target drive/flywheel parameters."
        if (mechanism == SysIdMechanism.LINEAR || mechanism == SysIdMechanism.ANGULAR) {
            warnings += "Feedback gains model voltage-to-velocity control; drivetrain proposals contain only feedforward coefficients."
        }
        warnings += dataQuality.blockers

        if (summary.rSquared < MIN_REVIEW_R2) warnings += "Feedforward fit is below the minimum R² of $MIN_REVIEW_R2."
        if (!metrics.isUsable) warnings += "No clean step response was found; feedback gains were not recommended."
        if (summary.kV <= 0.0 || summary.kA < 0.0) warnings += "Identified kV/kA signs are physically implausible."
        warnings += envelopeViolations
        val confidence = if (needsFeedback) {
            0.60 * summary.rSquared + 0.20 * metrics.modelFit + 0.20 * dataQuality.score
        } else {
            // Normalize the weights of the two models actually used by a feedforward proposal.
            0.75 * summary.rSquared + 0.25 * dataQuality.score
        }.let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.0 }
        val physicallyValid = summary.kS.isFinite() && summary.kV.isFinite() && summary.kA.isFinite() &&
            summary.kV > 0.0 && summary.kA >= 0.0
        val quality = when {
            topicValues.isEmpty() || !dataQuality.passed || envelopeViolations.isNotEmpty() || (needsFeedback && !metrics.isUsable) ||
                !physicallyValid || summary.rSquared !in MIN_REJECT_R2..1.0 -> RecommendationQuality.REJECTED
            confidence >= READY_CONFIDENCE && summary.rSquared >= MIN_REVIEW_R2 -> RecommendationQuality.READY
            else -> RecommendationQuality.REVIEW_REQUIRED
        }

        val recommendation = TuningRecommendation(
            mechanism = mechanism,
            mechanismName = mechanism.name.lowercase(),
            recommendedGains = gains,
            recommendedkS = summary.kS,
            recommendedkV = summary.kV,
            recommendedkA = summary.kA,
            riseTimeMs = metrics.riseTimeMs,
            percentOvershoot = metrics.percentOvershoot,
            settlingTimeMs = metrics.settlingTimeMs,
            logSource = source,
            confidence = confidence,
            quality = quality,
            rSquared = summary.rSquared,
            stepMetrics = metrics,
            warnings = warnings,
            dataQuality = dataQuality,
            safetyEnvelope = envelope,
            topicValues = topicValues
        )
        return SampleAnalysis(summary, recommendation)
    }

    internal fun publishRecommendation(recommendation: TuningRecommendation?) {
        _currentRecommendation.value = recommendation
        if (_applyState.value.phase != TuningApplyPhase.APPLIED_AWAITING_VALIDATION) {
            _applyState.value = TuningApplyState(
                phase = if (recommendation == null) TuningApplyPhase.IDLE else TuningApplyPhase.RECOMMENDED,
                message = if (recommendation == null) "" else "Recommendation ready for review."
            )
        }
    }

    /** Parses structured JSONL or CSV exports. Binary WPILOG files must first use the existing decoder. */
    fun analyzeLogFile(logFile: File, mechanism: SysIdMechanism = SysIdMechanism.LINEAR): TuningRecommendation? {
        publishRecommendation(null)
        if (!logFile.isFile || logFile.length() <= 0L || logFile.extension.equals("wpilog", true)) return null
        val rows = SysIdLogParser.parse(logFile.readText())
        return analyzeSamples(mechanism, rows, logFile.name)
    }

    suspend fun approveAndApplyGains(rec: TuningRecommendation) {
        if (rec.logSource.startsWith("digital-twin:")) {
            _applyState.value = TuningApplyState(
                TuningApplyPhase.FAILED,
                "Simulation taught the workflow but did not measure this robot. Run a recorded, safely armed SysId experiment before creating a robot tuning proposal.",
            )
            return
        }
        val envelopeViolations = AutoTuningSafetyPolicy.envelopeFor(rec.mechanism).violations(
            rec.recommendedkS, rec.recommendedkV, rec.recommendedkA,
            if (rec.mechanism == SysIdMechanism.FLYWHEEL) rec.recommendedGains else NO_FEEDBACK
        )
        if (rec.quality == RecommendationQuality.REJECTED || !rec.dataQuality.passed ||
            envelopeViolations.isNotEmpty() || rec.topicValues.isEmpty() ||
            rec.topicValues != buildTopicValues(rec.mechanism, rec.recommendedkS, rec.recommendedkV, rec.recommendedkA, rec.recommendedGains) ||
            rec.confidence !in 0.0..1.0 || rec.rSquared !in MIN_REJECT_R2..1.0 ||
            (rec.mechanism == SysIdMechanism.FLYWHEEL && !rec.stepMetrics.isUsable)
        ) {
            _applyState.value = TuningApplyState(TuningApplyPhase.FAILED, "Rejected recommendations cannot be applied.")
            return
        }
        val accepted = proposalInbox.submit(
            ExternalTuningProposal(
                source = "AutoTuner",
                summary = "${rec.mechanismName} recommendation from ${rec.logSource}; confidence ${"%.1f".format(rec.confidence * 100)}%.",
                values = rec.topicValues,
                evidencePath = null,
                evidenceSha256 = null
            )
        )
        _applyState.value = TuningApplyState(
            phase = if (accepted) TuningApplyPhase.RECOMMENDED else TuningApplyPhase.FAILED,
            message = if (accepted) "Queued for the Tuning proposal board. Open a project profile to review validation, policy, provenance, and diff before any live test or profile promotion."
                else "The Tuning proposal inbox is full or the proposal is invalid. Review pending proposals, then retry."
        )
    }

    /** Legacy state evaluator retained for reports; proposals themselves never apply robot values. */
    suspend fun validateOrRollback(validation: TuningRecommendation): Boolean {
        val state = _applyState.value
        if (state.phase != TuningApplyPhase.APPLIED_AWAITING_VALIDATION) return false
        val comparable = state.appliedValues.keys.intersect(validation.topicValues.keys)
        val maxRelativeDrift = comparable.maxOfOrNull { topic ->
            val applied = state.appliedValues.getValue(topic)
            val observed = validation.topicValues.getValue(topic)
            abs(observed - applied) / max(abs(applied), 1e-6)
        } ?: Double.POSITIVE_INFINITY
        val passed = validation.rSquared >= MIN_VALIDATION_R2 && maxRelativeDrift <= MAX_VALIDATION_DRIFT &&
            validation.quality != RecommendationQuality.REJECTED
        if (passed) {
            _applyState.value = state.copy(
                phase = TuningApplyPhase.VALIDATED,
                message = "Validated: R²=${"%.3f".format(validation.rSquared)}, max drift=${"%.1f".format(maxRelativeDrift * 100.0)}%."
            )
            return true
        }
        rollback("Validation failed; proposal remains un-applied.")
        return false
    }

    suspend fun rollback(reason: String = "Proposal discarded; no robot values were changed.") {
        val state = _applyState.value
        _applyState.value = state.copy(phase = TuningApplyPhase.ROLLED_BACK, message = reason)
    }

    private fun buildTopicValues(
        mechanism: SysIdMechanism,
        kS: Double,
        kV: Double,
        kA: Double,
        gains: AutoTunerPIDFGains
    ): Map<String, Double> = when (mechanism) {
        SysIdMechanism.LINEAR -> linkedMapOf(
            TuningParameterKeys.DRIVE_FEEDFORWARD_KS to kS,
            TuningParameterKeys.DRIVE_FEEDFORWARD_KV to kV,
            TuningParameterKeys.DRIVE_FEEDFORWARD_KA to kA,
        )
        SysIdMechanism.ANGULAR -> linkedMapOf(
            TuningParameterKeys.DRIVE_ANGULAR_FEEDFORWARD_KS to kS,
            TuningParameterKeys.DRIVE_ANGULAR_FEEDFORWARD_KV to kV,
            TuningParameterKeys.DRIVE_ANGULAR_FEEDFORWARD_KA to kA,
        )
        SysIdMechanism.FLYWHEEL -> linkedMapOf(
            TuningParameterKeys.FLYWHEEL_FEEDFORWARD_KS to kS,
            TuningParameterKeys.FLYWHEEL_FEEDFORWARD_KV to kV,
            TuningParameterKeys.FLYWHEEL_FEEDFORWARD_KA to kA,
            TuningParameterKeys.FLYWHEEL_VELOCITY_KP to gains.kP,
            TuningParameterKeys.FLYWHEEL_VELOCITY_KI to gains.kI,
            TuningParameterKeys.FLYWHEEL_VELOCITY_KD to gains.kD
        )
        SysIdMechanism.ELEVATOR, SysIdMechanism.ARM, SysIdMechanism.CUSTOM -> emptyMap()
    }

    companion object {
        private val NO_FEEDBACK = AutoTunerPIDFGains(0.0, 0.0, 0.0)
        private const val MIN_RECOMMENDATION_SAMPLES = 20
        private const val MIN_REVIEW_R2 = 0.70
        private const val MIN_REJECT_R2 = 0.45
        private const val MIN_VALIDATION_R2 = 0.75
        private const val MAX_VALIDATION_DRIFT = 0.25
        private const val READY_CONFIDENCE = 0.78
    }
}
