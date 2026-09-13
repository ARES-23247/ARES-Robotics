package com.ares.analytics.ui.components.dashboard

import com.areslib.telemetry.schema.HardwareTopology
import com.areslib.telemetry.schema.TopologyNode
import com.areslib.telemetry.schema.TopologyNodeType
import kotlin.test.*

class HardwareTopologyPresentationAuditTest {
    private fun node(id: String, parent: String? = null, type: TopologyNodeType = TopologyNodeType.MOTOR) =
        TopologyNode(id, type, id, parentId = parent)

    @Test fun nestedAndOutOfOrderParentsAppearOnceAtTheirActualDepth() {
        val nodes = listOf(node("leaf", "middle"), node("root"), node("middle", "root", TopologyNodeType.CAN_CODER),
            node("sibling", "root"), node("other"))
        val rows = topologyDisplayRows(nodes)
        assertEquals(listOf("root", "middle", "leaf", "sibling", "other"), rows.map { it.node.id })
        assertEquals(listOf(0, 1, 2, 1, 0), rows.map { it.depth })
        assertEquals(nodes.toSet(), rows.map { it.node }.toSet())
    }

    @Test fun missingParentsSelfParentsAndCyclesStayVisibleWithUniqueKeys() {
        val nodes = listOf(node("orphan", "missing"), node("child", "orphan"), node("self", "self"),
            node("a", "b"), node("b", "a"), node("tail", "b"))
        val rows = topologyDisplayRows(nodes)
        assertEquals(listOf("orphan", "child", "self", "a", "b", "tail"), rows.map { it.node.id })
        assertEquals(listOf(0, 1, 0, 0, 1, 2), rows.map { it.depth })
        assertEquals(nodes.size, rows.map { it.node.id }.toSet().size)
    }

    @Test fun oldCachedDuplicateIdentitiesKeepTheFirstDefinition() {
        val first = node("duplicate").copy(displayName = "first")
        val rows = topologyDisplayRows(listOf(first, first.copy(displayName = "second"), node("child", "duplicate")))
        assertEquals(listOf("first", "child"), rows.map { it.node.displayName })
        assertEquals(listOf(first), topologyDisplayRows(listOf(first, first.copy(displayName = "second")), "first")
            .map { it.node })
        assertTrue(topologyDisplayRows(listOf(first, first.copy(displayName = "second")), "second").isEmpty())
    }

    @Test fun deepDiscoveryUsesBoundedStackAndOneInputTraversal() {
        val nodeCount = 20_000
        var reads = 0
        val nodes = object : AbstractList<TopologyNode>() {
            override val size: Int = nodeCount
            override fun get(index: Int): TopologyNode {
                reads++
                return node(index.toString(), if (index == 0) null else (index - 1).toString())
            }
        }
        val rows = topologyDisplayRows(nodes)
        assertEquals(nodeCount, rows.size)
        assertEquals(nodeCount, reads)
        assertEquals(0, rows.first().depth)
        assertEquals(nodeCount - 1, rows.last().depth)
        assertEquals((nodeCount - 1).toString(), rows.last().node.id)
    }

    @Test fun everyDeclaredCategoryHasOneIndependentExpectedClassification() {
        val expected = mapOf(
            TopologyCategoryFilter.CONTROLLERS to listOf("ROBORIO", "CONTROL_HUB", "EXPANSION_HUB", "CANIVORE", "SRS_HUB", "POWER_DISTRIBUTION"),
            TopologyCategoryFilter.MOTORS to listOf("MOTOR", "CAN_MOTOR_CONTROLLER"),
            TopologyCategoryFilter.SERVOS to listOf("SERVO"),
            TopologyCategoryFilter.SENSORS to listOf("COLOR_SENSOR", "DISTANCE_SENSOR", "BEAM_BREAK", "ANALOG_SENSOR", "CAN_CODER"),
            TopologyCategoryFilter.VISION to listOf("CAMERA", "ODOMETRY_COMPUTER", "IMU", "PIGEON_IMU"),
        )
        assertEquals(TopologyNodeType.entries.map { it.name }.toSet(), expected.values.flatten().toSet())
        expected.forEach { (category, names) -> names.forEach { assertEquals(category, topologyCategory(TopologyNodeType.valueOf(it))) } }
    }

    @Test fun combinedSearchAndCategoryRemainFlatAndPreserveSourceOrder() {
        val nodes = listOf(node("root", type = TopologyNodeType.CONTROL_HUB),
            node("motor-one", "root").copy(displayName = "Shoulder", canId = 12),
            node("sensor", "root", TopologyNodeType.CAN_CODER).copy(canId = 12),
            node("motor-two", "root").copy(port = 12))
        assertEquals(listOf("motor-one", "motor-two"),
            topologyDisplayRows(nodes, " 12 ", TopologyCategoryFilter.MOTORS).map { it.node.id })
        assertTrue(topologyDisplayRows(nodes, "12").all { it.depth == 0 })
        assertEquals(listOf("motor-one"), filterTopologyNodes(nodes, " SHOULDER ", TopologyCategoryFilter.ALL).map { it.id })
        assertEquals(listOf("motor-two"), filterTopologyNodes(nodes, "MOTOR-TWO", TopologyCategoryFilter.ALL).map { it.id })
        assertTrue(topologyDisplayRows(emptyList()).isEmpty())
        assertTrue(topologyDisplayRows(nodes, "missing").isEmpty())
    }

    @Test fun markdownExportEscapesCellsWithoutAddingRowsOrMarkup() {
        val topology = HardwareTopology("Robot|One", listOf(node("motor").copy(
            displayName = "A|B\r\n<angle>&_*[]", canBus = "bus\\name|other", canId = 4, connectionType = "USB\nsecond")))
        val lines = topologyMarkdown(topology).lines().filter { it.isNotEmpty() }
        assertEquals(4, lines.size)
        assertEquals("# Hardware Map: Robot\\|One", lines[0])
        assertTrue(lines[3].contains("A\\|B<br>&lt;angle&gt;&amp;\\_\\*\\[\\]"))
        assertTrue(lines[3].contains("bus\\\\name\\|other"))
        assertTrue(lines[3].contains("CAN 4"))
        assertTrue(lines[3].contains("USB<br>second"))
    }

    @Test fun markdownExportPreservesPortZeroAndAbsentFields() {
        val table = topologyMarkdown(HardwareTopology("robot", listOf(node("zero").copy(port = 0), node("empty"))))
        assertTrue(table.contains("| zero | MOTOR | Port 0 | — | Internal |"))
        assertTrue(table.contains("| empty | MOTOR | — | — | Internal |"))
    }
}
