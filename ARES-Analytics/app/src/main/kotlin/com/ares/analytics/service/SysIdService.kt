package com.ares.analytics.service

import com.ares.analytics.shared.models.CalculatedSummary
import com.ares.analytics.shared.models.TelemetryFrame
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
 * 2. **OLS Matrix Solution**:
 *    $$\mathbf{Y} = \mathbf{X} \boldsymbol{\beta} \implies \boldsymbol{\beta} = (\mathbf{X}^T \mathbf{X})^{-1} \mathbf{X}^T \mathbf{Y}$$
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
 * Performs linear algebra calculations using EJML `SimpleMatrix` on background coroutines (`Dispatchers.IO`).
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
        val sortedVelocities = velocities.sortedBy { it.timestampMs }
        for (v in sortedVelocities) {
            val currentSign = sign(v.value)
            if (currentSign != 0.0 && lastSign != 0.0 && currentSign != lastSign) {
                directionChanges.add(v.timestampMs)
            }
            if (currentSign != 0.0) lastSign = currentSign
        }
        val sortedVoltages = voltages.sortedBy { it.timestampMs }
        val sortedAccels = accelerations.sortedBy { it.timestampMs }
        var voltageIdx = 0
        var accelIdx = 0
        var directionChangeIdx = 0

        for (v in sortedVelocities) {
            val t = v.timestampMs

            // Apply direction change cleansing: skip data points within ±50ms of a sign change
            while (directionChangeIdx < directionChanges.size - 1 &&
                directionChanges[directionChangeIdx + 1] <= t
            ) {
                directionChangeIdx++
            }
            val isNearDirectionChange =
                (directionChangeIdx < directionChanges.size && abs(directionChanges[directionChangeIdx] - t) <= 50) ||
                    (directionChangeIdx + 1 < directionChanges.size &&
                        abs(directionChanges[directionChangeIdx + 1] - t) <= 50)
            if (isNearDirectionChange) continue
            while (voltageIdx < sortedVoltages.size - 1 &&
                abs(sortedVoltages[voltageIdx + 1].timestampMs - t) <= abs(sortedVoltages[voltageIdx].timestampMs - t)
            ) {
                voltageIdx++
            }
            val voltageFrame = sortedVoltages[voltageIdx]
            if (abs(voltageFrame.timestampMs - t) > MAX_ALIGNMENT_DELTA_MS) continue

            // Move accelIdx forward to find nearest neighbor in O(N + M)
            while (accelIdx < sortedAccels.size - 1 &&
                abs(sortedAccels[accelIdx + 1].timestampMs - t) <= abs(sortedAccels[accelIdx].timestampMs - t)
            ) {
                accelIdx++
            }
            val accelFrame = sortedAccels[accelIdx]
            if (abs(accelFrame.timestampMs - t) > MAX_ALIGNMENT_DELTA_MS) continue

            if (voltageFrame.value.isFinite() && v.value.isFinite() && accelFrame.value.isFinite()) {
                alignedData.add(AlignedDataRow(t, voltageFrame.value, v.value, accelFrame.value))
            }
        }

        return analyzeRawData(alignedData)
    }

    fun analyzeRawData(alignedData: List<AlignedDataRow>): CalculatedSummary {
        val finiteData = alignedData.filter {
            it.voltage.isFinite() && it.velocity.isFinite() && it.accel.isFinite()
        }
        val validData = finiteData.filter { abs(it.velocity) > MIN_SYSID_VELOCITY }
        if (validData.size < 10) {
            return CalculatedSummary()
        }

        // Solve OLS: V = kS * sgn(v) + kV * v + kA * a
        // Construct matrices
        val n = validData.size
        val X = SimpleMatrix(n, 3)
        val y = SimpleMatrix(n, 1)

        for (i in 0 until n) {
            val row = validData[i]
            X.setRow(i, 0, sign(row.velocity), row.velocity, row.accel)
            y.set(i, 0, row.voltage)
        }

        return try {
            val beta = X.pseudoInverse().mult(y)
            val kS = beta.get(0, 0)
            val kV = beta.get(1, 0)
            val kA = beta.get(2, 0)
            if (!kS.isFinite() || !kV.isFinite() || !kA.isFinite()) return CalculatedSummary()

            // Compute R-squared
            val yMean = validData.map { it.voltage }.average()
            var ssTot = 0.0
            var ssRes = 0.0
            for (i in 0 until n) {
                val actual = y.get(i, 0)
                val predicted = kS * sign(validData[i].velocity) + kV * validData[i].velocity + kA * validData[i].accel
                ssTot += (actual - yMean) * (actual - yMean)
                ssRes += (actual - predicted) * (actual - predicted)
            }
            val rSquared = if (ssTot > 0) 1.0 - (ssRes / ssTot) else 0.0

            // Classify transient response
            val transientClassification = classifyTransient(finiteData)

            CalculatedSummary(
                kS = kS,
                kV = kV,
                kA = kA,
                rSquared = rSquared,
                transientClassification = transientClassification
            )
        } catch (e: Exception) {
            e.printStackTrace()
            CalculatedSummary()
        }
    }

    private fun classifyTransient(data: List<AlignedDataRow>): TransientClassification {
        // Find a step-like voltage increase (e.g. from < 1.0 to > 6.0)
        var stepStartIdx = -1
        for (i in 1 until data.size) {
            if (data[i - 1].voltage < 1.0 && data[i].voltage > 6.0) {
                stepStartIdx = i
                break
            }
        }
        if (stepStartIdx == -1) return TransientClassification.UNKNOWN

        // Trace velocity after step
        val transientPoints = data.subList(stepStartIdx, minOf(stepStartIdx + 30, data.size))
        if (transientPoints.isEmpty()) return TransientClassification.UNKNOWN
        // Find steady state velocity (average of last 10 points)
        val steadyStateVel = if (transientPoints.size > 10) {
            transientPoints.takeLast(10).map { it.velocity }.average()
        } else {
            transientPoints.last().velocity
        }

        if (!steadyStateVel.isFinite() || abs(steadyStateVel) <= 1e-6) {
            return TransientClassification.UNKNOWN
        }
        // Normalize by the signed final response so negative-going steps classify the
        // same way as positive ones. The old `maxVel - average(last 10)` test could
        // never be negative because a maximum is always at least that subset's mean,
        // making OVERDAMPED unreachable.
        val normalizedResponse = transientPoints.map { it.velocity / steadyStateVel }
        val peakProgress = normalizedResponse.maxOrNull() ?: return TransientClassification.UNKNOWN
        val tail = transientPoints.takeLast(minOf(10, transientPoints.size)).map { it.velocity }
        val tailDriftRatio = ((tail.maxOrNull() ?: steadyStateVel) - (tail.minOrNull() ?: steadyStateVel)) /
            abs(steadyStateVel)
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
        val mean = values.average()
        var windowSum = 0.0
        for (i in values.indices) {
            val window = 0.5 * (1.0 - kotlin.math.cos(2.0 * kotlin.math.PI * i / (n - 1)))
            padded[i] = (values[i] - mean) * window
            windowSum += window
        }
        val transformer = FastFourierTransformer(DftNormalization.STANDARD)
        val complex = transformer.transform(padded, TransformType.FORWARD)

        // Magnitudes of first half
        val half = nextPow2 / 2
        val frequencies = DoubleArray(half)
        val magnitudes = DoubleArray(half)

        for (i in 0 until half) {
            frequencies[i] = i * sampleRateHz / nextPow2
            magnitudes[i] = if (windowSum > 0.0) 2.0 * complex[i].abs() / windowSum else 0.0
        }

        // Find dominant frequency (excluding DC component at index 0)
        var maxMag = 0.0
        var dominantFreq = 0.0
        for (i in 1 until half) {
            if (magnitudes[i] > maxMag) {
                maxMag = magnitudes[i]
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
        const val MAX_ALIGNMENT_DELTA_MS = 50L
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
