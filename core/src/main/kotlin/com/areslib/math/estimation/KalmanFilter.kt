package com.areslib.math.estimation

/**
 * Optimized 1D Discrete Linear Kalman Filter.
 *
 * Recursively estimates the true state of a scalar 1-DOF physical system from a sequence of noisy sensor observations.
 * Ideal for filtering continuous signals like drivetrain velocity, analog distance sensors, battery voltage,
 * or gyro heading rates by balancing process model uncertainty ($Q$) against physical measurement noise ($R$).
 *
 * ### Mathematical Formulation:
 * 1. **Time Update (Predict)**:
 *    $$\hat{x}_k^- = \hat{x}_{k-1}$$
 *    $$P_k^- = P_{k-1} + Q$$
 * 2. **Measurement Update (Correct)**:
 *    $$K_k = \frac{P_k^-}{P_k^- + R}$$
 *    $$\hat{x}_k = \hat{x}_k^- + K_k \cdot (z_k - \hat{x}_k^-)$$
 *    $$P_k = (1 - K_k) \cdot P_k^-$$
 *
 * ### Physical Units & Properties:
 * - State $\hat{x}_k$, Measurement $z_k$: Arbitrary physical signal units ($m$, $m/s$, $rad/s$, $V$)
 * - Process Noise Covariance $Q$: Variance in system state transition ($[units]^2$)
 * - Measurement Noise Covariance $R$: Variance in physical sensor readings ($[units]^2$)
 *
 * ### Zero-GC Guarantee:
 * Executes in $O(1)$ time with zero dynamic memory allocations on hot 50Hz/100Hz control loops.
 *
 * @param processNoise Initial process noise covariance variance $Q$.
 * @param measurementNoise Initial measurement noise covariance variance $R$.
 * @param initialState Seed initial state estimate $\hat{x}_0$ (default $0.0$).
 * @param initialError Seed initial error covariance $P_0$ (default $1.0$).
 */
class KalmanFilter(
    private var processNoise: Double,
    private var measurementNoise: Double,
    initialState: Double = 0.0,
    initialError: Double = 1.0
) {
    private var x = initialState // Estimated state
    private var p = initialError // Error covariance
    private var hasFirstValue = false

    /**
     * Updates the filter state with a new raw measurement and returns the optimal estimate $\hat{x}_k$.
     *
     * @param measurement Raw sensor measurement value $z_k$.
     * @return Calculated optimal state estimate $\hat{x}_k$.
     */
    fun calculate(measurement: Double): Double {
        if (!measurement.isFinite() || !processNoise.isFinite() || !measurementNoise.isFinite()) {
            return x
        }

        if (!hasFirstValue) {
            x = measurement
            p = 1.0 // Initialize with unit covariance
            hasFirstValue = true
            return measurement
        }

        // 1. Predict (Time Update)
        p += processNoise

        // 2. Correct (Measurement Update)
        val denominator = p + measurementNoise
        val k = if (kotlin.math.abs(denominator) > 1e-12) p / denominator else 0.0 // Kalman Gain with div-by-zero protection
        
        val delta = measurement - x
        if (delta.isFinite()) {
            x += k * delta
        }
        
        p *= (1.0 - k)                     // Update error covariance

        return x
    }

    /**
     * Dynamically updates the process and measurement noise variance parameters.
     *
     * @param processNoise New process noise covariance $Q$.
     * @param measurementNoise New measurement noise covariance $R$.
     */
    fun setNoiseParameters(processNoise: Double, measurementNoise: Double) {
        this.processNoise = processNoise
        this.measurementNoise = measurementNoise
    }

    /**
     * Resets the filter to a specific baseline state estimate and error covariance.
     *
     * @param state Baseline state estimate $\hat{x}_0$.
     * @param error Baseline error covariance $P_0$.
     */
    fun reset(state: Double = 0.0, error: Double = 1.0) {
        x = state
        p = error
        hasFirstValue = true
    }

    /**
     * Clears internal state memory, forcing the next measurement to initialize state directly without filtering.
     */
    fun clear() {
        x = 0.0
        p = 1.0
        hasFirstValue = false
    }

    /** Returns the last computed optimal estimate $\hat{x}_k$. */
    val value: Double
        get() = x
}

