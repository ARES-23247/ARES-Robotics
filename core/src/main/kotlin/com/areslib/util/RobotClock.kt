package com.areslib.util

/**
 * Process-wide robot clock used by live code, simulation, tests, and deterministic replay.
 *
 * Live millisecond time is anchored to the wall clock once, then advanced from the monotonic
 * nanosecond clock. This avoids discontinuities when the host wall clock changes during a run.
 * Mock mode is a fixed instant: time advances only when the replay or test calls [useMockTime]
 * again. The mock controls both [currentTimeMillis] and [nanoTime] on the same timeline.
 *
 * Mode changes are process-global and are expected to be owned by lifecycle/test setup code, not
 * by control-loop components. Always restore [useSystemTime] after a test or replay session.
 */
object RobotClock {
    private var mocked = false
    private var mockTimeMs = 0L
    private val startWallMs = System.currentTimeMillis()
    private val startNanos = System.nanoTime()

    /**
     * Returns the current robot timestamp in milliseconds.
     *
     * In live mode this is epoch-like time advanced monotonically from the process-start anchor. In
     * mock mode it is exactly the last value supplied to [useMockTime].
     */
    fun currentTimeMillis(): Long {
        return if (mocked) mockTimeMs else startWallMs + (System.nanoTime() - startNanos) / 1_000_000L
    }

    /**
     * Returns monotonic elapsed time in nanoseconds in live mode, or the mocked millisecond value
     * converted to nanoseconds in mock mode. Do not compare the live value to Unix epoch time.
     */
    fun nanoTime(): Long {
        return if (mocked) mockTimeMs * 1_000_000L else System.nanoTime()
    }

    /**
     * Enters mock mode at the fixed timestamp [timeMs]. Calling this again advances or rewinds time.
     */
    fun useMockTime(timeMs: Long) {
        mocked = true
        mockTimeMs = timeMs
    }

    /**
     * Compatibility alias for [useMockTime].
     */
    fun setMockTimeMs(timeMs: Long) {
        useMockTime(timeMs)
    }

    /**
     * Leaves mock mode and resumes the process's monotonic live timeline.
     */
    fun useSystemTime() {
        mocked = false
    }

    /**
     * Whether calls currently return the injected mock timestamp.
     */
    val isMocked: Boolean get() = mocked
}
