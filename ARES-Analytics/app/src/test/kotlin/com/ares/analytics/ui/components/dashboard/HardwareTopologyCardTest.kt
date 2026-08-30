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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HardwareTopologyCardTest {
    private lateinit var tempDb: File
    private lateinit var databaseService: DatabaseService
    private lateinit var nt4ClientService: Nt4ClientService

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

    @BeforeTest
    fun setUp() {
        tempDb = File.createTempFile("topology_test_db", ".db").apply { deleteOnExit() }
        databaseService = DatabaseService(tempDb.absolutePath)
        nt4ClientService = Nt4ClientService(databaseService)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { nt4ClientService.stop() }
        tempDb.delete()
    }

    @Test
    fun testNt4ClientServiceTopologyFlow() = runBlocking {
        nt4ClientService.setLatestTopology(sampleTopology)
        val current = nt4ClientService.latestTopology.first()

        assertNotNull(current)
        assertEquals("ares-robot-23247", current.robotId)
        assertEquals(7, current.nodes.size)
    }

    @Test
    fun testCategoryFiltering() {
        val nodes = sampleTopology.nodes

        val controllers = nodes.filter {
            it.type in listOf(
                TopologyNodeType.ROBORIO, TopologyNodeType.CONTROL_HUB,
                TopologyNodeType.EXPANSION_HUB, TopologyNodeType.CANIVORE,
                TopologyNodeType.SRS_HUB, TopologyNodeType.POWER_DISTRIBUTION
            )
        }
        assertEquals(2, controllers.size)

        val motors = nodes.filter {
            it.type in listOf(TopologyNodeType.MOTOR, TopologyNodeType.CAN_MOTOR_CONTROLLER)
        }
        assertEquals(2, motors.size)

        val servos = nodes.filter { it.type == TopologyNodeType.SERVO }
        assertEquals(1, servos.size)

        val sensors = nodes.filter {
            it.type in listOf(
                TopologyNodeType.COLOR_SENSOR, TopologyNodeType.DISTANCE_SENSOR,
                TopologyNodeType.BEAM_BREAK, TopologyNodeType.ANALOG_SENSOR,
                TopologyNodeType.CAN_CODER
            )
        }
        assertEquals(1, sensors.size)

        val vision = nodes.filter {
            it.type in listOf(
                TopologyNodeType.CAMERA, TopologyNodeType.ODOMETRY_COMPUTER,
                TopologyNodeType.IMU, TopologyNodeType.PIGEON_IMU
            )
        }
        assertEquals(1, vision.size)
    }

    @Test
    fun testSearchFiltering() {
        val nodes = sampleTopology.nodes

        // Search by name
        val nameSearch = nodes.filter { it.displayName.contains("Mecanum", ignoreCase = true) }
        assertEquals(2, nameSearch.size)

        // Search by port
        val portSearch = nodes.filter { it.port?.toString() == "0" }
        assertEquals(2, portSearch.size) // motor_fl (port 0) and wrist_servo (port 0)

        // Search by ID
        val idSearch = nodes.filter { it.id.contains("expansion", ignoreCase = true) }
        assertEquals(1, idSearch.size)
    }

    @Test
    fun testHierarchyTreeResolution() {
        val nodes = sampleTopology.nodes
        val rootControllers = nodes.filter { it.parentId == null }
        assertEquals(1, rootControllers.size)
        assertEquals("control_hub", rootControllers.first().id)

        val childrenByParent = nodes.filter { it.parentId != null }.groupBy { it.parentId!! }
        val controlHubChildren = childrenByParent["control_hub"].orEmpty()
        assertEquals(5, controlHubChildren.size)

        val expansionHubChildren = childrenByParent["expansion_hub"].orEmpty()
        assertEquals(1, expansionHubChildren.size)
        assertEquals("wrist_servo", expansionHubChildren.first().id)
    }

    private val prettyJson = Json { prettyPrint = true }

    @Test
    fun testTopologySerializationAndExport() {
        val jsonStr = prettyJson.encodeToString(sampleTopology)
        assertTrue(jsonStr.contains("ares-robot-23247"))
        assertTrue(jsonStr.contains("REV Control Hub"))

        val decoded = prettyJson.decodeFromString<HardwareTopology>(jsonStr)
        assertEquals(sampleTopology.robotId, decoded.robotId)
        assertEquals(sampleTopology.nodes.size, decoded.nodes.size)
    }
}
