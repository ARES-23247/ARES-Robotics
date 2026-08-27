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
    private var lastUpdateNanos = Long.MIN_VALUE

    /** Updates using the configured deterministic/system clock. */
    fun update(frame: InputFrame) {
        update(frame, nanoTime())
    }

    /** Updates at an explicit monotonic timestamp, primarily for simulation, replay, and tests. */
    fun update(frame: InputFrame, nowNanos: Long) {
        if (lastUpdateNanos != Long.MIN_VALUE && nowNanos < lastUpdateNanos) {
            terminate(lastUpdateNanos, BindingReleaseReason.TIME_REWIND, requireNeutral = true)
            wasConnected = false
        }
        lastUpdateNanos = nowNanos

        if (!frame.isConnected) {
            if (wasConnected) {
                terminate(nowNanos, BindingReleaseReason.DISCONNECTED, requireNeutral = true)
                wasConnected = false
            }
            return
        }

        var index = 0
        while (index < digitalArray.size) {
            digitalArray[index].update(frame, nowNanos)
            index++
        }
        index = 0
        while (index < analogArray.size) {
            analogArray[index].update(frame, nowNanos)
            index++
        }
        wasConnected = true
    }

    /**
     * Immediately releases active inputs, bypassing debounce. Use on disable and OpMode stop.
     * Inputs must return to neutral before callbacks can activate again.
     */
    fun cancel() {
        val now = nanoTime()
        terminate(now, BindingReleaseReason.CANCELLED, requireNeutral = true)
        wasConnected = false
        lastUpdateNanos = now
    }

    private fun terminate(nowNanos: Long, reason: BindingReleaseReason, requireNeutral: Boolean) {
        var index = 0
        while (index < digitalArray.size) {
            digitalArray[index].terminate(nowNanos, reason, requireNeutral)
            index++
        }
        index = 0
        while (index < analogArray.size) {
            analogArray[index].terminate(requireNeutral)
            index++
        }
    }
}
