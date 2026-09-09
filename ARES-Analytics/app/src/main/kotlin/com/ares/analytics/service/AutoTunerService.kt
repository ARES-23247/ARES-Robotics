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
        get() = processGain.isFinite() && abs(processGain) > 1e-6 &&
            timeConstantMs.isFinite() && timeConstantMs > 0.0
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
 * identified first-order-plus-dead-time plant and conservative IMC tuning, never fixed constants.
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
        val metrics = identifyStepResponse(finite)
        val gains = calculateImcGains(metrics)
        val envelope = AutoTuningSafetyPolicy.envelopeFor(mechanism)
        val envelopeViolations = envelope.violations(summary.kS, summary.kV, summary.kA, gains)
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
        val confidence = (0.60 * summary.rSquared.coerceIn(0.0, 1.0) +
            0.20 * metrics.modelFit.coerceIn(0.0, 1.0) + 0.20 * dataQuality.score).coerceIn(0.0, 1.0)
        val physicallyValid = summary.kS.isFinite() && summary.kV.isFinite() && summary.kA.isFinite() &&
            summary.kV > 0.0 && summary.kA >= 0.0
        val quality = when {
            topicValues.isEmpty() || !dataQuality.passed || envelopeViolations.isNotEmpty() || !metrics.isUsable ||
                !physicallyValid || summary.rSquared < MIN_REJECT_R2 -> RecommendationQuality.REJECTED
            confidence >= READY_CONFIDENCE && metrics.isUsable -> RecommendationQuality.READY
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
            rec.recommendedkS, rec.recommendedkV, rec.recommendedkA, rec.recommendedGains
        )
        if (rec.quality == RecommendationQuality.REJECTED || !rec.dataQuality.passed ||
            envelopeViolations.isNotEmpty() || rec.topicValues.isEmpty() ||
            rec.topicValues != buildTopicValues(rec.mechanism, rec.recommendedkS, rec.recommendedkV, rec.recommendedkA, rec.recommendedGains) ||
            rec.confidence !in 0.0..1.0 || rec.rSquared !in MIN_REJECT_R2..1.0 || !rec.stepMetrics.isUsable
        ) {
            _applyState.value = TuningApplyState(TuningApplyPhase.FAILED, "Rejected recommendations cannot be applied.")
            return
        }
        proposalInbox.submit(
            ExternalTuningProposal(
                source = "AutoTuner",
                summary = "${rec.mechanismName} recommendation from ${rec.logSource}; confidence ${"%.1f".format(rec.confidence * 100)}%.",
                values = rec.topicValues,
                evidencePath = null,
                evidenceSha256 = null
            )
        )
        _applyState.value = TuningApplyState(
            phase = TuningApplyPhase.RECOMMENDED,
            message = "Sent to the Tuning proposal board. Review validation, policy, provenance, and diff before any live test or profile promotion."
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

    private fun calculateImcGains(metrics: StepResponseMetrics): AutoTunerPIDFGains {
        if (!metrics.isUsable) return AutoTunerPIDFGains(0.0, 0.0, 0.0)
        val tau = metrics.timeConstantMs / 1000.0
        val deadTime = max(metrics.deadTimeMs.takeIf { it.isFinite() }?.div(1000.0) ?: 0.0, 0.001)
        val processGain = abs(metrics.processGain)
        val lambda = max(tau * 0.65, deadTime * 3.0)
        val kP = (tau / (processGain * (lambda + deadTime))).coerceIn(0.0, MAX_KP)
        val integralTime = tau + deadTime * 0.5
        val kI = if (integralTime > 1e-6) (kP / integralTime).coerceIn(0.0, MAX_KI) else 0.0
        val kD = (kP * tau * deadTime / (2.0 * tau + deadTime)).coerceIn(0.0, MAX_KD)
        return AutoTunerPIDFGains(kP, kI, kD)
    }

    private fun identifyStepResponse(data: List<AlignedDataRow>): StepResponseMetrics {
        if (data.size < MIN_RECOMMENDATION_SAMPLES) return StepResponseMetrics()
        var stepIndex = -1
        var largestStep = 0.0
        for (i in 1 until data.size) {
            val delta = abs(data[i].voltage - data[i - 1].voltage)
            if (delta > largestStep) {
                largestStep = delta
                stepIndex = i
            }
        }
        if (stepIndex < 1 || largestStep < MIN_STEP_VOLTS || data.size - stepIndex < 8) return StepResponseMetrics()

        val baselineWindow = data.subList(max(0, stepIndex - 8), stepIndex)
        val tailWindow = data.subList(max(stepIndex + 1, data.size - 10), data.size)
        val baselineVelocity = baselineWindow.map { it.velocity }.average()
        val targetVelocity = tailWindow.map { it.velocity }.average()
        val inputBefore = baselineWindow.map { it.voltage }.average()
        val inputAfter = data.subList(stepIndex, minOf(data.size, stepIndex + 8)).map { it.voltage }.average()
        val responseDelta = targetVelocity - baselineVelocity
        val inputDelta = inputAfter - inputBefore
        if (abs(responseDelta) < 1e-6 || abs(inputDelta) < MIN_STEP_VOLTS) return StepResponseMetrics()

        fun progress(row: AlignedDataRow): Double = (row.velocity - baselineVelocity) / responseDelta
        fun firstCrossing(level: Double): Int = (stepIndex until data.size).firstOrNull { progress(data[it]) >= level } ?: -1
        val five = firstCrossing(0.05)
        val ten = firstCrossing(0.10)
        val sixtyThree = firstCrossing(0.632)
        val ninety = firstCrossing(0.90)
        val startTime = data[stepIndex].timestampMs
        val deadTimeMs = if (five >= 0) (data[five].timestampMs - startTime).toDouble() else Double.NaN
        val riseTimeMs = if (ten >= 0 && ninety >= ten) (data[ninety].timestampMs - data[ten].timestampMs).toDouble() else Double.NaN
        val timeConstantMs = if (sixtyThree >= 0) {
            (data[sixtyThree].timestampMs - startTime).toDouble() - (deadTimeMs.takeIf { it.isFinite() } ?: 0.0)
        } else Double.NaN
        val peak = (stepIndex until data.size).maxOf { progress(data[it]) }
        val overshoot = max(0.0, (peak - 1.0) * 100.0)

        var settlingTime = Double.NaN
        for (i in stepIndex until data.size) {
            var staysSettled = true
            for (j in i until data.size) {
                if (abs(progress(data[j]) - 1.0) > 0.02) {
                    staysSettled = false
                    break
                }
            }
            if (staysSettled) {
                settlingTime = (data[i].timestampMs - startTime).toDouble()
                break
            }
        }

        val tauSec = timeConstantMs / 1000.0
        val delaySec = (deadTimeMs.takeIf { it.isFinite() } ?: 0.0) / 1000.0
        var ssRes = 0.0
        var ssTot = 0.0
        for (i in stepIndex until data.size) {
            val elapsed = (data[i].timestampMs - startTime) / 1000.0
            val predictedProgress = if (elapsed <= delaySec || !tauSec.isFinite() || tauSec <= 0.0) 0.0
                else 1.0 - kotlin.math.exp(-(elapsed - delaySec) / tauSec)
            val actualProgress = progress(data[i])
            ssRes += (actualProgress - predictedProgress) * (actualProgress - predictedProgress)
            ssTot += (actualProgress - 1.0) * (actualProgress - 1.0)
        }
        val modelFit = if (ssTot > 1e-9) (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0) else 0.0
        return StepResponseMetrics(
            riseTimeMs = riseTimeMs,
            percentOvershoot = overshoot,
            settlingTimeMs = settlingTime,
            deadTimeMs = deadTimeMs,
            timeConstantMs = timeConstantMs,
            processGain = responseDelta / inputDelta,
            modelFit = modelFit
        )
    }

    companion object {
        private const val MIN_RECOMMENDATION_SAMPLES = 20
        private const val MIN_STEP_VOLTS = 1.0
        private const val MIN_REVIEW_R2 = 0.70
        private const val MIN_REJECT_R2 = 0.45
        private const val MIN_VALIDATION_R2 = 0.75
        private const val MAX_VALIDATION_DRIFT = 0.25
        private const val READY_CONFIDENCE = 0.78
        private const val MAX_KP = 50.0
        private const val MAX_KI = 100.0
        private const val MAX_KD = 10.0
    }
}
