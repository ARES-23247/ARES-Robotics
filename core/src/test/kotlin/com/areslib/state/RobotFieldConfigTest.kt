package com.areslib.state

import com.areslib.math.geometry.Pose2d
import com.areslib.pathing.Costmap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RobotFieldConfigTest {

    @Test
    fun testInitialPoseCalculation() {
        val config = RobotFieldConfig(
            redDriverStation = DriverStationSide.SOUTH,
            blueDriverStation = DriverStationSide.NORTH
        )

        // Red starts South, facing North (PI/2)
        val redPose = config.getInitialPose(Alliance.RED)
        assertEquals(0.0, redPose.x, 0.001)
        assertEquals(-1.8, redPose.y, 0.001)
        assertEquals(Math.PI / 2.0, redPose.heading.radians, 0.001)

        // Blue starts North, facing South (-PI/2)
        val bluePose = config.getInitialPose(Alliance.BLUE)
        assertEquals(0.0, bluePose.x, 0.001)
        assertEquals(1.8, bluePose.y, 0.001)
        assertEquals(-Math.PI / 2.0, bluePose.heading.radians, 0.001)
    }

    @Test
    fun testJoystickMapping() {
        val config = RobotFieldConfig(
            redDriverStation = DriverStationSide.WEST,
            blueDriverStation = DriverStationSide.EAST
        )

        // West side driver station: Forward joystick is +X, Left is +Y
        val redMapping = config.mapJoystickIntents(1.0, 0.5, Alliance.RED)
        assertEquals(1.0, redMapping.first, 0.001) // vx
        assertEquals(0.5, redMapping.second, 0.001) // vy

        // East side driver station: Forward joystick is -X, Left is -Y
        val blueMapping = config.mapJoystickIntents(1.0, 0.5, Alliance.BLUE)
        assertEquals(-1.0, blueMapping.first, 0.001) // vx
        assertEquals(-0.5, blueMapping.second, 0.001) // vy
    }

    @Test
    fun testJsonParsing() {
        // A typical JSON string exported by the Field Editor dashboard
        val json = """
            {
              "id": "config_xyz",
              "name": "Championship 2026",
              "gameYear": "2025-2026",
              "fieldType": "ftc",
              "xAxisDirection": "up",
              "yAxisDirection": "left",
              "redDriverStation": "south",
              "blueDriverStation": "north",
              "obstacles": [
                {
                  "id": "obs_1",
                  "name": "Center Block",
                  "x": 0.5,
                  "y": -0.5,
                  "width": 0.4,
                  "height": 0.4,
                  "isBlocking": true,
                  "obstacleType": "blocking"
                },
                {
                  "id": "obs_2",
                  "name": "Ramp 1",
                  "x": -0.5,
                  "y": 0.5,
                  "width": 0.3,
                  "height": 0.6,
                  "isBlocking": false,
                  "obstacleType": "ramp",
                  "rampDirection": "up"
                }
              ]
            }
        """.trimIndent()

        val tempFile = java.io.File.createTempFile("field_config_test", ".json")
        tempFile.writeText(json)

        try {
            val success = RobotFieldManager.loadFromJsonFile(tempFile.absolutePath)
            assertTrue(success)

            val config = RobotFieldManager.activeConfig
            assertEquals("config_xyz", config.id)
            assertEquals("Championship 2026", config.name)
            assertEquals("2025-2026", config.gameYear)
            assertEquals(FieldType.FTC, config.fieldType)
            assertEquals(AxisDirection.UP, config.xAxisDirection)
            assertEquals(AxisDirection.LEFT, config.yAxisDirection)
            assertEquals(DriverStationSide.SOUTH, config.redDriverStation)
            assertEquals(DriverStationSide.NORTH, config.blueDriverStation)

            assertEquals(2, config.obstacles.size)
            val obs1 = config.obstacles[0]
            assertEquals("obs_1", obs1.id)
            assertEquals("Center Block", obs1.name)
            assertEquals(0.5, obs1.x, 0.001)
            assertEquals(-0.5, obs1.y, 0.001)
            assertEquals(0.4, obs1.width, 0.001)
            assertEquals(0.4, obs1.height, 0.001)
            assertTrue(obs1.isBlocking)
            assertEquals(ObstacleType.BLOCKING, obs1.obstacleType)

            val obs2 = config.obstacles[1]
            assertEquals("obs_2", obs2.id)
            assertEquals("Ramp 1", obs2.name)
            assertFalse(obs2.isBlocking)
            assertEquals(ObstacleType.RAMP, obs2.obstacleType)
            assertEquals(AxisDirection.UP, obs2.rampDirection)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testCostmapRasterization() {
        val costmap = Costmap(widthMeters = 3.6, heightMeters = 3.6, resolutionMeters = 0.1)
        costmap.clear()

        // Create an obstacle of size 0.2m x 0.2m centered at origin (0.0, 0.0)
        // Bounding box in meters: X is [-0.1, 0.1], Y is [-0.1, 0.1]
        val obstacle = RobotFieldObstacle(
            x = 0.0,
            y = 0.0,
            width = 0.2,
            height = 0.2,
            isBlocking = true
        )

        costmap.setStaticObstacles(listOf(obstacle))

        // Grid width/height is 3.6m / 0.1 = 36 cells.
        // Origin is (-1.8, -1.8).
        // Centered cell is cellX = 18, cellY = 18.
        // Check if the center coordinates cell is occupied
        assertTrue(costmap.isOccupied(0.0, 0.0))
        assertTrue(costmap.isOccupied(0.05, 0.05))
        assertTrue(costmap.isOccupied(-0.05, -0.05))

        // Check if points far from center are free
        assertFalse(costmap.isOccupied(0.5, 0.5))
        assertFalse(costmap.isOccupied(-0.5, -0.5))
    }

    @Test
    fun testFrcInitialPoseCalculation() {
        val config = RobotFieldConfig(
            fieldType = FieldType.FRC
        )

        // Blue starts at (0.5, 4.1055) facing 0.0
        val bluePose = config.getInitialPose(Alliance.BLUE)
        assertEquals(0.5, bluePose.x, 0.001)
        assertEquals(4.1055, bluePose.y, 0.001)
        assertEquals(0.0, bluePose.heading.radians, 0.001)

        // Red starts at (16.041, 4.1055) facing PI (wrapped to -PI)
        val redPose = config.getInitialPose(Alliance.RED)
        assertEquals(16.041, redPose.x, 0.001)
        assertEquals(4.1055, redPose.y, 0.001)
        assertEquals(-Math.PI, redPose.heading.radians, 0.001)
    }

    @Test
    fun testFrcJoystickMapping() {
        val config = RobotFieldConfig(
            fieldType = FieldType.FRC
        )

        // Blue alliance: vx = forward, vy = left
        val blueMapping = config.mapJoystickIntents(1.0, 0.5, Alliance.BLUE)
        assertEquals(1.0, blueMapping.first, 0.001)
        assertEquals(0.5, blueMapping.second, 0.001)

        // Red alliance: vx = -forward, vy = -left
        val redMapping = config.mapJoystickIntents(1.0, 0.5, Alliance.RED)
        assertEquals(-1.0, redMapping.first, 0.001)
        assertEquals(-0.5, redMapping.second, 0.001)
    }

    @Test
    fun testNewFieldsJsonParsing() {
        val json = """
            {
              "id": "config_new",
              "name": "Championship 2026 Elements",
              "fieldType": "ftc",
              "obstacles": [
                {
                  "id": "obs_poly",
                  "name": "Poly Obstacle",
                  "x": 0.0,
                  "y": 0.0,
                  "width": 0.5,
                  "height": 0.5,
                  "isBlocking": true,
                  "shape": "polygon",
                  "points": [
                    {"x": -0.25, "y": -0.25},
                    {"x": 0.25, "y": -0.25},
                    {"x": 0.25, "y": 0.25},
                    {"x": -0.25, "y": 0.25}
                  ],
                  "friction": 0.65,
                  "restitution": 0.25,
                  "rotation": 90.0
                }
              ],
              "elementTypes": [
                {
                  "id": "type_ball",
                  "name": "Game Ball",
                  "shape": "sphere",
                  "width": 0.2,
                  "height": 0.2,
                  "depth": 0.2,
                  "diameter": 0.2,
                  "color": "#FF0000",
                  "massKg": 0.1,
                  "movable": true
                }
              ],
              "elements": [
                {
                  "id": "ball_1",
                  "elementTypeId": "type_ball",
                  "x": 1.0,
                  "y": 1.0,
                  "rotation": 0.0
                }
              ]
            }
        """.trimIndent()

        val tempFile = java.io.File.createTempFile("field_config_elements_test", ".json")
        tempFile.writeText(json)

        try {
            val success = RobotFieldManager.loadFromJsonFile(tempFile.absolutePath)
            assertTrue(success)

            val config = RobotFieldManager.activeConfig
            assertEquals("config_new", config.id)
            
            // Check custom physics obstacle properties
            assertEquals(1, config.obstacles.size)
            val obs = config.obstacles[0]
            assertEquals("obs_poly", obs.id)
            assertEquals("polygon", obs.shape)
            assertEquals(4, obs.points.size)
            assertEquals(-0.25, obs.points[0].x, 0.001)
            assertEquals(0.65, obs.friction, 0.001)
            assertEquals(0.25, obs.restitution, 0.001)
            assertEquals(90.0, obs.rotation, 0.001)

            // Check element types
            assertEquals(1, config.elementTypes.size)
            val type = config.elementTypes[0]
            assertEquals("type_ball", type.id)
            assertEquals("Game Ball", type.name)
            assertEquals("sphere", type.shape)
            assertEquals(0.2, type.width, 0.001)
            assertEquals(0.2, type.diameter ?: 0.0, 0.001)
            assertTrue(type.movable)

            // Check elements
            assertEquals(1, config.elements.size)
            val el = config.elements[0]
            assertEquals("ball_1", el.id)
            assertEquals("type_ball", el.elementTypeId)
            assertEquals(1.0, el.x, 0.001)
            assertEquals(1.0, el.y, 0.001)
            assertEquals(0.0, el.rotation, 0.001)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testCostmapFromFieldConfig() {
        val obstacle = RobotFieldObstacle(
            x = 0.5,
            y = 0.5,
            width = 0.2,
            height = 0.2,
            isBlocking = true
        )
        
        val elementType = RobotFieldElementType(
            id = "static_pillar_type",
            name = "Static Pillar",
            shape = "box",
            width = 0.4,
            height = 0.4,
            depth = 1.0,
            massKg = 10.0,
            movable = false
        )
        
        val elementInstance = RobotFieldElementInstance(
            id = "pillar_1",
            elementTypeId = "static_pillar_type",
            x = -0.5,
            y = -0.5,
            rotation = 0.0
        )

        val config = RobotFieldConfig(
            fieldType = FieldType.FTC,
            obstacles = listOf(obstacle),
            elementTypes = listOf(elementType),
            elements = listOf(elementInstance)
        )

        // Bumper radius is 0.1m, Resolution is 0.1m.
        val costmap = Costmap.fromFieldConfig(config, robotRadiusMeters = 0.1)

        // Verify bounds & sizes for FTC config (3.66m)
        assertEquals(3.66, costmap.widthMeters, 0.001)
        assertEquals(3.66, costmap.heightMeters, 0.001)
        assertEquals(-1.83, costmap.origin.x, 0.001)
        assertEquals(-1.83, costmap.origin.y, 0.001)

        // Verify obstacle is rasterized
        assertTrue(costmap.isOccupied(0.5, 0.5))

        // Verify static element is rasterized
        assertTrue(costmap.isOccupied(-0.5, -0.5))

        // Verify inflation (robotRadius = 0.1m, so cell at (0.5+0.1, 0.5) is inflated/blocked)
        assertFalse(costmap.isTraversable(0.6, 0.5))
        // Center cell (occupied) should be non-traversable
        assertFalse(costmap.isTraversable(0.5, 0.5))
        // Point far away should be traversable
        assertTrue(costmap.isTraversable(0.0, 0.0))
    }
}
