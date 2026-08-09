package com.areslib.sim.physics

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.sim.field.FieldElementLoader
import com.areslib.sim.field.FieldObstacleLoader
import com.areslib.sim.network.NT4FieldPublisher
import com.areslib.state.Alliance
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldManager
import org.dyn4j.dynamics.Body
import org.dyn4j.world.World
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Vector2
import java.io.File

/**
 * Manages the Dyn4j 2D top-down physics world, robot body creation, perimeter walls,
 * static field obstacles, and dynamic game piece elements.
 */
class SimPhysicsWorld {
    val world = World<Body>()
    val robotBody = Body()
    val activeObstacles = mutableListOf<Body>()
    val gamePieces = mutableListOf<Body>()

    private val FIELD_WIDTH = 3.65
    private val FIELD_HEIGHT = 3.65

    init {
        world.setGravity(Vector2(0.0, 0.0))

        val robotFixture = robotBody.addFixture(Geometry.createRectangle(0.45, 0.45))
        robotFixture.density = 74.0 // ~15 kg
        robotBody.setMass(MassType.NORMAL)
        robotBody.linearDamping = 1.5
        robotBody.angularDamping = 3.0

        world.addBody(robotBody)
        createWalls()
    }

    /**
     * setupSpawnPose declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun setupSpawnPose(isRedAlliance: Boolean): Pose2d {
        val startPose = if (isRedAlliance) {
            Pose2d(0.0, -1.2, Rotation2d(Math.PI / 2.0))
        } else {
            Pose2d(0.0, 1.2, Rotation2d(-Math.PI / 2.0))
        }
        robotBody.transform.setTranslation(startPose.x, startPose.y)
        robotBody.transform.setRotation(startPose.heading.radians)
        return startPose
    }

    /**
     * loadFieldElements declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun loadFieldElements(activeConfig: RobotFieldConfig?) {
        if (activeConfig != null) {
            val obstacles = FieldObstacleLoader.loadObstacles(world, activeConfig.obstacles)
            activeObstacles.addAll(obstacles)
            val elements = FieldElementLoader.loadElements(world, activeConfig.elementTypes, activeConfig.elements)
            gamePieces.addAll(elements)
            NT4FieldPublisher.publishConfigId(activeConfig.id)
            NT4FieldPublisher.publishObstacles(activeConfig.obstacles)
            NT4FieldPublisher.publishAprilTags(activeConfig.apriltags)
        } else {
            var obstaclesFile: File? = null
            val obsPaths = listOf(
                File(System.getProperty("user.home"), "dev/robotics/ares/ARES-FTC/TeamCode/src/main/assets/paths/obstacles.json").path,
                "../ARES-FTC/TeamCode/src/main/assets/paths/obstacles.json",
                "src/main/assets/paths/obstacles.json",
                "TeamCode/src/main/assets/paths/obstacles.json",
                "../src/main/assets/paths/obstacles.json"
            )
            for (p in obsPaths) {
                val f = File(p)
                if (f.exists()) {
                    obstaclesFile = f
                    break
                }
            }
            if (obstaclesFile != null) {
                try {
                    println("[Simulator] Loading obstacles from: ${obstaclesFile.absolutePath}")
                    val content = obstaclesFile.readText()
                    val obstacles = FieldObstacleLoader.loadObstaclesFromAnalyticsJson(content)
                    val loaded = FieldObstacleLoader.loadObstacles(world, obstacles)
                    activeObstacles.addAll(loaded)
                    NT4FieldPublisher.publishObstacles(obstacles)

                    val newConfig = RobotFieldManager.activeConfig.copy(obstacles = obstacles)
                    RobotFieldManager.setActiveConfig(newConfig)
                } catch (e: Exception) {
                    println("Failed to load initial field obstacles: ${e.message}")
                }
            }

            var gamePiecesFile: File? = null
            val gpPaths = listOf(
                File(System.getProperty("user.home"), "dev/robotics/ares/ARES-FTC/TeamCode/src/main/assets/paths/game_pieces.json").path,
                "../ARES-FTC/TeamCode/src/main/assets/paths/game_pieces.json",
                "src/main/assets/paths/game_pieces.json",
                "TeamCode/src/main/assets/paths/game_pieces.json",
                "../src/main/assets/paths/game_pieces.json"
            )
            for (p in gpPaths) {
                val f = File(p)
                if (f.exists()) {
                    gamePiecesFile = f
                    break
                }
            }
            if (gamePiecesFile != null) {
                try {
                    println("[Simulator] Loading game pieces from: ${gamePiecesFile.absolutePath}")
                    val content = gamePiecesFile.readText()
                    val loadedGp = FieldElementLoader.loadGamePiecesFromAnalyticsJson(world, content)
                    gamePieces.addAll(loadedGp)
                } catch (e: Exception) {
                    println("Failed to load initial game pieces: ${e.message}")
                }
            }
        }
    }


    private fun createWalls() {
        val halfW = FIELD_WIDTH / 2.0
        val halfH = FIELD_HEIGHT / 2.0
        val thickness = 0.1

        val walls = listOf(
            Geometry.createRectangle(FIELD_WIDTH, thickness) to Vector2(0.0, halfH + thickness / 2.0),
            Geometry.createRectangle(FIELD_WIDTH, thickness) to Vector2(0.0, -halfH - thickness / 2.0),
            Geometry.createRectangle(thickness, FIELD_HEIGHT) to Vector2(-halfW - thickness / 2.0, 0.0),
            Geometry.createRectangle(thickness, FIELD_HEIGHT) to Vector2(halfW + thickness / 2.0, 0.0)
        )

        for ((shape, pos) in walls) {
            val wallBody = Body()
            wallBody.addFixture(shape)
            wallBody.setMass(MassType.INFINITE)
            wallBody.transform.setTranslation(pos)
            world.addBody(wallBody)
        }
    }
}
