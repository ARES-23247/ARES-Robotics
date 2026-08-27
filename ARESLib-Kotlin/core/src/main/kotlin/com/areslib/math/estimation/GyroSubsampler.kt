package com.areslib.math.estimation

import kotlin.math.abs

/**
 * Pre-allocated snapshot of a single high-rate gyroscope reading.
 *
 * @property timestampMs System timestamp in milliseconds ($ms$).
 * @property gyroRateRadPerSec Raw IMU yaw rate in radians per second ($rad/s$), CCW-positive.
 */
data class GyroSample(
    var timestampMs: Long = 0L,
    var gyroRateRadPerSec: Double = 0.0
)

/**
 * High-Rate Gyroscope Subsampling & Numerical Integration Engine.
 *
 * Accumulates high-rate raw IMU gyro measurements ($200\text{--}1000\text{ Hz}$) in a pre-allocated circular ring buffer
 * and integrates angular displacement over an odometry frame window $[t_{\text{start}}, t_{\text{end}}]$ using **Simpson's 3/8 Rule**
 * ($O(\Delta t^3)$ numerical accuracy).
 *
 * ### Mathematical Formulation (Simpson's 3/8 Integration):
 * $$\int_{t_0}^{t_3} \omega(t) \, dt \approx \frac{3 \Delta t}{8} \left( \omega_0 + 3\omega_1 + 3\omega_2 + \omega_3 \right)$$
 *
 * ### Zero-GC Guarantee:
 * Uses pre-allocated circular ring arrays to guarantee 100% Zero-GC heap compliance on Android ART and RoboRIO JVM runtimes.
 *
 * @param capacity Maximum number of gyro samples to retain (default $256$).
 */
class GyroSubsampler(private val capacity: Int = 256) {
    private val buffer = Array(capacity) { GyroSample() }
    private var head = 0
    private var count = 0

    /** Total number of active gyro samples retained in the ring buffer. */
    val size: Int get() = count

    /**
     * Records a new high-rate raw IMU gyroscope reading into the circular buffer.
     *
     * @param timestampMs System timestamp in milliseconds ($ms$).
     * @param gyroRateRadPerSec Raw yaw angular velocity in radians per second ($rad/s$), CCW-positive.
     */
    fun addSample(timestampMs: Long, gyroRateRadPerSec: Double) {
        if (gyroRateRadPerSec.isNaN() || gyroRateRadPerSec.isInfinite()) return
        val sample = buffer[head]
        sample.timestampMs = timestampMs
        sample.gyroRateRadPerSec = gyroRateRadPerSec
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    /**
     * Resets the circular buffer history.
     */
    fun clear() {
        head = 0
        count = 0
    }

    /**
     * Numerically integrates bias-corrected angular velocity over the time window $[t_{\text{start}}, t_{\text{end}}]$
     * using Simpson's 3/8 Rule when $\ge 4$ sub-samples exist, or composite trapezoidal integration.
     *
     * @param startTimeMs Window start timestamp in milliseconds ($ms$).
     * @param endTimeMs Window end timestamp in milliseconds ($ms$).
     * @param gyroBiasRadPerSec Current estimated gyroscope zero-rate bias in radians per second ($rad/s$).
     * @return Integrated angular displacement change $\Delta\theta$ in radians ($rad$), CCW-positive.
     */
    fun integrateWindow(startTimeMs: Long, endTimeMs: Long, gyroBiasRadPerSec: Double = 0.0): Double {
        if (count == 0 || endTimeMs <= startTimeMs) return 0.0

        // Find samples inside [startTimeMs, endTimeMs]
        var startIndex = -1
        var endIndex = -1
        val startPhys = if (count == capacity) head else 0

        for (i in 0 until count) {
            val idx = (startPhys + i) % capacity
            val sample = buffer[idx]
            if (sample.timestampMs >= startTimeMs && startIndex == -1) {
                startIndex = i
            }
            if (sample.timestampMs <= endTimeMs) {
                endIndex = i
            }
        }

        if (startIndex == -1 || endIndex == -1 || startIndex > endIndex) return 0.0

        val windowSize = endIndex - startIndex + 1

        if (windowSize == 1) {
            val dtSec = (endTimeMs - startTimeMs) / 1000.0
            val idx = (startPhys + startIndex) % capacity
            return (buffer[idx].gyroRateRadPerSec - gyroBiasRadPerSec) * dtSec
        }

        var integratedRad = 0.0

        // If 4 or more samples, apply composite Simpson's 3/8 rule where possible
        var current = startIndex
        while (current + 3 <= endIndex) {
            val idx0 = (startPhys + current) % capacity
            val idx1 = (startPhys + current + 1) % capacity
            val idx2 = (startPhys + current + 2) % capacity
            val idx3 = (startPhys + current + 3) % capacity

            val s0 = buffer[idx0]
            val s3 = buffer[idx3]
            val subDtSec = (s3.timestampMs - s0.timestampMs) / 1000.0

            if (subDtSec > 0.0) {
                val w0 = buffer[idx0].gyroRateRadPerSec - gyroBiasRadPerSec
                val w1 = buffer[idx1].gyroRateRadPerSec - gyroBiasRadPerSec
                val w2 = buffer[idx2].gyroRateRadPerSec - gyroBiasRadPerSec
                val w3 = buffer[idx3].gyroRateRadPerSec - gyroBiasRadPerSec

                integratedRad += (subDtSec / 8.0) * (w0 + 3.0 * w1 + 3.0 * w2 + w3)
            }
            current += 3
        }

        // Composite trapezoidal rule for remaining tail samples
        while (current < endIndex) {
            val idxA = (startPhys + current) % capacity
            val idxB = (startPhys + current + 1) % capacity
            val sA = buffer[idxA]
            val sB = buffer[idxB]
            val subDtSec = (sB.timestampMs - sA.timestampMs) / 1000.0

            if (subDtSec > 0.0) {
                val wA = sA.gyroRateRadPerSec - gyroBiasRadPerSec
                val wB = sB.gyroRateRadPerSec - gyroBiasRadPerSec
                integratedRad += 0.5 * (wA + wB) * subDtSec
            }
            current++
        }

        return integratedRad
    }
}
