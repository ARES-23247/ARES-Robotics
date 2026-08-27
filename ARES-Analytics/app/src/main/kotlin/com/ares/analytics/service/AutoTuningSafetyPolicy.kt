package com.ares.analytics.service

import com.areslib.control.assist.SysIdMechanism
data class AutoTuningDataQuality(
    val score: Double,
    val finiteSampleRatio: Double,
    val sampleCount: Int,
    val durationMs: Long,
    val medianPeriodMs: Double,
    val maximumGapMs: Long,
    val voltageSpan: Double,
    val velocitySpan: Double,
    val blockers: List<String>,
    val warnings: List<String>
) {
    val passed: Boolean get() = blockers.isEmpty()
}

data class MechanismGainEnvelope(
    val maxKS: Double,
    val minKV: Double,
    val maxKV: Double,
    val maxKA: Double,
    val maxKP: Double,
    val maxKI: Double,
    val maxKD: Double
) {
    fun violations(kS: Double, kV: Double, kA: Double, gains: AutoTunerPIDFGains): List<String> {
        val violations = ArrayList<String>(4)
        if (!kS.isFinite() || kS < 0.0 || kS > maxKS) violations += "kS=$kS is outside [0, $maxKS]."
        if (!kV.isFinite() || kV < minKV || kV > maxKV) violations += "kV=$kV is outside [$minKV, $maxKV]."
        if (!kA.isFinite() || kA < 0.0 || kA > maxKA) violations += "kA=$kA is outside [0, $maxKA]."
        if (!gains.kP.isFinite() || gains.kP < 0.0 || gains.kP > maxKP) violations += "kP=${gains.kP} is outside [0, $maxKP]."
        if (!gains.kI.isFinite() || gains.kI < 0.0 || gains.kI > maxKI) violations += "kI=${gains.kI} is outside [0, $maxKI]."
        if (!gains.kD.isFinite() || gains.kD < 0.0 || gains.kD > maxKD) violations += "kD=${gains.kD} is outside [0, $maxKD]."
        return violations
    }
}

/** Robot-independent quality and gain limits enforced before a recommendation can be applied. */
object AutoTuningSafetyPolicy {
    fun envelopeFor(mechanism: SysIdMechanism): MechanismGainEnvelope = when (mechanism) {
        SysIdMechanism.LINEAR, SysIdMechanism.ELEVATOR -> MechanismGainEnvelope(3.0, 0.01, 15.0, 10.0, 30.0, 60.0, 8.0)
        SysIdMechanism.ANGULAR, SysIdMechanism.ARM -> MechanismGainEnvelope(3.0, 0.01, 20.0, 10.0, 40.0, 80.0, 10.0)
        SysIdMechanism.FLYWHEEL, SysIdMechanism.CUSTOM -> MechanismGainEnvelope(3.0, 0.001, 3.0, 2.0, 12.0, 25.0, 3.0)
    }

    fun assessData(mechanism: SysIdMechanism, samples: List<AlignedDataRow>): AutoTuningDataQuality {
        val finite = samples.filter { it.voltage.isFinite() && it.velocity.isFinite() && it.accel.isFinite() }
            .sortedBy { it.timestampMs }
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val finiteRatio = if (samples.isEmpty()) 0.0 else finite.size.toDouble() / samples.size
        val periods = finite.zipWithNext { first, second -> second.timestampMs - first.timestampMs }
            .filter { it > 0L }
            .sorted()
        val medianPeriod = if (periods.isEmpty()) 0.0 else periods[periods.size / 2].toDouble()
        val maximumGap = periods.maxOrNull() ?: 0L
        val duration = if (finite.size > 1) finite.last().timestampMs - finite.first().timestampMs else 0L
        val voltageSpan = finite.maxOfOrNull { it.voltage }?.minus(finite.minOfOrNull { it.voltage } ?: 0.0) ?: 0.0
        val velocitySpan = finite.maxOfOrNull { it.velocity }?.minus(finite.minOfOrNull { it.velocity } ?: 0.0) ?: 0.0
        val uniqueTimestamps = finite.asSequence().map { it.timestampMs }.distinct().count()

        if (finite.size < MIN_SAMPLES) blockers += "At least $MIN_SAMPLES finite samples are required."
        if (finiteRatio < MIN_FINITE_RATIO) blockers += "More than ${(100.0 * (1.0 - MIN_FINITE_RATIO)).toInt()}% of samples are non-finite."
        if (duration < MIN_DURATION_MS) blockers += "The characterized interval is shorter than ${MIN_DURATION_MS}ms."
        if (uniqueTimestamps != finite.size) blockers += "Sample timestamps must be unique."
        if (voltageSpan < MIN_VOLTAGE_SPAN) blockers += "Voltage excitation span is below ${MIN_VOLTAGE_SPAN}V."
        if (velocitySpan < MIN_VELOCITY_SPAN) blockers += "Velocity excitation is too small for identification."
        if (medianPeriod <= 0.0) blockers += "A positive sample period could not be established."
        if (medianPeriod > MAX_MEDIAN_PERIOD_MS) blockers += "The median telemetry period exceeds ${MAX_MEDIAN_PERIOD_MS.toInt()}ms."
        if (medianPeriod > 0.0 && maximumGap > medianPeriod * MAX_GAP_MULTIPLIER) {
            blockers += "A ${maximumGap}ms telemetry gap exceeds ${MAX_GAP_MULTIPLIER.toInt()}x the median period."
        }

        val hasPositive = finite.any { it.velocity > DIRECTION_THRESHOLD }
        val hasNegative = finite.any { it.velocity < -DIRECTION_THRESHOLD }
        if (mechanism == SysIdMechanism.FLYWHEEL) {
            if (!hasPositive) blockers += "Flywheel characterization never reached positive velocity."
            if (hasNegative) warnings += "Flywheel data contains reverse motion; one-direction characterization is recommended."
        } else if (!hasPositive || !hasNegative) {
            blockers += "Drivetrain characterization must cover both directions."
        }

        if (medianPeriod > 0.0 && maximumGap > medianPeriod * 3.0) warnings += "Telemetry contains noticeable sample gaps."
        if (finiteRatio < 0.98) warnings += "Some non-finite samples were discarded."

        val sampleScore = (finite.size / 120.0).coerceIn(0.0, 1.0)
        val durationScore = (duration / 2_500.0).coerceIn(0.0, 1.0)
        val excitationScore = (voltageSpan / 8.0).coerceIn(0.0, 1.0)
        val gapScore = if (medianPeriod <= 0.0) 0.0 else (1.0 - maximumGap / (medianPeriod * MAX_GAP_MULTIPLIER)).coerceIn(0.0, 1.0)
        val directionScore = if (mechanism == SysIdMechanism.FLYWHEEL) {
            if (hasPositive) 1.0 else 0.0
        } else if (hasPositive && hasNegative) 1.0 else 0.0
        val score = (0.25 * sampleScore + 0.20 * durationScore + 0.20 * excitationScore +
            0.15 * gapScore + 0.10 * directionScore + 0.10 * finiteRatio).coerceIn(0.0, 1.0)

        return AutoTuningDataQuality(
            score = score,
            finiteSampleRatio = finiteRatio,
            sampleCount = finite.size,
            durationMs = duration,
            medianPeriodMs = medianPeriod,
            maximumGapMs = maximumGap,
            voltageSpan = voltageSpan,
            velocitySpan = velocitySpan,
            blockers = blockers,
            warnings = warnings
        )
    }

    private const val MIN_SAMPLES = 30
    private const val MIN_FINITE_RATIO = 0.90
    private const val MIN_DURATION_MS = 500L
    private const val MIN_VOLTAGE_SPAN = 2.0
    private const val MIN_VELOCITY_SPAN = 0.2
    private const val DIRECTION_THRESHOLD = 0.05
    private const val MAX_GAP_MULTIPLIER = 8.0
    private const val MAX_MEDIAN_PERIOD_MS = 100.0
}
