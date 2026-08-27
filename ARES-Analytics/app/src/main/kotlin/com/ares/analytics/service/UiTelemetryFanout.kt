package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coalesces raw telemetry into UI-rate latest-value updates without changing lossless ingestion.
 *
 * Robot logging and analytical services continue to consume [TelemetryStore.updates] at source
 * rate. Compose widgets consume this fan-out, so hundreds of same-topic samples cannot queue
 * thousands of redundant main-thread callbacks ahead of keyboard events and rendering.
 */
internal class UiTelemetryFanout(
    scope: CoroutineScope,
    private val frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
) {
    private data class PendingFrame(val targetEpoch: Long, val frame: TelemetryFrame)

    private val resetLock = Any()
    private val pendingByTopic = ConcurrentHashMap<String, PendingFrame>()
    private val targetEpoch = AtomicLong()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val mutableUpdates = MutableSharedFlow<TelemetryFrame>(
        replay = 100,
        extraBufferCapacity = 1_024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<TelemetryFrame> = mutableUpdates.asSharedFlow()

    init {
        require(frameIntervalMs > 0L) { "frameIntervalMs must be positive" }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (ignored in wakeups) {
                delay(frameIntervalMs)
                val activeEpoch = targetEpoch.get()
                val batch = ArrayList<PendingFrame>(pendingByTopic.size)
                for ((topic, pending) in pendingByTopic) {
                    if (pendingByTopic.remove(topic, pending)) batch += pending
                }
                batch.forEach { pending ->
                    synchronized(resetLock) {
                        if (pending.targetEpoch == activeEpoch && targetEpoch.get() == activeEpoch) {
                            mutableUpdates.tryEmit(pending.frame)
                        }
                    }
                }
                if (pendingByTopic.isNotEmpty()) wakeups.trySend(Unit)
            }
        }
    }

    /** Nonblocking latest-value handoff from the raw ingestion path. */
    fun offer(frame: TelemetryFrame, frameTargetEpoch: Long = targetEpoch.get()) {
        synchronized(resetLock) {
            if (frameTargetEpoch != targetEpoch.get()) return
            pendingByTopic[frame.key] = PendingFrame(frameTargetEpoch, frame)
        }
        wakeups.trySend(Unit)
    }

    /** Drops both pending and replayed UI values when the selected live target changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun reset(nextTargetEpoch: Long) {
        synchronized(resetLock) {
            targetEpoch.set(nextTargetEpoch)
            pendingByTopic.clear()
            mutableUpdates.resetReplayCache()
        }
    }

    private companion object {
        const val DEFAULT_FRAME_INTERVAL_MS = 50L
    }
}
