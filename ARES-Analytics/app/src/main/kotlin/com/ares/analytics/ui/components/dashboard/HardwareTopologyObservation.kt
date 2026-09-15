package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.*
import com.ares.analytics.service.MonotonicClock
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.SystemMonotonicClock
import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.telemetry.schema.TopologyNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.IdentityHashMap
import java.util.Locale

internal data class TopologyMotorReading(val currentAmps: Double?, val velocity: Double?)

internal class TopologyTelemetryTracker(nodes: List<TopologyNode>) {
    private data class Binding(val id: String, val current: IntArray, val velocity: IntArray)
    private val keyIndices = LinkedHashMap<String, Int>()
    private val bindings = nodes.distinctBy { it.id }
        .filter { topologyCategory(it.type) == TopologyCategoryFilter.MOTORS }
        .map { node ->
            val id = node.id.trim('/')
            val canonicalPrefix = when {
                id.startsWith("Hardware/Motors/") -> id
                id.startsWith("Motors/") -> "Hardware/$id"
                else -> "Hardware/Motors/$id"
            }
            val prefixes = listOf(canonicalPrefix, "Hardware/Motors/" + node.displayName).distinct()
            fun indices(suffix: String): IntArray = prefixes.map { prefix ->
                keyIndices.getOrPut("$prefix/$suffix") { keyIndices.size }
            }.toIntArray()
            Binding(node.id, indices("CurrentAmps"), indices("Velocity"))
        }
    private val frames = arrayOfNulls<TelemetryFrame>(keyIndices.size)
    private val receivedAt = LongArray(keyIndices.size)
    private var epoch: Long? = null

    private fun selectEpoch(next: Long) {
        if (epoch != next) {
            frames.fill(null)
            epoch = next
        }
    }

    @Synchronized fun accept(frame: TelemetryFrame, nowNanos: Long, targetEpoch: Long) {
        val index = keyIndices[frame.key.trimStart('/')] ?: return
        selectEpoch(targetEpoch)
        frames[index] = frame
        receivedAt[index] = nowNanos
    }

    @Synchronized fun snapshot(nowNanos: Long, targetEpoch: Long): Map<String, TopologyMotorReading> {
        selectEpoch(targetEpoch)
        fun read(indices: IntArray): Double? {
            for (index in indices) {
                val frame = frames[index] ?: continue
                if (nowNanos - receivedAt[index] !in 0L..STALE_AFTER_NANOS) continue
                // An explicit invalid canonical sample must not be masked by a display-name alias.
                return frame.value.takeIf { frame.stringValue == null && it.isFinite() }
            }
            return null
        }
        return buildMap {
            for (binding in bindings) {
                val current = read(binding.current)
                val velocity = read(binding.velocity)
                if (current != null || velocity != null) put(binding.id, TopologyMotorReading(current, velocity))
            }
        }
    }

    companion object { const val STALE_AFTER_NANOS = 2_000_000_000L }
}

/** One collector and a 10 Hz sampler per card, with storage bounded by the displayed topology. */
internal fun observeTopologyTelemetry(
    nodes: List<TopologyNode>,
    frames: SharedFlow<TelemetryFrame>,
    targetEpoch: () -> Long,
    isCurrent: (TelemetryFrame) -> Boolean,
    clock: MonotonicClock = SystemMonotonicClock,
): Flow<Map<String, TopologyMotorReading>> = flow {
    val tracker = TopologyTelemetryTracker(nodes)
    // A retained publication does not carry a trustworthy desktop receipt time.
    val retained = IdentityHashMap<TelemetryFrame, Boolean>()
    frames.replayCache.forEach { retained[it] = true }
    coroutineScope {
        launch(start = CoroutineStart.UNDISPATCHED) {
            frames.collect { frame ->
                val epoch = targetEpoch()
                if (!retained.containsKey(frame) && isCurrent(frame) && targetEpoch() == epoch) {
                    tracker.accept(frame, clock.nowNanos(), epoch)
                }
            }
        }
        while (isActive) {
            emit(tracker.snapshot(clock.nowNanos(), targetEpoch()))
            delay(100)
        }
    }
}

@Composable
internal fun rememberTopologyReadings(
    service: Nt4ClientService,
    nodes: List<TopologyNode>,
    enabled: Boolean,
    clock: MonotonicClock = SystemMonotonicClock,
): Map<String, TopologyMotorReading> = key(service, nodes, enabled, clock) {
    if (!enabled || nodes.none { topologyCategory(it.type) == TopologyCategoryFilter.MOTORS }) emptyMap()
    else {
        val observations = remember(service, nodes, clock) {
            observeTopologyTelemetry(nodes, service.uiTelemetryFlow,
                service.telemetryStore::currentTargetEpoch, service.telemetryStore::isCurrentNotifiedFrame, clock)
        }
        observations.collectAsState(emptyMap()).value
    }
}

internal fun topologyCurrentText(current: Double): String = String.format(Locale.ROOT, "%.2f A", current)

// Generic motor telemetry is encoder-native; the wire field does not establish radians per second.
internal fun topologyVelocityText(velocity: Double): String =
    String.format(Locale.ROOT, "%.1f encoder units/s", velocity)
