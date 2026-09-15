package com.areslib.input

/** Why an accepted digital activation ended. */
enum class BindingReleaseReason {
    /** The debounced physical or virtual input became inactive. */
    INPUT_RELEASED,

    /** The controller disconnected or required input feedback became unavailable. */
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
    private var lastAcceptedPressNanos = 0L
    private var hasAcceptedPress = false
    private var holdFired = false
    private var nextRepeatElapsedNanos = -1L
    private var waitingForNeutral = false
    private var generation = 0L
    private var updating = false
    private var terminating = false

    val isActive: Boolean get() = stableActive && acceptedActivation

    fun update(frame: InputFrame, nowNanos: Long) {
        if (terminating) return
        check(!updating) { "digital binding update must not be reentrant" }
        updating = true
        try {
            updateSample(frame, nowNanos)
        } catch (failure: Throwable) {
            try { terminate(nowNanos, BindingReleaseReason.CANCELLED, requireNeutral = true) }
            catch (cleanup: Throwable) { inputFailure(failure, cleanup) }
            throw failure
        } finally {
            updating = false
        }
    }

    private fun updateSample(frame: InputFrame, nowNanos: Long) {
        val updateGeneration = generation
        if (!source.isAvailable(frame)) {
            terminate(nowNanos, BindingReleaseReason.DISCONNECTED, requireNeutral = true)
            return
        }
        if (generation != updateGeneration) return
        if (waitingForNeutral) {
            val neutral = source.isNeutral(frame, nowNanos)
            if (generation != updateGeneration || !neutral) return
            waitingForNeutral = false
            hasCandidate = true
            candidate = false
            candidateSinceNanos = nowNanos
            return
        }

        val sampled = source.sample(frame, nowNanos)
        if (generation != updateGeneration) return
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

        if (generation != updateGeneration || !stableActive || !acceptedActivation) return
        val heldFor = elapsedNanos(nowNanos, pressedAtNanos)
        if (timing.maximumActiveNanos >= 0L && heldFor >= timing.maximumActiveNanos) {
            terminate(nowNanos, BindingReleaseReason.MAXIMUM_DURATION, requireNeutral = true)
            return
        }
        listener.onHeld(heldFor)
        if (generation != updateGeneration) return

        if (!holdFired && timing.holdAfterNanos >= 0L && heldFor >= timing.holdAfterNanos) {
            holdFired = true
            listener.onHold(heldFor)
            if (generation != updateGeneration) return
        }

        if (nextRepeatElapsedNanos >= 0L && heldFor >= nextRepeatElapsedNanos) {
            nextRepeatElapsedNanos = nextRepeatDeadline(nextRepeatElapsedNanos, heldFor, timing.repeatEveryNanos)
            listener.onRepeat(heldFor)
        }
    }

    private fun confirmPress(nowNanos: Long) {
        stableActive = true
        pressedAtNanos = nowNanos
        holdFired = false
        nextRepeatElapsedNanos = timing.repeatAfterNanos

        val outsideCooldown = !hasAcceptedPress ||
            elapsedNanos(nowNanos, lastAcceptedPressNanos) >= timing.cooldownNanos
        acceptedActivation = outsideCooldown
        if (outsideCooldown) {
            hasAcceptedPress = true
            lastAcceptedPressNanos = nowNanos
            listener.onPress()
        }
    }

    private fun confirmRelease(nowNanos: Long) {
        val heldFor = elapsedNanos(nowNanos, pressedAtNanos)
        val release = acceptedActivation
        stableActive = false
        acceptedActivation = false
        holdFired = false
        nextRepeatElapsedNanos = -1L
        if (release) listener.onRelease(heldFor, BindingReleaseReason.INPUT_RELEASED)
    }

    internal fun terminate(nowNanos: Long, reason: BindingReleaseReason, requireNeutral: Boolean) {
        if (terminating) return
        terminating = true
        generation++
        val release = stableActive && acceptedActivation
        val heldFor = elapsedNanos(nowNanos, pressedAtNanos)
        hasCandidate = false
        candidate = false
        stableActive = false
        acceptedActivation = false
        holdFired = false
        nextRepeatElapsedNanos = -1L
        hasAcceptedPress = false
        waitingForNeutral = requireNeutral
        var failure: Throwable? = null
        try {
            try { source.reset() }
            catch (next: Throwable) { failure = inputFailure(failure, next) }
            if (release) {
                try { listener.onRelease(heldFor, reason) }
                catch (next: Throwable) { failure = inputFailure(failure, next) }
            }
        } finally {
            terminating = false
        }
        failure?.let { throw it }
    }
}

internal fun elapsedNanos(nowNanos: Long, sinceNanos: Long): Long {
    if (nowNanos < sinceNanos) return 0L
    val difference = nowNanos - sinceNanos
    return if (difference < 0L) Long.MAX_VALUE else difference
}

/** Returns -1 once there is no representable later repeat duration. */
private fun nextRepeatDeadline(previous: Long, elapsed: Long, interval: Long): Long {
    val remainder = (elapsed - previous) % interval
    val untilNext = interval - remainder
    return if (elapsed > Long.MAX_VALUE - untilNext) -1L else elapsed + untilNext
}
