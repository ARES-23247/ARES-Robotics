package com.ares.analytics.service

import com.ares.analytics.shared.models.CalculatedSummary
import com.ares.analytics.shared.models.TransientClassification
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import org.ejml.simple.SimpleMatrix
import kotlin.math.abs
import kotlin.math.sign

/**
 * System Identification (SysId) Service for actuator feedforward parameter estimation and mechanical vibration spectral analysis.
 *
 * Implements Ordinary Least Squares (OLS) multi-variable linear regression to solve motor feedforward parameters ($k_S, k_V, k_A$),
 * and applies Apache Commons Math Fast Fourier Transform (FFT) analysis to identify mechanical resonance frequencies in mechanism telemetry.
 *
 * ### Mathematical Regressions:
 * 1. **Electromechanical Voltage Model**:
 *    $$V(t) = k_S \cdot \operatorname{sgn}(v(t)) + k_V \cdot v(t) + k_A \cdot a(t)$$
 * 2. **OLS Solution**: column-scaled compact SVD with a relative singular-value rank check.
 * 3. **Direction-Change Filtering**:
 *    Cleanses zero-crossing transients by excluding telemetry samples recorded within $\pm 50\text{ ms}$ of velocity sign changes ($\operatorname{sgn}(v)$).
 *
 * ### Physical Units & Quantities:
 * - Voltage ($V$): Volts ($V$)
 * - Velocity ($v$): Meters/sec ($m/s$) or Radians/sec ($rad/s$)
 * - Acceleration ($a$): $m/s^2$ or $rad/s^2$
 * - Static Friction ($k_S$): Volts ($V$)
 * - Velocity Feedforward ($k_V$): $V/(m/s)$ or $V/(rad/s)$
 * - Acceleration Feedforward ($k_A$): $V/(m/s^2)$ or $V/(rad/s^2)$
 * - Resonance Frequency: Hertz ($Hz$)
 *
 * ### Thread Safety & Performance Guarantees:
 * Numerical methods are synchronous. Callers own dispatching expensive analysis off the UI thread.
 *
 * @param databaseService Primary DuckDB telemetry repository.
 *
 * @see AutoTunerService
 * @see SummaryEngineService
 */
class SysIdService(private val databaseService: DatabaseService) {

    /**
     * Solves feedforward gains ($k_S, k_V, k_A$) and calculates transient statistics for a specified motor channel across a log session.
     *
     * @param sessionId Session identifier string.
     * @param voltageKey Telemetry topic key for motor voltage ($V$).
     * @param velocityKey Telemetry topic key for motor velocity ($m/s$ or $rad/s$).
     * @param accelerationKey Telemetry topic key for motor acceleration ($m/s^2$ or $rad/s^2$).
     * @return Calculated summary containing solved feedforward coefficients and $R^2$ goodness-of-fit.
     */
    suspend fun analyzeMotorData(
        sessionId: String,
        voltageKey: String,
        velocityKey: String,
        accelerationKey: String
    ): CalculatedSummary {
        // Fetch each channel server-side (key-filtered) instead of loading the whole
        // session three times and filtering in memory (AUDIT H13).
        val voltages = databaseService.getTelemetryForKey(sessionId, voltageKey)
        val velocities = databaseService.getTelemetryForKey(sessionId, velocityKey)
        val accelerations = databaseService.getTelemetryForKey(sessionId, accelerationKey)

        if (voltages.isEmpty() || velocities.isEmpty() || accelerations.isEmpty()) {
            return CalculatedSummary()
        }

        // Align independently sampled channels by bounded nearest-neighbor matching.
        val alignedData = mutableListOf<AlignedDataRow>()

        // Identify direction change timestamps (sign of velocity changes)
        val directionChanges = mutableListOf<Long>()
        var lastSign = 0.0
        val sortedVelocities = velocities.filter { it.value.isFinite() }.sortedBy { it.timestampUs }
        for (v in sortedVelocities) {
            val currentSign = sign(v.value)
            if (currentSign != 0.0 && lastSign != 0.0 && currentSign != lastSign) {
                directionChanges.add(v.timestampUs)
            }
            if (currentSign != 0.0) lastSign = currentSign
        }
        val sortedVoltages = voltages.filter { it.value.isFinite() }.sortedBy { it.timestampUs }
        val sortedAccels = accelerations.filter { it.value.isFinite() }.sortedBy { it.timestampUs }
        if (sortedVelocities.isEmpty() || sortedVoltages.isEmpty() || sortedAccels.isEmpty()) return CalculatedSummary()
        var voltageIdx = 0
        var accelIdx = 0
        var directionChangeIdx = 0

        for (v in sortedVelocities) {
            val t = v.timestampUs

            // Apply direction change cleansing: skip data points within ±50ms of a sign change
            while (directionChangeIdx < directionChanges.size - 1 &&
                directionChanges[directionChangeIdx + 1] <= t
            ) {
                directionChangeIdx++
            }
            val isNearDirectionChange =
                (directionChangeIdx < directionChanges.size && abs(directionChanges[directionChangeIdx] - t) <= 50_000) ||
                    (directionChangeIdx + 1 < directionChanges.size &&
                        abs(directionChanges[directionChangeIdx + 1] - t) <= 50_000)
            if (isNearDirectionChange) continue
            while (voltageIdx < sortedVoltages.size - 1 &&
                abs(sortedVoltages[voltageIdx + 1].timestampUs - t) <= abs(sortedVoltages[voltageIdx].timestampUs - t)
            ) {
                voltageIdx++
            }
            val voltageFrame = sortedVoltages[voltageIdx]
            if (abs(voltageFrame.timestampUs - t) > MAX_ALIGNMENT_DELTA_US) continue

            // Move accelIdx forward to find nearest neighbor in O(N + M)
            while (accelIdx < sortedAccels.size - 1 &&
                abs(sortedAccels[accelIdx + 1].timestampUs - t) <= abs(sortedAccels[accelIdx].timestampUs - t)
            ) {
                accelIdx++
            }
            val accelFrame = sortedAccels[accelIdx]
            if (abs(accelFrame.timestampUs - t) > MAX_ALIGNMENT_DELTA_US) continue

            alignedData.add(AlignedDataRow(v.timestampMs, voltageFrame.value, v.value, accelFrame.value))
        }

        return analyzeRawData(alignedData)
    }

    fun analyzeRawData(alignedData: List<AlignedDataRow>): CalculatedSummary =
        analyzePreparedData(PreparedSysIdData.from(alignedData))

    internal fun analyzePreparedData(prepared: PreparedSysIdData): CalculatedSummary {
        val finiteData = prepared.rows
        val validData = finiteData.filter { abs(it.velocity) > MIN_SYSID_VELOCITY }
        if (validData.size < 10) {
            return CalculatedSummary()
        }

        val fallback = CalculatedSummary(transientClassification = classifyTransient(finiteData))
        var velocityScale = 0.0
        var accelerationScale = 0.0
        var voltageScale = 0.0
        for (row in validData) {
            velocityScale = maxOf(velocityScale, abs(row.velocity))
            accelerationScale = maxOf(accelerationScale, abs(row.accel))
            voltageScale = maxOf(voltageScale, abs(row.voltage))
        }
        if (accelerationScale == 0.0 || voltageScale == 0.0) return fallback
        // Normalize units before assessing rank. A missing excitation cannot identify a gain.
        val n = validData.size
        val X = SimpleMatrix(n, 3)
        val y = DoubleArray(n)

        for (i in 0 until n) {
            val row = validData[i]
            X.set(i, 0, sign(row.velocity))
            X.set(i, 1, row.velocity / velocityScale)
            X.set(i, 2, row.accel / accelerationScale)
            y[i] = row.voltage / voltageScale
        }

        return try {
            val decomposition = X.svd(true)
            val singular = decomposition.singularValues
            val largest = singular.maxOrNull() ?: return fallback
            if (singular.size != 3 || !largest.isFinite() || largest <= 0.0 ||
                singular.any { !it.isFinite() || it <= largest * 1e-10 }) return fallback
            val u = decomposition.u
            val v = decomposition.v
            val beta = DoubleArray(3)
            for (column in 0..2) {
                var projection = 0.0
                for (i in 0 until n) projection += u.get(i, column) * y[i]
                val weight = projection / singular[column]
                for (j in 0..2) beta[j] += v.get(j, column) * weight
            }
            val kS = beta[0] * voltageScale
            val kV = rescaleGain(beta[1], voltageScale, velocityScale)
            val kA = rescaleGain(beta[2], voltageScale, accelerationScale)
            if (!kS.isFinite() || !kV.isFinite() || !kA.isFinite()) return fallback

            // Compute R-squared
            val yMean = y.average()
            var ssTot = 0.0
            var ssRes = 0.0
            for (i in 0 until n) {
                val actual = y[i]
                val predicted = beta[0] * X.get(i, 0) + beta[1] * X.get(i, 1) + beta[2] * X.get(i, 2)
                ssTot += (actual - yMean) * (actual - yMean)
                ssRes += (actual - predicted) * (actual - predicted)
            }
            val rSquared = if (ssTot > 0) 1.0 - (ssRes / ssTot) else 0.0
            if (!rSquared.isFinite()) return fallback

            CalculatedSummary(
                kS = kS,
                kV = kV,
                kA = kA,
                rSquared = rSquared,
                transientClassification = fallback.transientClassification
            )
        } catch (_: Exception) {
            fallback
        }
    }

    /** Binary scaling keeps a finite physical coefficient even if the units ratio overflows. */
    private fun rescaleGain(coefficient: Double, numerator: Double, denominator: Double): Double {
        val coefficientExponent = Math.getExponent(coefficient)
        val numeratorExponent = Math.getExponent(numerator)
        val denominatorExponent = Math.getExponent(denominator)
        val mantissa = Math.scalb(coefficient, -coefficientExponent) * Math.scalb(numerator, -numeratorExponent) /
            Math.scalb(denominator, -denominatorExponent)
        return Math.scalb(mantissa, coefficientExponent + numeratorExponent - denominatorExponent)
    }

    private fun classifyTransient(data: List<AlignedDataRow>): TransientClassification {
        // Find a step-like voltage increase (e.g. from < 1.0 to > 6.0)
        var stepStartIdx = -1
        for (i in 1 until data.size) {
            if (abs(data[i - 1].voltage) < 1.0 && abs(data[i].voltage) > 6.0) {
                stepStartIdx = i
                break
            }
        }
        if (stepStartIdx == -1) return TransientClassification.UNKNOWN

        // Scale before averaging and comparing to avoid overflow and temporary lists.
        val end = minOf(stepStartIdx + 30, data.size)
        val tailStart = maxOf(stepStartIdx, end - 10)
        var scale = 0.0
        for (i in stepStartIdx until end) scale = maxOf(scale, abs(data[i].velocity))
        if (scale == 0.0) return TransientClassification.UNKNOWN
        var steady = 0.0
        if (end - stepStartIdx > 10) {
            for (i in tailStart until end) steady += (data[i].velocity / scale) / (end - tailStart)
        } else {
            steady = data[end - 1].velocity / scale
        }
        if (abs(steady) * scale <= 1e-6) return TransientClassification.UNKNOWN
        var peakProgress = Double.NEGATIVE_INFINITY
        var tailMin = Double.POSITIVE_INFINITY
        var tailMax = Double.NEGATIVE_INFINITY
        for (i in stepStartIdx until end) {
            val velocity = data[i].velocity / scale
            peakProgress = maxOf(peakProgress, velocity / steady)
            if (i >= tailStart) {
                tailMin = minOf(tailMin, velocity)
                tailMax = maxOf(tailMax, velocity)
            }
        }
        val tailDriftRatio = (tailMax - tailMin) / abs(steady)
        return when {
            peakProgress > 1.05 -> TransientClassification.UNDERDAMPED
            tailDriftRatio > 0.10 -> TransientClassification.OVERDAMPED
            else -> TransientClassification.CRITICALLY_DAMPED
        }
    }

    /**
     * FFT analysis of a telemetry signal to find dominant frequencies (e.g. vibrations or oscillations).
     */
    fun performFftAnalysis(values: DoubleArray, sampleRateHz: Double): FftResult {
        if (values.size < 4 || values.size > MAX_FFT_SAMPLES ||
            !sampleRateHz.isFinite() || sampleRateHz <= 0.0 || values.any { !it.isFinite() }
        ) {
            return FftResult(emptyDoubleArray(), emptyDoubleArray(), 0.0)
        }

        // FFT size must be power of two
        val n = values.size
        val nextPow2 = nextPowerOfTwo(n)
        val padded = DoubleArray(nextPow2)
        val scale = values.maxOf { abs(it) }
        var mean = 0.0
        if (scale > 0.0) {
            for (value in values) mean += value / scale
            mean /= n
        }
        var windowSum = 0.0
        for (i in values.indices) {
            val window = 0.5 * (1.0 - kotlin.math.cos(2.0 * kotlin.math.PI * i / (n - 1)))
            padded[i] = (if (scale > 0.0) values[i] / scale - mean else 0.0) * window
            windowSum += window
        }
        val transformer = FastFourierTransformer(DftNormalization.STANDARD)
        val complex = transformer.transform(padded, TransformType.FORWARD)

        // One-sided amplitudes include DC and Nyquist once; interior bins combine both sides.
        val half = nextPow2 / 2
        val frequencies = DoubleArray(half + 1)
        val magnitudes = DoubleArray(half + 1)

        for (i in 0..half) {
            frequencies[i] = (i.toDouble() / nextPow2) * sampleRateHz
            val factor = if (i == 0 || i == half) 1.0 else 2.0
            magnitudes[i] = (factor * complex[i].abs() / windowSum) * scale
            if (!magnitudes[i].isFinite()) return FftResult(emptyDoubleArray(), emptyDoubleArray(), 0.0)
        }

        // Select the peak from the windowed transform, before endpoint amplitude scaling.
        // Doubling interior bins can otherwise promote Hann leakage beside Nyquist.
        var maxMag = 0.0
        var dominantFreq = 0.0
        for (i in 1..half) {
            val magnitude = complex[i].abs()
            if (magnitude > maxMag) {
                maxMag = magnitude
                dominantFreq = frequencies[i]
            }
        }

        return FftResult(frequencies, magnitudes, dominantFreq)
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var k = 1
        while (k < n) k = k shl 1
        return k
    }

    private fun emptyDoubleArray() = DoubleArray(0)

    private companion object {
        const val MAX_ALIGNMENT_DELTA_US = 50_000L
        const val MIN_SYSID_VELOCITY = 1e-4
        const val MAX_FFT_SAMPLES = 1 shl 20
    }
}

data class AlignedDataRow(
    val timestampMs: Long,
    val voltage: Double,
    val velocity: Double,
    val accel: Double
)

data class FftResult(
    val frequencies: DoubleArray,
    val magnitudes: DoubleArray,
    val dominantFrequency: Double
)
