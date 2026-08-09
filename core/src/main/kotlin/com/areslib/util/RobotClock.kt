package com.areslib.util

/**
 * Global time provider for the robot, allowing time to be mocked/injected
 * during log replay.
 */
object RobotClock {
    private var mocked = false
    private var mockTimeMs = 0L
    private val startWallMs = System.currentTimeMillis()
    private val startNanos = System.nanoTime()

    /**
     * Get the current epoch time in milliseconds.
     * Returns the mocked timestamp if mocked, otherwise falls back to System.currentTimeMillis().
     */
    fun currentTimeMillis(): Long {
        return if (mocked) mockTimeMs else startWallMs + (System.nanoTime() - startNanos) / 1_000_000L
    }

    /**
     * Get the current time in nanoseconds.
     * Returns the mocked timestamp scaled to ns if mocked, otherwise System.nanoTime().
     */
    fun nanoTime(): Long {
        return if (mocked) mockTimeMs * 1_000_000L else System.nanoTime()
    }

    /**
     * Set the global time to mock mode and inject a static timestamp.
     */
    fun useMockTime(timeMs: Long) {
        mocked = true
        mockTimeMs = timeMs
    }

    /**
     * Helper specifically for E2E tests using setMockTimeMs naming convention.
     */
    fun setMockTimeMs(timeMs: Long) {
        useMockTime(timeMs)
    }

    /**
     * Revert the global clock to real-time system clock operation.
     */
    fun useSystemTime() {
        mocked = false
    }

    /**
     * Helper to check if the clock is currently mocked.
     */
    val isMocked: Boolean get() = mocked
}
