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
 * Owns the center-origin Dyn4j top-down world and its robot/field bodies.
 *
 * Distances are meters and body rotations are CCW-positive radians. The FTC field is bounded at
 * approximately ±1.825 m on each axis. [loadFieldElements] removes prior dynamic field content
 * before loading a supplied configuration; when no configuration is supplied it searches known
 * development checkout locations and leaves a missing/invalid asset category empty.
 *
 * Dyn4j world mutation is single-thread-owned by the simulation loop. Public body collections are
 * exposed for visualization but callers must not mutate them concurrently with physics stepping.
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
     * Hard-sets the alliance spawn transform and returns the applied pose.
     * Red starts at `(0, -1.2, +π/2)` and Blue at `(0, +1.2, -π/2)` in the center-origin frame.
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
     * Replaces obstacles and game pieces from [activeConfig], or performs best-effort asset discovery
     * when it is `null`. The active dashboard field topics are updated for successfully loaded data.
     */
    fun loadFieldElements(activeConfig: RobotFieldConfig?) {
        for (body in activeObstacles) {
            world.removeBody(body)
        }
        activeObstacles.clear()

        for (body in gamePieces) {
            world.removeBody(body)
        }
        gamePieces.clear()

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

    /**
     * Replaces only static obstacles from an ARES-Analytics JSON-array payload.
     * Returns `false` without changing existing obstacles when parsing fails; once parsing succeeds,
     * replacement and NT4 publication occur synchronously on the caller's thread.
     */
    fun replaceObstaclesFromAnalyticsJson(json: String): Boolean {
        return try {
            if (!com.google.gson.JsonParser.parseString(json).isJsonArray) return false
            val obstacles = FieldObstacleLoader.loadObstaclesFromAnalyticsJson(json)
            for (body in activeObstacles) world.removeBody(body)
            activeObstacles.clear()
            activeObstacles.addAll(FieldObstacleLoader.loadObstacles(world, obstacles))
            RobotFieldManager.setActiveConfig(RobotFieldManager.activeConfig.copy(obstacles = obstacles))
            NT4FieldPublisher.publishObstacles(obstacles)
            true
        } catch (e: Exception) {
            System.err.println("Failed to apply dashboard obstacles: ${e.message}")
            false
        }
    }
}
