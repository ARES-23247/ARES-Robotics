package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Coalesces raw telemetry into UI-rate latest-value updates without changing the recording path.
 *
 * Durable recording uses a separate queue. [TelemetryStore.updates] is a bounded, best-effort
 * source-rate bus; Compose widgets consume this further-coalesced fan-out, so same-topic bursts cannot queue
 * thousands of redundant main-thread callbacks ahead of keyboard events and rendering.
 */
internal class UiTelemetryFanout(
    scope: CoroutineScope,
    private val frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS,
) {
    private class EpochBucket(val epoch: Long) {
        val byTopic = ConcurrentHashMap<String, TelemetryFrame>(128)
        val dirty = AtomicBoolean(false)
    }

    private val resetLock = Any()
    private val activeBucket = AtomicReference(EpochBucket(0L))
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
            val batch = ArrayList<TelemetryFrame>(128)
            for (ignored in wakeups) {
                delay(frameIntervalMs)
                val bucket = activeBucket.get()
                bucket.dirty.set(false)
                batch.clear()
                for ((topic, frame) in bucket.byTopic) {
                    if (bucket.byTopic.remove(topic, frame)) {
                        batch.add(frame)
                    }
                }
                if (batch.isNotEmpty() && activeBucket.get() === bucket) {
                    synchronized(resetLock) {
                        if (activeBucket.get() === bucket) {
                            for (i in 0 until batch.size) {
                                mutableUpdates.tryEmit(batch[i])
                            }
                        }
                    }
                }
                if (activeBucket.get() === bucket &&
                    bucket.byTopic.isNotEmpty() &&
                    bucket.dirty.compareAndSet(false, true)
                ) {
                    wakeups.trySend(Unit)
                }
            }
        }
    }

    /** Lock-free, zero-wrapper latest-value handoff from the raw ingestion path. */
    fun offer(frame: TelemetryFrame, frameTargetEpoch: Long = activeBucket.get().epoch) {
        val bucket = activeBucket.get()
        if (bucket.epoch != frameTargetEpoch) return
        bucket.byTopic[frame.key] = frame
        if (bucket.dirty.compareAndSet(false, true)) {
            wakeups.trySend(Unit)
        }
    }

    /** Drops both pending and replayed UI values when the selected live target changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun reset(nextTargetEpoch: Long) {
        synchronized(resetLock) {
            val previous = activeBucket.getAndSet(EpochBucket(nextTargetEpoch))
            previous.byTopic.clear()
            previous.dirty.set(false)
            mutableUpdates.resetReplayCache()
        }
    }

    private companion object {
        const val DEFAULT_FRAME_INTERVAL_MS = 16L
    }
}
