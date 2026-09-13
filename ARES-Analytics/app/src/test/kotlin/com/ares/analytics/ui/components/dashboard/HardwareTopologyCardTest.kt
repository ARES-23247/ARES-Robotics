package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.areslib.telemetry.schema.HardwareTopology
import com.areslib.telemetry.schema.TopologyNode
import com.areslib.telemetry.schema.TopologyNodeType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HardwareTopologyCardTest {
    private val sampleTopology = HardwareTopology(
        robotId = "ares-robot-23247",
        nodes = listOf(
            TopologyNode(
                id = "control_hub",
                type = TopologyNodeType.CONTROL_HUB,
                displayName = "REV Control Hub",
                parentId = null,
                canBus = "can0"
            ),
            TopologyNode(
                id = "expansion_hub",
                type = TopologyNodeType.EXPANSION_HUB,
                displayName = "REV Expansion Hub",
                parentId = "control_hub",
                canBus = "can0"
            ),
            TopologyNode(
                id = "motor_fl",
                type = TopologyNodeType.MOTOR,
                displayName = "Front Left Mecanum",
                parentId = "control_hub",
                port = 0,
                connectionType = "MOTOR_PORT"
            ),
            TopologyNode(
                id = "motor_fr",
                type = TopologyNodeType.MOTOR,
                displayName = "Front Right Mecanum",
                parentId = "control_hub",
                port = 1,
                connectionType = "MOTOR_PORT"
            ),
            TopologyNode(
                id = "wrist_servo",
                type = TopologyNodeType.SERVO,
                displayName = "Intake Wrist Servo",
                parentId = "expansion_hub",
                port = 0,
                connectionType = "SERVO_PORT"
            ),
            TopologyNode(
                id = "color_sensor",
                type = TopologyNodeType.COLOR_SENSOR,
                displayName = "Color Sensor V3",
                parentId = "control_hub",
                port = 1,
                connectionType = "I2C"
            ),
            TopologyNode(
                id = "limelight_camera",
                type = TopologyNodeType.CAMERA,
                displayName = "Limelight 3A",
                parentId = "control_hub",
                connectionType = "USB"
            )
        )
    )

    @Test
    fun testNt4ClientServiceTopologyFlow() = runBlocking {
        val tempDb = File.createTempFile("topology_test_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val nt4ClientService = Nt4ClientService(databaseService)
        try {
            nt4ClientService.setLatestTopology(sampleTopology)
            val current = nt4ClientService.latestTopology.first()
            assertNotNull(current)
            assertEquals("ares-robot-23247", current.robotId)
            assertEquals(7, current.nodes.size)
        } finally {
            try { nt4ClientService.stop() } finally { databaseService.closeAndJoin() }
            assertTrue(tempDb.delete(), "Closed topology test database must be removable")
        }
    }

    @Test
    fun testCategoryFiltering() {
        val expected = mapOf(TopologyCategoryFilter.ALL to 7, TopologyCategoryFilter.CONTROLLERS to 2,
            TopologyCategoryFilter.MOTORS to 2, TopologyCategoryFilter.SERVOS to 1,
            TopologyCategoryFilter.SENSORS to 1, TopologyCategoryFilter.VISION to 1)
        expected.forEach { (category, count) ->
            assertEquals(count, filterTopologyNodes(sampleTopology.nodes, "", category).size)
        }
    }

    @Test
    fun testSearchFiltering() {
        fun matches(query: String) = filterTopologyNodes(sampleTopology.nodes, query, TopologyCategoryFilter.ALL)
        assertEquals(listOf("motor_fl", "motor_fr"), matches("Mecanum").map { it.id })
        assertEquals(listOf("motor_fl", "wrist_servo"), matches("0").map { it.id })
        assertEquals(listOf("expansion_hub"), matches("expansion").map { it.id })
    }

    @Test
    fun testHierarchyTreeResolution() {
        val rows = topologyDisplayRows(sampleTopology.nodes)
        assertEquals(listOf("control_hub", "expansion_hub", "wrist_servo", "motor_fl", "motor_fr", "color_sensor", "limelight_camera"),
            rows.map { it.node.id })
        assertEquals(listOf(0, 1, 2, 1, 1, 1, 1), rows.map { it.depth })
    }

    private val prettyJson = Json { prettyPrint = true }

    @Test
    fun testTopologySerializationAndExport() {
        val jsonStr = prettyJson.encodeToString(sampleTopology)
        assertTrue(jsonStr.contains("ares-robot-23247"))
        assertTrue(jsonStr.contains("REV Control Hub"))

        assertTrue(topologyMarkdown(sampleTopology).contains("Front Left Mecanum"))
        val decoded = prettyJson.decodeFromString<HardwareTopology>(jsonStr)
        assertEquals(sampleTopology.robotId, decoded.robotId)
        assertEquals(sampleTopology.nodes.size, decoded.nodes.size)
    }
}
