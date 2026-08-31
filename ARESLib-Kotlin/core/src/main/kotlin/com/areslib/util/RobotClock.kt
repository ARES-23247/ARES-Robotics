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
    /**
     * One immutable mode snapshot prevents readers on different robot/simulator threads from
     * observing `mocked == true` with an older mock timestamp (or the inverse transition).
     * Volatile publication is sufficient here and keeps the 50-100 Hz read path allocation-free.
     */
    private sealed interface ClockMode {
        data object System : ClockMode
        data class Mock(val timeMs: Long) : ClockMode
    }

    @Volatile
    private var mode: ClockMode = ClockMode.System
    private val startWallMs = System.currentTimeMillis()
    private val startNanos = System.nanoTime()

    /**
     * Returns the current robot timestamp in milliseconds.
     *
     * In live mode this is epoch-like time advanced monotonically from the process-start anchor. In
     * mock mode it is exactly the last value supplied to [useMockTime].
     */
    fun currentTimeMillis(): Long {
        return when (val snapshot = mode) {
            is ClockMode.Mock -> snapshot.timeMs
            ClockMode.System -> startWallMs + (System.nanoTime() - startNanos) / 1_000_000L
        }
    }

    /**
     * Returns monotonic elapsed time in nanoseconds in live mode, or the mocked millisecond value
     * converted to nanoseconds in mock mode. Do not compare the live value to Unix epoch time.
     */
    fun nanoTime(): Long {
        return when (val snapshot = mode) {
            is ClockMode.Mock -> snapshot.timeMs * 1_000_000L
            ClockMode.System -> System.nanoTime()
        }
    }

    /**
     * Enters mock mode at the fixed timestamp [timeMs]. Calling this again advances or rewinds time.
     */
    fun useMockTime(timeMs: Long) {
        mode = ClockMode.Mock(timeMs)
    }

    /**
     * Leaves mock mode and resumes the process's monotonic live timeline.
     */
    fun useSystemTime() {
        mode = ClockMode.System
    }

    /**
     * Whether calls currently return the injected mock timestamp.
     */
    val isMocked: Boolean get() = mode is ClockMode.Mock
}
