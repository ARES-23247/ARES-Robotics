package com.areslib.sim

import com.areslib.util.RobotClock
import java.util.concurrent.locks.LockSupport

/** Separate host scheduling from the fixed robot/physics timeline; neither callback boxes longs. */
internal fun interface SimHostClock { fun nanoTime(): Long }
internal fun interface SimHostWait { fun await(nanos: Long) }

/**
 * Interruptible, non-spinning absolute-deadline pacing. Work and ordinary wakeup jitter consume
 * the current period instead of being added to it. A stall of at least a whole extra period
 * rebases the host deadline, preventing an unbounded burst of stale control/physics frames.
 * No robot step is dropped, enlarged, or advanced here. The runner owns that fixed step.
 */
internal class SimFramePacer(
    private val clock: SimHostClock = SimHostClock(RobotClock::hostNanoTime),
    private val wait: SimHostWait = SimHostWait(LockSupport::parkNanos),
    private val periodNanos: Long = DesktopSimLauncher.SIM_TIMESTEP_MS * 1_000_000L,
) {
    private var deadline = clock.nanoTime() + periodNanos
    var lastLatenessNanos: Long = 0L
        private set
    var rebasedPeriods: Long = 0L
        private set

    init { require(periodNanos > 0L) }

    fun reset() {
        deadline = clock.nanoTime() + periodNanos
        lastLatenessNanos = 0L
        rebasedPeriods = 0L
    }

    @Throws(InterruptedException::class)
    fun awaitNextFrame() {
        var now: Long
        while (true) {
            if (Thread.interrupted()) throw InterruptedException("Simulator pacing interrupted")
            now = clock.nanoTime()
            val remaining = deadline - now
            if (remaining <= 0L) break
            wait.await(remaining)
        }
        lastLatenessNanos = now - deadline
        if (lastLatenessNanos >= periodNanos) {
            rebasedPeriods += lastLatenessNanos / periodNanos
            deadline = now + periodNanos
        } else {
            deadline += periodNanos
        }
    }
}
