package com.areslib.input

/**
 * Zero-allocation, monotonic-safe rumble pacing controller for gamepad haptics.
 *
 * Enforces an active rumble burst duration followed by a minimum cooldown quiet period
 * to prevent actuator burnout and tactile desensitization. Correctly handles clock rewinds
 * and non-monotonic timestamps without freezing or locking rumble on.
 *
 * @property activeDurationMs Active rumble burst duration in milliseconds (default 1000 ms).
 * @property cooldownDurationMs Minimum quiet cooldown period between bursts in milliseconds (default 2000 ms).
 */
class RumblePacer(
    val activeDurationMs: Long = DEFAULT_ACTIVE_DURATION_MS,
    val cooldownDurationMs: Long = DEFAULT_COOLDOWN_DURATION_MS,
) {
    init {
        require(activeDurationMs > 0) { "Rumble duration must be positive" }
        require(cooldownDurationMs >= 0) { "Rumble cooldown must be nonnegative" }
    }
    var isRumbleActive: Boolean = false
        private set

    var isCoolingDown: Boolean = false
        private set

    private var rumbleStartTimestampMs: Long = 0L
    private var lastDeactivationTimestampMs: Long = 0L

    /**
     * Resets active rumble and cooldown state immediately (e.g., on teleop initialize or fault latch).
     */
    fun reset() {
        isRumbleActive = false
        isCoolingDown = false
        rumbleStartTimestampMs = 0L
        lastDeactivationTimestampMs = 0L
    }

    /**
     * Updates rumble pacing state for the current loop cycle.
     *
     * @param nowMs Current monotonic epoch timestamp in milliseconds.
     * @param triggerRisingEdge True if a new alert condition fired on this cycle.
     * @param alertConditionActive True if the underlying alert condition remains active.
     * @return Commanded rumble intensity (0.0 to 1.0).
     */
    fun update(nowMs: Long, triggerRisingEdge: Boolean, alertConditionActive: Boolean): Double {
        if (isCoolingDown && nowMs < lastDeactivationTimestampMs) {
            lastDeactivationTimestampMs = nowMs
        }

        if (isRumbleActive && (!alertConditionActive || nowMs < rumbleStartTimestampMs ||
                elapsedAtLeast(nowMs, rumbleStartTimestampMs, activeDurationMs))) {
            isRumbleActive = false
            isCoolingDown = true
            lastDeactivationTimestampMs = nowMs
        }

        if (isCoolingDown && elapsedAtLeast(nowMs, lastDeactivationTimestampMs, cooldownDurationMs)) {
            isCoolingDown = false
        }

        if (triggerRisingEdge && alertConditionActive && !isRumbleActive && !isCoolingDown) {
            isRumbleActive = true
            rumbleStartTimestampMs = nowMs
        }

        return if (isRumbleActive) 1.0 else 0.0
    }

    companion object {
        const val DEFAULT_ACTIVE_DURATION_MS: Long = 1000L
        const val DEFAULT_COOLDOWN_DURATION_MS: Long = 2000L

        /**
         * Checks elapsed time with clock-rewind and signed overflow protection.
         */
        fun elapsedAtLeast(now: Long, start: Long, duration: Long): Boolean {
            if (now < start) return false
            val elapsed = now - start
            return elapsed < 0L || elapsed >= duration
        }
    }
}
