package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.*
import com.ares.analytics.service.MonotonicClock
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.SystemMonotonicClock
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.IdentityHashMap

/** Bounded latest-per-known-topic state; timestamps are desktop receipt times, never robot clocks. */
internal class ControllerHealthTracker {
    private val frames = arrayOfNulls<TelemetryFrame>(HEALTH_KEYS.size)
    private val receivedAt = LongArray(HEALTH_KEYS.size)
    private var epoch: Long? = null
    private var lastReceipt: Long? = null

    private fun selectEpoch(next: Long) {
        if (epoch != next) {
            frames.fill(null)
            lastReceipt = null
            epoch = next
        }
    }

    @Synchronized fun accept(frame: TelemetryFrame, nowNanos: Long, targetEpoch: Long) {
        selectEpoch(targetEpoch)
        lastReceipt = nowNanos
        val index = HEALTH_KEY_INDEX[TelemetryMetricCatalog.normalizeTopic(frame.key)] ?: return
        frames[index] = frame
        receivedAt[index] = nowNanos
    }

    @Synchronized fun snapshot(nowNanos: Long, targetEpoch: Long): ControllerHealthObservation {
        selectEpoch(targetEpoch)
        fun current(key: String): TelemetryFrame? {
            val normalizedKey = TelemetryMetricCatalog.normalizeTopic(key)
            val index = HEALTH_KEY_INDEX[normalizedKey] ?: return null
            val age = nowNanos - receivedAt[index]
            return frames[index]?.takeIf { age in 0..STALE_AFTER_NANOS }
        }
        val age = lastReceipt?.let { nowNanos - it }?.takeIf { it >= 0L }
        return ControllerHealthObservation(
            controllerHealthSnapshot({ current(it)?.value }, { current(it)?.stringValue }),
            age?.div(1_000_000L) ?: -1L,
            ControllerHealthSource.LIVE,
        )
    }

    companion object { const val STALE_AFTER_NANOS = 2_000_000_000L }
}

/** One cancellable collector plus a 10 Hz presentation sampler, independent of unrelated topic volume. */
internal fun observeLiveControllerHealth(
    frames: SharedFlow<TelemetryFrame>,
    targetEpoch: () -> Long,
    isCurrent: (TelemetryFrame) -> Boolean,
    clock: MonotonicClock = SystemMonotonicClock,
): Flow<ControllerHealthObservation> = flow {
    // Retained fan-out values carry no desktop receipt age; wait for a newly observed publication.
    val retained = IdentityHashMap<TelemetryFrame, Boolean>()
    frames.replayCache.forEach { retained[it] = true }
    val tracker = ControllerHealthTracker()
    coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) {
            frames.collect { frame ->
                val observedEpoch = targetEpoch()
                if (!retained.containsKey(frame) && isCurrent(frame) && targetEpoch() == observedEpoch) {
                    // A switch after this check still stores the old epoch, so the next snapshot clears it.
                    tracker.accept(frame, clock.nowNanos(), observedEpoch)
                }
            }
        }
        while (isActive) {
            emit(tracker.snapshot(clock.nowNanos(), targetEpoch()))
            delay(100)
        }
    }
}

internal fun resolveControllerHealth(
    live: ControllerHealthObservation,
    frame: ReplayFrame?,
    replaySelected: Boolean,
    connected: Boolean,
): ControllerHealthObservation = when {
    replaySelected || frame != null -> ControllerHealthObservation(
        frame?.toReplayHealthSnapshot() ?: ControllerHealthSnapshot(),
        if (frame == null) -1L else 0L,
        ControllerHealthSource.REPLAY,
    )
    !connected -> ControllerHealthObservation()
    else -> live
}

@Composable
internal fun rememberControllerHealth(
    service: Nt4ClientService,
    frame: ReplayFrame?,
    replaySelected: Boolean,
    connected: Boolean,
    clock: MonotonicClock = SystemMonotonicClock,
): ControllerHealthObservation {
    val liveEnabled = connected && !replaySelected && frame == null
    val live = key(service, clock, liveEnabled) {
        if (liveEnabled) {
            val observations = remember(service, clock) {
                observeLiveControllerHealth(service.uiTelemetryFlow, service.telemetryStore::currentTargetEpoch,
                    service.telemetryStore::isCurrentNotifiedFrame, clock)
            }
            observations.collectAsState(ControllerHealthObservation(source = ControllerHealthSource.LIVE)).value
        } else ControllerHealthObservation()
    }
    return remember(live, frame, replaySelected, connected) {
        resolveControllerHealth(live, frame, replaySelected, connected)
    }
}

internal typealias ReplayHealthSnapshot = ControllerHealthSnapshot

/** Exact normalized topics only; canonical names precede aliases independently of map order. */
internal fun ReplayFrame.toReplayHealthSnapshot(): ReplayHealthSnapshot {
    fun <T> normalizedHealthValues(input: Map<String, T>): Map<String, T> {
        val result = HashMap<String, T>()
        val sourceKeys = HashMap<String, String>()
        for ((key, value) in input) {
            val normalized = TelemetryMetricCatalog.normalizeTopic(key)
            if (normalized !in HEALTH_KEY_INDEX) continue
            val previous = sourceKeys[normalized]
            if (previous == null || key == normalized || (previous != normalized && key < previous)) {
                result[normalized] = value
                sourceKeys[normalized] = key
            }
        }
        return result
    }
    val numbers = normalizedHealthValues(values)
    val strings = normalizedHealthValues(stringValues)
    return controllerHealthSnapshot(numbers::get, strings::get)
}

/** Loading a selected recording must not expose a retained frame from another recording. */
internal fun selectDashboardReplayFrame(
    frame: ReplayFrame?,
    primarySessionId: String?,
    isReplayActive: Boolean,
): ReplayFrame? {
    if (primarySessionId == null && !isReplayActive) return null
    val expectedSession = primarySessionId ?: Nt4ClientService.LIVE_SESSION_ID
    return frame?.takeIf { expectedSession.isNotBlank() && it.sessionId == expectedSession }
}
