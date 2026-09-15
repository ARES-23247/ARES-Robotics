package com.areslib.input

import com.areslib.util.RobotClock

/**
 * Updates a fixed set of controller bindings from reusable [InputFrame] samples.
 *
 * The runtime performs indexed array loops and allocates nothing after construction. It owns no
 * hardware and should run once per robot loop before subsystem outputs are written. Callbacks
 * normally dispatch Redux actions or start routines.
 */
class ControllerBindingRuntime(
    digitalBindings: List<DigitalBinding> = emptyList(),
    analogBindings: List<AnalogBinding> = emptyList(),
    private val nanoTime: () -> Long = RobotClock::nanoTime,
) {
    private val digitalArray = digitalBindings.toTypedArray()
    private val analogArray = analogBindings.toTypedArray()
    private var wasConnected = false
    private var hasUpdate = false
    private var lastUpdateNanos = 0L
    private var generation = 0L
    private var updating = false
    private var terminating = false

    /** Updates using the configured deterministic/system clock. */
    fun update(frame: InputFrame) {
        if (terminating) return
        update(frame, readTime())
    }

    /** Updates at an explicit monotonic timestamp, primarily for simulation, replay, and tests. */
    fun update(frame: InputFrame, nowNanos: Long) {
        if (terminating) return
        check(!updating) { "controller binding update must not be reentrant" }
        updating = true
        try {
            if (hasUpdate && nowNanos < lastUpdateNanos) {
                terminate(lastUpdateNanos, BindingReleaseReason.TIME_REWIND, requireNeutral = true)
            }
            lastUpdateNanos = nowNanos
            hasUpdate = true

            if (!frame.isConnected) {
                if (wasConnected) {
                    terminate(nowNanos, BindingReleaseReason.DISCONNECTED, requireNeutral = true)
                }
                return
            }

            wasConnected = true
            val updateGeneration = generation
            var index = 0
            while (index < digitalArray.size) {
                digitalArray[index].update(frame, nowNanos)
                if (generation != updateGeneration) return
                index++
            }
            index = 0
            while (index < analogArray.size) {
                analogArray[index].update(frame, nowNanos)
                if (generation != updateGeneration) return
                index++
            }
        } catch (failure: Throwable) {
            abort(nowNanos, failure)
        } finally {
            updating = false
        }
    }

    /**
     * Immediately releases active inputs, bypassing debounce. Use on disable and OpMode stop.
     * Inputs must return to neutral before callbacks can activate again. Cleanup attempts every
     * binding even when a callback fails; the first failure is rethrown with later failures suppressed.
     */
    fun cancel() {
        if (terminating) return
        val now = readTime()
        lastUpdateNanos = now
        hasUpdate = true
        terminate(now, BindingReleaseReason.CANCELLED, requireNeutral = true)
    }

    private fun readTime(): Long = try {
        nanoTime()
    } catch (failure: Throwable) {
        abort(lastUpdateNanos, failure)
    }

    private fun abort(nowNanos: Long, failure: Throwable): Nothing {
        try { terminate(nowNanos, BindingReleaseReason.CANCELLED, requireNeutral = true) }
        catch (cleanup: Throwable) { inputFailure(failure, cleanup) }
        throw failure
    }

    private fun terminate(nowNanos: Long, reason: BindingReleaseReason, requireNeutral: Boolean) {
        if (terminating) return
        terminating = true
        generation++
        wasConnected = false
        var failure: Throwable? = null
        try {
            var index = 0
            while (index < digitalArray.size) {
                try { digitalArray[index].terminate(nowNanos, reason, requireNeutral) }
                catch (next: Throwable) { failure = inputFailure(failure, next) }
                index++
            }
            index = 0
            while (index < analogArray.size) {
                try { analogArray[index].terminate(requireNeutral) }
                catch (next: Throwable) { failure = inputFailure(failure, next) }
                index++
            }
        } finally {
            terminating = false
        }
        failure?.let { throw it }
    }
}

internal fun inputFailure(first: Throwable?, next: Throwable): Throwable {
    if (first == null) return next
    if (first !== next) first.addSuppressed(next)
    return first
}
