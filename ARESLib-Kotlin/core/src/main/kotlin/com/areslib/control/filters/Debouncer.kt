package com.areslib.control.filters

import com.areslib.util.RobotClock

/**
 * Hysteresis Time-Domain Debouncing Filter for Boolean Signals and Digital Sensors.
 *
 * Suppresses high-frequency mechanical contact chatter or digital sensor noise by requiring an input signal to hold its state
 * continuously for specified rising ($T_{rising}$) and falling ($T_{falling}$) time durations before updating the output boolean state.
 *
 * ### Discrete State Transition Logic:
 * Upon input change ($x(t) \neq x_{baseline}$), reset state change timestamp $t_{change} \gets t$.
 * Output state updates according to:
 * $$y(t) = \begin{cases} \text{true} & x(t) = \text{true} \text{ and } (t - t_{change}) \ge T_{rising} \\ \text{false} & x(t) = \text{false} \text{ and } (t - t_{change}) \ge T_{falling} \\ y(t-1) & \text{otherwise} \end{cases}$$
 *
 * ### Physical Units & Properties:
 * - Time Thresholds ($T_{rising}, T_{falling}$): Milliseconds ($ms$)
 * - System Clock: Deterministic [RobotClock] timestamp ($ms$)
 * - Zero-GC Footprint: Operates with zero heap allocations during 50Hz update cycles.
 *
 * @param risingTimeMs Minimum continuous duration in milliseconds ($ms$) raw input must remain `true` before output transitions to `true`.
 * @param fallingTimeMs Minimum continuous duration in milliseconds ($ms$) raw input must remain `false` before output transitions to `false` (defaults to [risingTimeMs]).
 */
class Debouncer(
    private val risingTimeMs: Long,
    private val fallingTimeMs: Long = risingTimeMs
) {
    init {
        require(risingTimeMs >= 0L && fallingTimeMs >= 0L) { "Debounce durations must be non-negative" }
    }

    private var lastStateChangeTimeMs: Long = RobotClock.currentTimeMillis()
    private var lastSampleTimeMs: Long = lastStateChangeTimeMs
    private var outputState: Boolean = false
    private var baselineState: Boolean = false

    /**
     * Calculates the debounced boolean output state given the current raw input boolean reading.
     * A replay clock rewind restarts the pending dwell while retaining the last output.
     *
     * @param input Raw boolean signal reading from switch or digital sensor.
     * @return Debounced boolean output state ($y(t)$).
     */
    fun calculate(input: Boolean): Boolean {
        val currentTimeMs = RobotClock.currentTimeMillis()

        if (input != baselineState || currentTimeMs < lastSampleTimeMs) {
            baselineState = input
            lastStateChangeTimeMs = currentTimeMs
        }
        lastSampleTimeMs = currentTimeMs

        // After rewind handling, ordered timestamps with a negative subtraction have exceeded
        // Long.MAX_VALUE elapsed milliseconds. Every representable dwell has then completed.
        val difference = currentTimeMs - lastStateChangeTimeMs
        val elapsed = if (difference < 0L) Long.MAX_VALUE else difference

        if (input) {
            if (elapsed >= risingTimeMs) {
                outputState = true
            }
        } else {
            if (elapsed >= fallingTimeMs) {
                outputState = false
            }
        }

        return outputState
    }
}
