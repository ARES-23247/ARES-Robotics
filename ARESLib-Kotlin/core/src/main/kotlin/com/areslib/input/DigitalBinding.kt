package com.areslib.input

/** Why an accepted digital activation ended. */
enum class BindingReleaseReason {
    /** The debounced physical or virtual input became inactive. */
    INPUT_RELEASED,

    /** The controller disconnected. */
    DISCONNECTED,

    /** The binding runtime was explicitly cancelled, normally on disable or OpMode stop. */
    CANCELLED,

    /** Deterministic time moved backwards and active timing state could no longer be preserved. */
    TIME_REWIND,

    /** The binding's configured maximum activation duration elapsed. */
    MAXIMUM_DURATION,
}

/**
 * Allocation-free callback target for one digital binding.
 *
 * A generated DSL may adapt lambdas to this interface during initialization. Implementations must
 * return quickly and should normally dispatch Redux actions instead of touching hardware.
 */
interface DigitalBindingListener {
    fun onPress() = Unit
    fun onHeld(heldForNanos: Long) = Unit
    fun onHold(heldForNanos: Long) = Unit
    fun onRepeat(heldForNanos: Long) = Unit
    fun onRelease(heldForNanos: Long, reason: BindingReleaseReason) = Unit
}

/** Timing and rate-limit policy for a [DigitalBinding]. All durations use monotonic nanoseconds. */
data class DigitalBindingTiming(
    val pressDebounceNanos: Long = 0L,
    val releaseDebounceNanos: Long = 0L,
    /** Negative disables the one-shot hold callback. */
    val holdAfterNanos: Long = -1L,
    /** Negative disables repeats. */
    val repeatAfterNanos: Long = -1L,
    val repeatEveryNanos: Long = 0L,
    /** Minimum interval between accepted press edges. */
    val cooldownNanos: Long = 0L,
    /** Negative disables the safety limit. */
    val maximumActiveNanos: Long = -1L,
) {
    init {
        require(pressDebounceNanos >= 0L) { "press debounce must not be negative" }
        require(releaseDebounceNanos >= 0L) { "release debounce must not be negative" }
        require(holdAfterNanos >= -1L) { "holdAfterNanos must be -1 or non-negative" }
        require(repeatAfterNanos >= -1L) { "repeatAfterNanos must be -1 or non-negative" }
        require(cooldownNanos >= 0L) { "cooldown must not be negative" }
        require(maximumActiveNanos >= -1L) { "maximumActiveNanos must be -1 or non-negative" }
        if (repeatAfterNanos >= 0L) {
            require(repeatEveryNanos > 0L) { "repeatEveryNanos must be positive when repeats are enabled" }
        }
    }
}

/**
 * Deterministic state machine for press, release, held, hold, repeat, debounce, and cooldown.
 *
 * Debounce changes only confirmed edges; [DigitalBindingListener.onHeld] continues while a release
 * is being debounced. Cooldown rejects an entire activation rather than starting it later while a
 * button remains held. Disconnect/cancel bypasses release debounce and synthesizes exactly one
 * release for every accepted active binding.
 */
class DigitalBinding(
    val source: DigitalSource,
    val timing: DigitalBindingTiming = DigitalBindingTiming(),
    val listener: DigitalBindingListener,
) {
    private var hasCandidate = false
    private var candidate = false
    private var candidateSinceNanos = 0L
    private var stableActive = false
    private var acceptedActivation = false
    private var pressedAtNanos = 0L
    private var lastAcceptedPressNanos = Long.MIN_VALUE
    private var holdFired = false
    private var nextRepeatNanos = Long.MAX_VALUE
    private var waitingForNeutral = false

    val isActive: Boolean get() = stableActive && acceptedActivation

    fun update(frame: InputFrame, nowNanos: Long) {
        val sampled = source.sample(frame, nowNanos)

        if (waitingForNeutral) {
            if (sampled) return
            waitingForNeutral = false
            hasCandidate = true
            candidate = false
            candidateSinceNanos = nowNanos
            return
        }

        if (!hasCandidate || sampled != candidate) {
            hasCandidate = true
            candidate = sampled
            candidateSinceNanos = nowNanos
        }

        if (candidate != stableActive) {
            val debounce = if (candidate) timing.pressDebounceNanos else timing.releaseDebounceNanos
            if (elapsedNanos(nowNanos, candidateSinceNanos) >= debounce) {
                if (candidate) confirmPress(nowNanos) else confirmRelease(nowNanos)
            }
        }

        if (!stableActive || !acceptedActivation) return
        val heldFor = elapsedNanos(nowNanos, pressedAtNanos)
        if (timing.maximumActiveNanos >= 0L && heldFor >= timing.maximumActiveNanos) {
            terminate(nowNanos, BindingReleaseReason.MAXIMUM_DURATION, requireNeutral = true)
            return
        }
        listener.onHeld(heldFor)

        if (!holdFired && timing.holdAfterNanos >= 0L && heldFor >= timing.holdAfterNanos) {
            holdFired = true
            listener.onHold(heldFor)
        }

        if (timing.repeatAfterNanos >= 0L && nowNanos >= nextRepeatNanos) {
            listener.onRepeat(heldFor)
            nextRepeatNanos = nextRepeatDeadline(nextRepeatNanos, nowNanos, timing.repeatEveryNanos)
        }
    }

    private fun confirmPress(nowNanos: Long) {
        stableActive = true
        pressedAtNanos = nowNanos
        holdFired = false
        nextRepeatNanos = if (timing.repeatAfterNanos >= 0L) {
            saturatingAdd(nowNanos, timing.repeatAfterNanos)
        } else {
            Long.MAX_VALUE
        }

        val outsideCooldown = lastAcceptedPressNanos == Long.MIN_VALUE ||
            elapsedNanos(nowNanos, lastAcceptedPressNanos) >= timing.cooldownNanos
        acceptedActivation = outsideCooldown
        if (outsideCooldown) {
            lastAcceptedPressNanos = nowNanos
            listener.onPress()
        }
    }

    private fun confirmRelease(nowNanos: Long) {
        val heldFor = elapsedNanos(nowNanos, pressedAtNanos)
        if (acceptedActivation) listener.onRelease(heldFor, BindingReleaseReason.INPUT_RELEASED)
        stableActive = false
        acceptedActivation = false
        holdFired = false
        nextRepeatNanos = Long.MAX_VALUE
    }

    internal fun terminate(nowNanos: Long, reason: BindingReleaseReason, requireNeutral: Boolean) {
        if (stableActive && acceptedActivation) {
            listener.onRelease(elapsedNanos(nowNanos, pressedAtNanos), reason)
        }
        source.reset()
        hasCandidate = false
        candidate = false
        stableActive = false
        acceptedActivation = false
        holdFired = false
        nextRepeatNanos = Long.MAX_VALUE
        lastAcceptedPressNanos = Long.MIN_VALUE
        waitingForNeutral = requireNeutral
    }
}

internal fun elapsedNanos(nowNanos: Long, sinceNanos: Long): Long {
    if (nowNanos < sinceNanos) return 0L
    val difference = nowNanos - sinceNanos
    return if (difference < 0L) Long.MAX_VALUE else difference
}

private fun saturatingAdd(value: Long, increment: Long): Long =
    if (increment > 0L && value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

private fun nextRepeatDeadline(previous: Long, now: Long, interval: Long): Long {
    if (previous == Long.MAX_VALUE) return Long.MAX_VALUE
    val elapsed = elapsedNanos(now, previous)
    val completedIntervals = elapsed / interval
    if (completedIntervals == Long.MAX_VALUE) return Long.MAX_VALUE
    val intervalsToSkip = completedIntervals + 1L
    if (intervalsToSkip > Long.MAX_VALUE / interval) return Long.MAX_VALUE
    return saturatingAdd(previous, intervalsToSkip * interval)
}
