package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.*
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.MonotonicClock
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.SystemMonotonicClock
import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.telemetry.schema.HardwareTopology
import com.areslib.telemetry.schema.TopologyNode
import com.areslib.telemetry.schema.TopologyNodeType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.IdentityHashMap
import java.util.Locale

enum class TopologyCategoryFilter(val displayName: String) {
    ALL("All"), CONTROLLERS("Controllers"), MOTORS("Motors"), SERVOS("Servos"),
    SENSORS("Sensors"), VISION("Vision & IMU")
}

internal data class TopologyDisplayRow(val node: TopologyNode, val depth: Int)

internal fun topologyCategory(type: TopologyNodeType): TopologyCategoryFilter = when (type) {
    TopologyNodeType.ROBORIO, TopologyNodeType.CONTROL_HUB, TopologyNodeType.EXPANSION_HUB,
    TopologyNodeType.CANIVORE, TopologyNodeType.SRS_HUB, TopologyNodeType.POWER_DISTRIBUTION ->
        TopologyCategoryFilter.CONTROLLERS
    TopologyNodeType.MOTOR, TopologyNodeType.CAN_MOTOR_CONTROLLER -> TopologyCategoryFilter.MOTORS
    TopologyNodeType.SERVO -> TopologyCategoryFilter.SERVOS
    TopologyNodeType.COLOR_SENSOR, TopologyNodeType.DISTANCE_SENSOR, TopologyNodeType.BEAM_BREAK,
    TopologyNodeType.ANALOG_SENSOR, TopologyNodeType.CAN_CODER -> TopologyCategoryFilter.SENSORS
    TopologyNodeType.CAMERA, TopologyNodeType.ODOMETRY_COMPUTER, TopologyNodeType.IMU,
    TopologyNodeType.PIGEON_IMU -> TopologyCategoryFilter.VISION
}

internal fun filterTopologyNodes(
    nodes: List<TopologyNode>,
    search: String,
    category: TopologyCategoryFilter,
): List<TopologyNode> {
    val query = search.trim()
    return nodes.filter { node ->
        (category == TopologyCategoryFilter.ALL || topologyCategory(node.type) == category) &&
            (query.isEmpty() || node.displayName.contains(query, ignoreCase = true) ||
                node.id.contains(query, ignoreCase = true) ||
                node.canId?.toString()?.contains(query) == true ||
                node.port?.toString()?.contains(query) == true)
    }
}

/**
 * Stable preorder in O(nodes) expected time. Missing parents become roots; disconnected cycles
 * are opened at their first input node. Iteration avoids call-stack limits on deep discovery.
 * Old cached payloads can predate codec validation: the first occurrence owns a duplicate ID.
 */
internal fun topologyDisplayRows(
    nodes: List<TopologyNode>,
    search: String = "",
    category: TopologyCategoryFilter = TopologyCategoryFilter.ALL,
): List<TopologyDisplayRow> {
    val byId = LinkedHashMap<String, TopologyNode>(nodes.size)
    nodes.forEach { byId.putIfAbsent(it.id, it) }
    if (search.isNotBlank() || category != TopologyCategoryFilter.ALL) {
        return filterTopologyNodes(byId.values.toList(), search, category).map { TopologyDisplayRow(it, 0) }
    }
    val children = HashMap<String, MutableList<TopologyNode>>()
    for (node in byId.values) {
        val parent = node.parentId
        if (parent != null && parent in byId) children.getOrPut(parent) { ArrayList() }.add(node)
    }
    val result = ArrayList<TopologyDisplayRow>(byId.size)
    val visited = HashSet<String>(byId.size)
    val stack = ArrayDeque<TopologyDisplayRow>()
    fun appendBranch(root: TopologyNode) {
        if (root.id in visited) return
        stack.addLast(TopologyDisplayRow(root, 0))
        while (stack.isNotEmpty()) {
            val row = stack.removeLast()
            if (!visited.add(row.node.id)) continue
            result.add(row)
            val descendants = children[row.node.id].orEmpty()
            for (index in descendants.indices.reversed()) {
                val child = descendants[index]
                if (child.id !in visited) stack.addLast(TopologyDisplayRow(child, row.depth + 1))
            }
        }
    }
    byId.values.filter { it.parentId !in byId }.forEach(::appendBranch)
    byId.values.forEach(::appendBranch)
    return result
}

/** Copy user-provided labels as literal table cells rather than Markdown/HTML structure. */
internal fun topologyMarkdown(topology: HardwareTopology): String = buildString {
    appendLine("# Hardware Map: " + markdownCell(topology.robotId))
    appendLine("| Name | Type | Bus / Port | ID | Connection |")
    appendLine("| :--- | :--- | :--- | :--- | :--- |")
    for (node in topology.nodes) {
        val cells = listOf(node.displayName, node.type.name,
            node.canBus ?: node.port?.let { "Port $it" } ?: "—",
            node.canId?.let { "CAN $it" } ?: "—", node.connectionType ?: "Internal")
        appendLine(cells.joinToString(" | ", prefix = "| ", postfix = " | ", transform = ::markdownCell))
    }
}

private fun markdownCell(value: String): String = buildString {
    val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
    for (character in normalized) when (character) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '\n' -> append("<br>")
        '\\', '|', '*', '_', '\u0060', '[', ']', '~' -> append('\\').append(character)
        else -> append(character)
    }
}

internal data class HardwareTopologySelection(
    val topology: HardwareTopology?,
    val cached: Boolean,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** The database stores a latest cached map per robot, not a topology snapshot per historical session. */
@Composable
internal fun rememberHardwareTopologySelection(
    live: HardwareTopology?,
    database: DatabaseService,
    sessionId: String?,
): HardwareTopologySelection {
    val cached = sessionId != null && sessionId != "live-telemetry"
    var selection by remember(database, sessionId) {
        mutableStateOf(HardwareTopologySelection(null, cached, loading = cached))
    }
    LaunchedEffect(database, sessionId) {
        if (cached) {
            try {
                val robotId = database.getSessionSummary(requireNotNull(sessionId))?.robotId?.takeIf { it.isNotBlank() }
                val topology = robotId?.let { database.getTopology(it) }
                if (topology != null && topology.robotId != robotId) {
                    selection = HardwareTopologySelection(null, true, failed = true)
                } else selection = HardwareTopologySelection(topology, true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selection = HardwareTopologySelection(null, true, failed = true)
            }
        }
    }
    return if (cached) selection else HardwareTopologySelection(live, false)
}

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

