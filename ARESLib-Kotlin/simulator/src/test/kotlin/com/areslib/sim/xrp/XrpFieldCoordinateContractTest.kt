package com.areslib.sim.xrp

import com.areslib.sim.physics.SimPhysicsWorld
import com.areslib.state.*
import com.areslib.util.RobotClock
import com.google.gson.JsonParser
import java.io.File
import kotlin.test.*

class XrpFieldCoordinateContractTest {
    private val previousConfig = RobotFieldManager.activeConfig
    @AfterTest fun restore() {
        RobotFieldManager.setActiveConfig(previousConfig)
        RobotClock.useSystemTime()
    }

    @Test fun `XRP walls match the canonical project center origin`() {
        val project = JsonParser.parseString(root().resolve("ARES-XRP-Starter/.ares/project.json").readText()).asJsonObject
        assertEquals("CENTER_ORIGIN_CCW", project["coordinateConvention"].asString)
        val world = SimPhysicsWorld()
        world.loadFieldElements(RobotFieldConfig(fieldType = FieldType.XRP, widthMeters = 2.54, heightMeters = 1.4224))
        assertEquals(-1.32, world.fieldWalls[2].transform.translationX, 1e-12)
        assertEquals(1.32, world.fieldWalls[3].transform.translationX, 1e-12)
        assertEquals(-0.7612, world.fieldWalls[1].transform.translationY, 1e-12)
        assertEquals(0.7612, world.fieldWalls[0].transform.translationY, 1e-12)
    }

    @Test fun `Orbit preset positions agree with centered authoring coordinates`() {
        val field = preset()
        val center = field.obstacles.single { it.id == "earth_pedestal" }
        assertEquals(0.0, center.x, 1e-12)
        assertEquals(0.0, center.y, 1e-12)
        val tags = field.apriltags.associateBy { it.id }
        assertEquals(-1.22, tags.getValue(1).x, 1e-12)
        assertEquals(1.22, tags.getValue(2).x, 1e-12)
        assertTrue(field.apriltags.all { it.y == 0.0 })
        assertEquals(0.0, tags.getValue(1).yaw)
        assertEquals(180.0, tags.getValue(2).yaw)
        assertEquals(-0.92, field.fieldWaypoints.single { it.id == "waypoint_red_launch" }.x, 1e-12)
        for (element in field.elements) {
            assertTrue(element.x in -1.27..1.27 && element.y in -0.7112..0.7112)
        }
    }

    @Test fun `XRP fmap positions do not acquire a corner offset`() {
        val field = RobotFieldConfig(fieldType = FieldType.XRP, apriltags = listOf(
            RobotFieldAprilTag(id = 1, family = "36h11", sizeMeters = 0.1, x = -1.22, y = 0.0),
        ))
        val encoded = AprilTagMapCodec.encodeLimelightFmap(field)
        val raw = AprilTagMapCodec.decodeLimelightFmap(encoded).tags.single()
        assertEquals(-1.22, raw.x, 1e-12)
        assertEquals(0.0, raw.y, 1e-12)
        val centeredInput = """{"fiducials":[{"id":1,"transform":[1,0,0,-1.22,0,1,0,0,0,0,1,0,0,0,0,1]}]}"""
        assertEquals(-1.22, AprilTagMapCodec.decodeLimelightFmapForField(centeredInput, field).tags.single().x, 1e-12)
    }

    @Test fun `default XRP startup uses XRP geometry and centered spawn`() {
        val engine = XrpSimulationEngine()
        assertEquals(FieldType.XRP, engine.physicsWorld.loadedFieldConfig?.fieldType)
        assertEquals(-0.92, engine.otosX, 1e-12)
        assertEquals(0.0, engine.otosY, 1e-12)
    }

    @Test fun `pose reset uses the current centered field dimensions`() {
        RobotClock.useMockTime(1000)
        val engine = XrpSimulationEngine(activeConfig = RobotFieldConfig(fieldType = FieldType.XRP))
        engine.physicsWorld.loadFieldElements(RobotFieldConfig(fieldType = FieldType.XRP, widthMeters = 4.0, heightMeters = 2.0))
        engine.processDriveFrame(doubleArrayOf(2.0, 7101.0, 0.0, 0.0, 0.0, 0.0, 0.0, 8.0))
        engine.processDriveFrame(doubleArrayOf(2.0, 7101.0, 1.0, 20.0, 0.0, 0.0, 0.0, 520.0))
        assertEquals(-1.65, engine.otosX, 1e-12)
        assertEquals(0.0, engine.otosY, 1e-12)
        assertEquals(0.0, engine.leftPower)
        assertEquals(0.0, engine.rightPower)
    }

    private fun preset() = RobotFieldDocument.decode(root().resolve(
        "ARES-Analytics/app/src/main/resources/field-presets/xrp/orbit_odyssey_2026.json").readText())
    private fun root(): File = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { it.resolve("ARES-XRP-Starter/.ares/project.json").isFile }
}
