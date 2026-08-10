package com.areslib.math.estimation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KalmanFilterTest declaration.
 *
 * @param args Standard arguments (if applicable).
 * @return Corresponding output value or Unit.
 */
class KalmanFilterTest {

    @Test
    /**
     * testInitialization declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testInitialization() {
        val filter = KalmanFilter(processNoise = 0.01, measurementNoise = 0.1)

        // First calculation should snap directly to the measurement
        assertEquals(5.0, filter.calculate(5.0), 1e-6)
        assertEquals(5.0, filter.value, 1e-6)
    }

    @Test
    fun `first observation preserves the configured initial error covariance`() {
        val filter = KalmanFilter(
            processNoise = 0.0,
            measurementNoise = 1.0,
            initialError = 9.0
        )

        assertEquals(10.0, filter.calculate(10.0), 1e-12)

        // The second observation's gain is 9 / (9 + 1) = 0.9. Resetting the
        // covariance to the old hard-coded 1.0 would instead produce 5.0.
        assertEquals(1.0, filter.calculate(0.0), 1e-12)

        filter.clear()
        assertEquals(10.0, filter.calculate(10.0), 1e-12)
        assertEquals(1.0, filter.calculate(0.0), 1e-12)
    }

    @Test
    /**
     * testNoiseReductionConvergence declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testNoiseReductionConvergence() {
        // High measurement noise (R = 1.0), low process noise (Q = 0.01)
        // Means the filter will heavily smooth out random variations and trust its state prediction.
        val filter = KalmanFilter(processNoise = 0.01, measurementNoise = 1.0, initialState = 10.0)

        val trueValue = 10.0
        // Simulate 50 noisy measurements centered around 10.0
        val noisyMeasurements = doubleArrayOf(
            11.2, 8.9, 10.5, 9.2, 11.8, 8.5, 10.1, 9.9, 10.6, 9.4,
            11.0, 9.1, 10.7, 9.3, 11.5, 8.7, 10.2, 9.8, 10.4, 9.6,
            11.1, 8.8, 10.3, 9.5, 11.3, 8.6, 10.0, 10.0, 10.5, 9.5,
            11.0, 9.0, 10.8, 9.2, 11.4, 8.9, 10.1, 9.9, 10.3, 9.7,
            10.9, 9.1, 10.6, 9.4, 11.2, 8.8, 10.2, 9.8, 10.4, 9.6
        )

        var lastEstimate = 10.0
        for (measurement in noisyMeasurements) {
            lastEstimate = filter.calculate(measurement)
        }

        // The final state estimate should have converged close to the true value (10.0),
        // and have significantly less variance than the individual noisy readings (which range from 8.5 to 11.8).
        assertEquals(trueValue, lastEstimate, 0.5)
    }

    @Test
    /**
     * testNoiseParametersDynamicallyChange declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testNoiseParametersDynamicallyChange() {
        val filter = KalmanFilter(processNoise = 0.01, measurementNoise = 1.0, initialState = 10.0)
        filter.calculate(10.0)

        // Make measurement noise extremely tiny (R = 1e-9) -> filter should snap immediately to new measurements
        filter.setNoiseParameters(processNoise = 0.01, measurementNoise = 1e-9)
        assertEquals(25.0, filter.calculate(25.0), 1e-4)
    }

    @Test
    /**
     * testReset declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testReset() {
        val filter = KalmanFilter(0.1, 0.5)
        filter.calculate(10.0)
        filter.calculate(12.0)

        filter.reset(state = 42.0, error = 0.2)
        assertEquals(42.0, filter.value, 1e-6)
    }

    @Test
    /**
     * testClear declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun testClear() {
        val filter = KalmanFilter(0.1, 0.5)
        filter.calculate(10.0)
        filter.calculate(12.0)

        filter.clear()
        // First value should snap directly after clear
        assertEquals(100.0, filter.calculate(100.0), 1e-6)
    }
}
