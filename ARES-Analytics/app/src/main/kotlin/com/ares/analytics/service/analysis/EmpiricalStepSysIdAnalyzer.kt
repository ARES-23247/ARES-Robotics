package com.ares.analytics.service.analysis

import com.ares.analytics.service.tuning.ExternalTuningProposal
import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import org.ejml.simple.SimpleMatrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

enum class GravityModelKind(val label: String) {
    NONE("No Gravity (Drive / Flywheel)"),
    ELEVATOR_CONSTANT("Elevator (+kG)"),
    ARM_COSINE("Arm (+kG·cos θ)"),
}

data class EmpiricalSysIdSample(
    val timestampMs: Long,
    val voltage: Double,
    val velocity: Double,
    val acceleration: Double,
    val position: Double = 0.0,
)

data class EmpiricalFeedforwardFit(
    val valid: Boolean = false,
    val kS: Double = 0.0,
    val kV: Double = 0.0,
    val kA: Double = 0.0,
    val kG: Double = 0.0,
    val rSquared: Double = 0.0,
    val sampleCount: Int = 0,
    val gravityMode: GravityModelKind = GravityModelKind.NONE,
    val suggestedKp: Double = 0.0,
    val suggestedKd: Double = 0.0,
)

data class EmpiricalStepMetrics(
    val stepTimestampMs: Long,
    val initialSetpoint: Double,
    val finalSetpoint: Double,
    val finalMeasured: Double,
    val riseTimeMs: Double,
    val peakOvershootPct: Double,
    val settlingTimeMs: Double,
    val steadyStateError: Double,
    val phaseLagMs: Double,
)

/**
 * Empirical System Identification & Step-Response Analyzer for recorded telemetry sessions.
 *
 * Solves SVD-regularized Ordinary Least Squares (OLS) feedforward models with optional elevator or
 * arm gravity compensation, extracts quantitative closed-loop step-response metrics, and builds
 * schema-validated [ExternalTuningProposal]s for [com.ares.analytics.service.tuning.TuningProposalInbox].
 */
object EmpiricalStepSysIdAnalyzer {

    private const val MIN_MOTION_VELOCITY = 0.03

    /** Aligns voltage, velocity, optional acceleration (derived if absent), and optional position series. */
    fun alignSamples(
        voltageSamples: List<TimedScalarSample>,
        velocitySamples: List<TimedScalarSample>,
        accelerationSamples: List<TimedScalarSample> = emptyList(),
        positionSamples: List<TimedScalarSample> = emptyList(),
    ): List<EmpiricalSysIdSample> {
        val volts = SignalMathEngine.deduplicateTimestamps(voltageSamples)
        val vels = SignalMathEngine.deduplicateTimestamps(velocitySamples)
        if (volts.size < 12 || vels.size < 12) return emptyList()
        val accels = if (accelerationSamples.size >= 8) {
            SignalMathEngine.deduplicateTimestamps(accelerationSamples)
        } else {
            SignalMathEngine.derivative(vels)
        }
        val positions = if (positionSamples.size >= 4) {
            SignalMathEngine.deduplicateTimestamps(positionSamples)
        } else {
            SignalMathEngine.cumulativeIntegral(vels)
        }

        val minTs = max(volts.first().timestampMs, vels.first().timestampMs)
        val maxTs = min(volts.last().timestampMs, vels.last().timestampMs)
        if (minTs >= maxTs) return emptyList()

        val out = ArrayList<EmpiricalSysIdSample>(vels.size)
        for (vel in vels) {
            val ts = vel.timestampMs
            if (ts < minTs || ts > maxTs) continue
            val v = interpolateAt(volts, ts) ?: continue
            val a = interpolateAt(accels, ts) ?: 0.0
            val p = interpolateAt(positions, ts) ?: 0.0
            if (v.isFinite() && vel.value.isFinite() && a.isFinite() && p.isFinite()) {
                out += EmpiricalSysIdSample(ts, v, vel.value, a, p)
            }
        }
        return out
    }

    /**
     * Solves $V = k_S \operatorname{sgn}(v) + k_V v + k_A a (+ k_G \text{ or } k_G \cos\theta)$
     * via column-scaled compact SVD, rejecting rank-deficient excitations cleanly.
     */
    fun fitFeedforward(
        samples: List<EmpiricalSysIdSample>,
        gravityMode: GravityModelKind = GravityModelKind.NONE,
    ): EmpiricalFeedforwardFit {
        val filtered = filterZeroCrossingTransients(samples)
        val cols = if (gravityMode == GravityModelKind.NONE) 3 else 4
        if (filtered.size < cols * 4) {
            return EmpiricalFeedforwardFit(gravityMode = gravityMode, sampleCount = filtered.size)
        }

        val vScale = filtered.maxOf { abs(it.velocity) }.coerceAtLeast(1e-6)
        val aScale = filtered.maxOf { abs(it.acceleration) }.coerceAtLeast(1e-6)
        val yScale = filtered.maxOf { abs(it.voltage) }.coerceAtLeast(1e-6)
        if (yScale <= 1e-5) return EmpiricalFeedforwardFit(gravityMode = gravityMode)

        val n = filtered.size
        val xMatrix = SimpleMatrix(n, cols)
        val yNorm = DoubleArray(n)

        for (i in 0 until n) {
            val row = filtered[i]
            xMatrix.set(i, 0, sign(row.velocity))
            xMatrix.set(i, 1, row.velocity / vScale)
            xMatrix.set(i, 2, row.acceleration / aScale)
            if (cols == 4) {
                val gBasis = when (gravityMode) {
                    GravityModelKind.ELEVATOR_CONSTANT -> 1.0
                    GravityModelKind.ARM_COSINE -> cos(row.position)
                    GravityModelKind.NONE -> 0.0
                }
                xMatrix.set(i, 3, gBasis)
            }
            yNorm[i] = row.voltage / yScale
        }

        return try {
            val svd = xMatrix.svd(true)
            val singular = svd.singularValues
            val maxSigma = singular.take(cols).maxOrNull() ?: return EmpiricalFeedforwardFit(gravityMode = gravityMode)
            if (!maxSigma.isFinite() || maxSigma <= 0.0) return EmpiricalFeedforwardFit(gravityMode = gravityMode)
            if ((0 until cols).any { !singular[it].isFinite() || singular[it] <= maxSigma * 1e-8 }) {
                return EmpiricalFeedforwardFit(gravityMode = gravityMode, sampleCount = n)
            }

            val u = svd.u
            val v = svd.v
            val beta = DoubleArray(cols)
            for (col in 0 until cols) {
                var proj = 0.0
                for (i in 0 until n) proj += u.get(i, col) * yNorm[i]
                val weight = proj / singular[col]
                for (j in 0 until cols) beta[j] += v.get(j, col) * weight
            }

            val kS = beta[0] * yScale
            val kV = (beta[1] * yScale) / vScale
            val kA = (beta[2] * yScale) / aScale
            val kG = if (cols == 4) beta[3] * yScale else 0.0
            if (!kS.isFinite() || !kV.isFinite() || !kA.isFinite() || !kG.isFinite()) {
                return EmpiricalFeedforwardFit(gravityMode = gravityMode)
            }

            val yMean = yNorm.average()
            var ssTot = 0.0
            var ssRes = 0.0
            for (i in 0 until n) {
                var pred = 0.0
                for (j in 0 until cols) pred += beta[j] * xMatrix.get(i, j)
                ssTot += (yNorm[i] - yMean) * (yNorm[i] - yMean)
                ssRes += (yNorm[i] - pred) * (yNorm[i] - pred)
            }
            val r2 = if (ssTot > 1e-12) (1.0 - ssRes / ssTot).coerceIn(-1.0, 1.0) else 0.0
            val tau = if (kV > 1e-4 && kA > 1e-5) (kA / kV).coerceIn(0.03, 1.5) else 0.15
            val suggestedKp = (1.2 / tau).coerceIn(0.2, 24.0)
            val suggestedKd = (0.18 * kA * suggestedKp).coerceIn(0.0, 6.0)

            EmpiricalFeedforwardFit(
                valid = true,
                kS = kS,
                kV = kV,
                kA = kA,
                kG = kG,
                rSquared = r2,
                sampleCount = n,
                gravityMode = gravityMode,
                suggestedKp = suggestedKp,
                suggestedKd = suggestedKd,
            )
        } catch (_: Exception) {
            EmpiricalFeedforwardFit(gravityMode = gravityMode)
        }
    }

    /**
     * Detects the largest clean setpoint step in `[setpointSamples]` and measures 10%→90% rise time,
     * percentage overshoot relative to settled response, ±2% settling time, steady-state error, and phase lag.
     */
    fun analyzeStepResponse(
        setpointSamples: List<TimedScalarSample>,
        measuredSamples: List<TimedScalarSample>,
    ): EmpiricalStepMetrics? {
        val sp = SignalMathEngine.deduplicateTimestamps(setpointSamples)
        val pv = SignalMathEngine.deduplicateTimestamps(measuredSamples)
        if (sp.size < 10 || pv.size < 10) return null

        var bestStepIdx = -1
        var bestStepMag = 0.0
        for (i in 1 until sp.size - 5) {
            val jump = abs(sp[i].value - sp[i - 1].value)
            if (jump > bestStepMag) {
                bestStepMag = jump
                bestStepIdx = i
            }
        }
        if (bestStepIdx == -1 || bestStepMag <= 1e-5) return null

        val stepTs = sp[bestStepIdx].timestampMs
        val initialSetpoint = sp[bestStepIdx - 1].value
        val finalSetpoint = sp[bestStepIdx].value
        val stepDirection = sign(finalSetpoint - initialSetpoint)

        // Restrict window until next major setpoint change (if any)
        var windowEndTs = pv.last().timestampMs
        for (i in (bestStepIdx + 1) until sp.size) {
            if (abs(sp[i].value - finalSetpoint) > 0.15 * bestStepMag) {
                windowEndTs = sp[i - 1].timestampMs
                break
            }
        }
        val postStep = pv.filter { it.timestampMs in stepTs..windowEndTs }
        if (postStep.size < 8) return null

        val initialMeasured = interpolateAt(pv, stepTs) ?: postStep.first().value
        val tailCount = (postStep.size / 5).coerceAtLeast(3)
        val finalMeasured = postStep.takeLast(tailCount).map { it.value }.average()
        val responseSpan = (finalMeasured - initialMeasured) * stepDirection
        if (responseSpan <= 1e-6) return null

        val threshold10 = initialMeasured + stepDirection * 0.10 * responseSpan
        val threshold90 = initialMeasured + stepDirection * 0.90 * responseSpan
        val t10 = crossingTimestamp(postStep, threshold10, stepDirection) ?: stepTs.toDouble()
        val t90 = crossingTimestamp(postStep, threshold90, stepDirection) ?: postStep.last().timestampMs.toDouble()
        val riseTimeMs = (t90 - t10).coerceAtLeast(0.0)

        val peakProgress = postStep.maxOf { (it.value - initialMeasured) * stepDirection }
        val peakOvershootPct = (((peakProgress - responseSpan) / responseSpan) * 100.0).coerceAtLeast(0.0)

        val tolerance = 0.02 * responseSpan
        var lastOutsideIdx = -1
        for (i in postStep.indices) {
            if (abs(postStep[i].value - finalMeasured) > tolerance) {
                lastOutsideIdx = i
            }
        }
        val settledTs = when {
            lastOutsideIdx < 0 -> stepTs
            lastOutsideIdx + 1 < postStep.size -> postStep[lastOutsideIdx + 1].timestampMs
            else -> postStep.last().timestampMs
        }
        val settlingTimeMs = (settledTs - stepTs).toDouble().coerceAtLeast(0.0)
        val steadyStateError = finalSetpoint - finalMeasured

        val midpoint = initialMeasured + stepDirection * 0.50 * responseSpan
        val t50 = crossingTimestamp(postStep, midpoint, stepDirection) ?: stepTs.toDouble()
        val phaseLagMs = (t50 - stepTs.toDouble()).coerceAtLeast(0.0)

        return EmpiricalStepMetrics(
            stepTimestampMs = stepTs,
            initialSetpoint = initialSetpoint,
            finalSetpoint = finalSetpoint,
            finalMeasured = finalMeasured,
            riseTimeMs = riseTimeMs,
            peakOvershootPct = peakOvershootPct,
            settlingTimeMs = settlingTimeMs,
            steadyStateError = steadyStateError,
            phaseLagMs = phaseLagMs,
        )
    }

    /**
     * Maps solved gains (`kS`, `kV`, `kA`, `kG`, `kP`, `kD`) onto matching writable numeric
     * [TuningParameterDeclaration]s so [com.ares.analytics.viewmodel.TuningViewModel] stages them atomically.
     */
    fun buildTuningProposal(
        mechanismHint: String,
        fit: EmpiricalFeedforwardFit,
        declarations: List<TuningParameterDeclaration>,
        evidenceSessionId: String? = null,
    ): ExternalTuningProposal? {
        if (!fit.valid || declarations.isEmpty()) return null
        val hintNorm = mechanismHint.trim().lowercase()
        val editableDoubles = declarations.filter { decl ->
            decl.type == TuningParameterType.DOUBLE &&
                decl.applyPolicy != TuningApplyPolicy.READ_ONLY_VENDOR &&
                (hintNorm.isEmpty() ||
                    decl.key.lowercase().contains(hintNorm) ||
                    decl.componentUid.lowercase().contains(hintNorm) ||
                    decl.displayName.lowercase().contains(hintNorm))
        }
        if (editableDoubles.isEmpty()) return null

        val candidateValues = linkedMapOf(
            "ks" to fit.kS,
            "kv" to fit.kV,
            "ka" to fit.kA,
            "kg" to fit.kG,
            "kp" to fit.suggestedKp,
            "kd" to fit.suggestedKd,
        )
        val matched = linkedMapOf<String, Double>()
        for (decl in editableDoubles) {
            val leaf = decl.key.substringAfterLast('/').substringAfterLast('.').lowercase()
            val rawGain = candidateValues[leaf] ?: continue
            if (!rawGain.isFinite()) continue
            if (leaf == "kg" && fit.gravityMode == GravityModelKind.NONE) continue
            val clamped = clampToDeclaration(rawGain, decl) ?: continue
            matched[decl.key] = clamped
        }
        if (matched.isEmpty()) return null

        val summary = buildString {
            append("Empirical OLS SysId")
            if (mechanismHint.isNotBlank()) append(" ($mechanismHint)")
            append(": R²=${String.format("%.3f", fit.rSquared)}, n=${fit.sampleCount}")
            if (evidenceSessionId != null) append(" [session $evidenceSessionId]")
        }
        return ExternalTuningProposal(
            source = "Empirical Log SysId",
            summary = summary,
            values = matched,
        )
    }

    private fun clampToDeclaration(value: Double, decl: TuningParameterDeclaration): Double? {
        if (!value.isFinite()) return null
        val minBound = decl.minimum
        val maxBound = decl.maximum
        if (minBound != null && maxBound != null && minBound > maxBound) return null
        val clamped = value
            .let { v -> if (minBound != null) maxOf(v, minBound) else v }
            .let { v -> if (maxBound != null) minOf(v, maxBound) else v }
        return clamped.takeIf { it.isFinite() }
    }

    private fun filterZeroCrossingTransients(samples: List<EmpiricalSysIdSample>): List<EmpiricalSysIdSample> {
        val signChanges = ArrayList<Long>()
        for (i in 1 until samples.size) {
            val prev = sign(samples[i - 1].velocity)
            val curr = sign(samples[i].velocity)
            if (prev != 0.0 && curr != 0.0 && prev != curr) {
                signChanges += samples[i].timestampMs
            }
        }
        return samples.filter { row ->
            row.voltage.isFinite() &&
                row.velocity.isFinite() &&
                row.acceleration.isFinite() &&
                row.position.isFinite() &&
                abs(row.velocity) >= MIN_MOTION_VELOCITY &&
                signChanges.none { changeTs -> abs(row.timestampMs - changeTs) <= 50L }
        }
    }

    private fun crossingTimestamp(
        samples: List<TimedScalarSample>,
        threshold: Double,
        direction: Double,
    ): Double? {
        for (i in 1 until samples.size) {
            val prev = (samples[i - 1].value - threshold) * direction
            val curr = (samples[i].value - threshold) * direction
            if (prev <= 0.0 && curr >= 0.0) {
                val denom = curr - prev
                val alpha = if (denom > 1e-12) (-prev / denom).coerceIn(0.0, 1.0) else 0.0
                return samples[i - 1].timestampMs + alpha * (samples[i].timestampMs - samples[i - 1].timestampMs)
            }
        }
        return null
    }

    internal fun interpolateAt(samples: List<TimedScalarSample>, timestampMs: Long): Double? {
        if (samples.isEmpty()) return null
        var lo = 0
        var hi = samples.lastIndex
        if (timestampMs <= samples[lo].timestampMs) return samples[lo].value
        if (timestampMs >= samples[hi].timestampMs) return samples[hi].value
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val midTs = samples[mid].timestampMs
            when {
                midTs < timestampMs -> lo = mid + 1
                midTs > timestampMs -> hi = mid - 1
                else -> return samples[mid].value
            }
        }
        val left = samples[hi.coerceAtLeast(0)]
        val right = samples[lo.coerceAtMost(samples.lastIndex)]
        val span = (right.timestampMs - left.timestampMs).toDouble()
        val alpha = if (span > 0.0) (timestampMs - left.timestampMs) / span else 0.0
        return left.value + alpha * (right.value - left.value)
    }
}
