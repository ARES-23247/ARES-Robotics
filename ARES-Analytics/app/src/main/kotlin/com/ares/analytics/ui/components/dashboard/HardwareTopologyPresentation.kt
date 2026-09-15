package com.ares.analytics.ui.components.dashboard

import com.areslib.telemetry.schema.HardwareTopology
import com.areslib.telemetry.schema.TopologyNode
import com.areslib.telemetry.schema.TopologyNodeType

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
