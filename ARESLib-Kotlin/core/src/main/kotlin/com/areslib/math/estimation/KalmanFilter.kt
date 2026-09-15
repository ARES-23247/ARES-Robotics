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
 * @throws IllegalArgumentException If the initial state is nonfinite or its error is nonfinite/negative.
 * The first valid observation seeds state directly; [reset] instead supplies a prior to filter.
 * Invalid observations or noise parameters suppress updates without changing state or covariance.
 */
class KalmanFilter(
    private var processNoise: Double,
    private var measurementNoise: Double,
    initialState: Double = 0.0,
    initialError: Double = 1.0
) {
    private val configuredInitialError = initialError
    private var x = initialState // Estimated state
    private var p = initialError // Error covariance
    private var hasFirstValue = false

    init {
        require(initialState.isFinite()) { "Initial state must be finite" }
        require(initialError.isFinite() && initialError >= 0.0) { "Initial error must be finite and nonnegative" }
    }

    /**
     * Updates the filter state with a new raw measurement and returns the optimal estimate $\hat{x}_k$.
     *
     * @param measurement Raw sensor measurement value $z_k$.
     * @return Calculated optimal state estimate $\hat{x}_k$.
     */
    fun calculate(measurement: Double): Double {
        if (!measurement.isFinite() || !processNoise.isFinite() || processNoise < 0.0 ||
            !measurementNoise.isFinite() || measurementNoise < 0.0) {
            return x
        }

        if (!hasFirstValue) {
            x = measurement
            hasFirstValue = true
            return measurement
        }

        // 1. Predict (Time Update)
        val predicted = p + processNoise

        // 2. Correct (Measurement Update)
        val denominator = predicted + measurementNoise
        val k = if (!denominator.isFinite()) {
            // Normalize the sum, not its already-overflowed result. The posterior
            // is bounded by R even when P + Q cannot be represented as a Double.
            val scale = maxOf(p, processNoise, measurementNoise)
            val scaledPrediction = p / scale + processNoise / scale
            scaledPrediction / (scaledPrediction + measurementNoise / scale)
        } else if (denominator > 0.0) predicted / denominator else 0.0
        
        val delta = measurement - x
        if (delta.isFinite()) {
            x += k * delta
        } else {
            // Opposite finite extremes can have an infinite difference while
            // their convex combination remains finite.
            x = (1.0 - k) * x + k * measurement
        }
        
        // Multiply the smaller variance by the well-resolved weight. This avoids
        // cancellation at K ~= 1 and preserves P when an extremely small K rounds to zero.
        p = if (predicted <= measurementNoise) predicted * (1.0 - k) else measurementNoise * k

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
     * @throws IllegalArgumentException If state is nonfinite or error is nonfinite/negative.
     * Invalid arguments leave the previous filter state unchanged.
     */
    fun reset(state: Double = 0.0, error: Double = 1.0) {
        require(state.isFinite()) { "State must be finite" }
        require(error.isFinite() && error >= 0.0) { "Error must be finite and nonnegative" }
        x = state
        p = error
        hasFirstValue = true
    }

    /**
     * Clears internal state memory, forcing the next measurement to initialize state directly without filtering.
     */
    fun clear() {
        x = 0.0
        p = configuredInitialError
        hasFirstValue = false
    }

    /** Returns the last computed optimal estimate $\hat{x}_k$. */
    val value: Double
        get() = x
}

