package com.areslib.util

/**
 * Process-wide robot clock used by live code, simulation, tests, and deterministic replay.
 *
 * Live millisecond time is anchored to the wall clock once, then advanced from the monotonic
 * nanosecond clock. This avoids discontinuities when the host wall clock changes during a run.
 * Mock mode is a fixed instant: time advances only when the replay or test calls [useMockTime]
 * again. The mock controls both time methods; nanosecond conversion wraps in signed 64-bit
 * arithmetic. Use subtraction for bounded elapsed intervals, not ordering of raw nanoTime values.
 *
 * Mode changes are process-global and are expected to be owned by lifecycle/test setup code, not
 * by control-loop components. Always restore [useSystemTime] after a test or replay session.
 */
object RobotClock {
    /**
     * Each getter reads one immutable mode snapshot, so it cannot observe a partially published
     * timestamp. Separate getter calls may observe different modes if their owner switches mode
     * between them; the API does not provide an atomic multi-getter transaction.
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
     * In live mode this is epoch-like time advanced from the fixed process-start anchor, within
     * the signed nanoTime elapsed range (less than 2^63 nanoseconds since that anchor). In mock
     * mode it is exactly the last value supplied to [useMockTime], including negative values.
     */
    fun currentTimeMillis(): Long {
        return when (val snapshot = mode) {
            is ClockMode.Mock -> snapshot.timeMs
            ClockMode.System -> startWallMs + (System.nanoTime() - startNanos) / 1_000_000L
        }
    }

    /**
     * Returns the JVM's nanosecond time source in live mode, or mock milliseconds multiplied by
     * 1,000,000 modulo 2^64. Raw values may be negative and may wrap. Subtraction recovers intervals
     * shorter than 2^63 nanoseconds within one unchanged mode/timeline; crossing modes, rewinding
     * mock time or exceeding that range is not a valid forward elapsed interval. Live nanoTime
     * has an arbitrary origin and must not be compared to Unix epoch time.
     */
    fun nanoTime(): Long {
        return when (val snapshot = mode) {
            is ClockMode.Mock -> snapshot.timeMs * 1_000_000L
            ClockMode.System -> System.nanoTime()
        }
    }

    /**
     * Monotonic host time for pacing and measuring a desktop simulator while robot time is mocked.
     * This clock never advances the robot timeline. Do not use it for controller dt, feedback
     * freshness, leases, or replay timestamps: those must continue to use [nanoTime] and
     * [currentTimeMillis]. Like nanoTime, only differences within 2^63 nanoseconds are meaningful.
     */
    fun hostNanoTime(): Long = System.nanoTime()

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
