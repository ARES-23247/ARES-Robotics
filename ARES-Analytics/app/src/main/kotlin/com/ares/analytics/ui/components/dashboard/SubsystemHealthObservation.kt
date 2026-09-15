package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.MonotonicClock
import com.ares.analytics.service.SystemMonotonicClock
import com.ares.analytics.service.hardware.SubsystemHealthAccumulator
import com.ares.analytics.service.hardware.SubsystemHealthSnapshot
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import java.util.IdentityHashMap

/** One owned collector and a 4 Hz sampler; target changes invalidate every cached health signal. */
internal fun observeSubsystemHealth(
    frames: SharedFlow<TelemetryFrame>,
    targetEpoch: () -> Long,
    isCurrent: (TelemetryFrame) -> Boolean,
    clock: MonotonicClock = SystemMonotonicClock,
): Flow<List<SubsystemHealthSnapshot>> = flow {
    // Retained state flags remain useful, but a replayed heartbeat has no trustworthy receipt age.
    // Require a new heartbeat before old flags can establish readiness when a card is reopened.
    val retainedHeartbeats = IdentityHashMap<TelemetryFrame, Boolean>()
    frames.replayCache.filter { it.key.endsWith("/TelemetryHeartbeat") }
        .forEach { retainedHeartbeats[it] = true }
    val accumulator = SubsystemHealthAccumulator()
    coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) {
            frames.collect { frame ->
                val observedEpoch = targetEpoch()
                if (!retainedHeartbeats.containsKey(frame) && isCurrent(frame) && targetEpoch() == observedEpoch) {
                    accumulator.accept(frame, clock.nowNanos(), observedEpoch)
                }
            }
        }
        while (isActive) {
            emit(accumulator.snapshots(clock.nowNanos(), targetEpoch()))
            delay(250L)
        }
    }
}
