package com.areslib.sim.physics

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.sim.field.FieldElementLoader
import com.areslib.sim.field.FieldObstacleLoader
import com.areslib.sim.network.NT4FieldPublisher
import com.areslib.state.Alliance
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldManager
import com.areslib.state.RobotFieldValidator
import org.dyn4j.dynamics.Body
import org.dyn4j.world.World
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Vector2
import java.io.File

/**
 * Owns the center-origin Dyn4j top-down world and its robot/field bodies.
 *
 * Distances are meters and body rotations are CCW-positive radians. Field boundaries follow the
 * active canonical document dimensions in the center-origin frame. [loadFieldElements] removes prior dynamic field content
 * before loading a supplied configuration; when no configuration is supplied it searches the
 * running project's canonical assets before developer-only fallbacks and leaves a missing/invalid
 * asset category empty.
 *
 * Dyn4j world mutation is single-thread-owned by the simulation loop. Public body collections are
 * exposed for visualization but callers must not mutate them concurrently with physics stepping.
 */
class SimPhysicsWorld {
    val world = World<Body>()
    val robotBody = Body()
    val activeObstacles = mutableListOf<Body>()
    val gamePieces = mutableListOf<Body>()
    val fieldWalls = mutableListOf<Body>()

    var loadedFieldConfig: RobotFieldConfig? = null
        private set

    private var fieldWidth = com.areslib.math.coordinate.CoordinateTransformers.FTC_FIELD_SIZE
    private var fieldHeight = com.areslib.math.coordinate.CoordinateTransformers.FTC_FIELD_SIZE

    init {
        world.setGravity(Vector2(0.0, 0.0))

        val robotFixture = robotBody.addFixture(Geometry.createRectangle(0.45, 0.45))
        robotFixture.density = 74.0 // ~15 kg
        robotBody.setMass(MassType.NORMAL)
        robotBody.linearDamping = 1.5
        robotBody.angularDamping = 3.0

        world.addBody(robotBody)
        rebuildWalls(fieldWidth, fieldHeight)
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
     * Replaces obstacles and game pieces from [activeConfig], or discovers the canonical `field.json`
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
            loadedFieldConfig = activeConfig
            RobotFieldManager.setActiveConfig(activeConfig)
            rebuildWallsIfDimensionsChanged(activeConfig.resolvedWidthMeters, activeConfig.resolvedHeightMeters)
            val obstacles = FieldObstacleLoader.loadObstacles(world, activeConfig.obstacles)
            activeObstacles.addAll(obstacles)
            val elements = FieldElementLoader.loadElements(world, activeConfig.elementTypes, activeConfig.elements)
            gamePieces.addAll(elements)
            NT4FieldPublisher.publishConfigId(activeConfig.id)
            NT4FieldPublisher.publishObstacles(activeConfig.obstacles)
            NT4FieldPublisher.publishAprilTags(activeConfig.apriltags)
        } else {
            val canonicalConfigPaths = canonicalFieldConfigPaths(
                workingDirectory = File(System.getProperty("user.dir")),
                userHome = File(System.getProperty("user.home")),
            )
            val canonicalConfigFile = canonicalConfigPaths.firstOrNull(File::isFile)
            if (canonicalConfigFile != null) {
                try {
                    val config = RobotFieldDocument.decode(canonicalConfigFile.readText())
                    RobotFieldManager.setActiveConfig(config)
                    println("[Simulator] Loading canonical field document: ${canonicalConfigFile.absolutePath}")
                    loadFieldElements(config)
                    return
                } catch (e: Exception) {
                    System.err.println("[Simulator] Failed to load canonical field document: ${e.message}")
                }
            }

        }
    }


    private fun rebuildWallsIfDimensionsChanged(width: Double, height: Double) {
        if (width == fieldWidth && height == fieldHeight) return
        rebuildWalls(width, height)
    }

    private fun rebuildWalls(width: Double, height: Double) {
        fieldWalls.forEach(world::removeBody)
        fieldWalls.clear()
        fieldWidth = width
        fieldHeight = height

        val halfW = width / 2.0
        val halfH = height / 2.0
        val thickness = 0.1

        val walls = listOf(
            Geometry.createRectangle(width, thickness) to Vector2(0.0, halfH + thickness / 2.0),
            Geometry.createRectangle(width, thickness) to Vector2(0.0, -halfH - thickness / 2.0),
            Geometry.createRectangle(thickness, height) to Vector2(-halfW - thickness / 2.0, 0.0),
            Geometry.createRectangle(thickness, height) to Vector2(halfW + thickness / 2.0, 0.0)
        )

        for ((shape, pos) in walls) {
            val wallBody = Body()
            wallBody.addFixture(shape)
            wallBody.setMass(MassType.INFINITE)
            wallBody.transform.setTranslation(pos)
            world.addBody(wallBody)
            fieldWalls += wallBody
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
            val updatedConfig = (loadedFieldConfig ?: RobotFieldManager.activeConfig).copy(obstacles = obstacles)
            loadedFieldConfig = updatedConfig
            RobotFieldManager.setActiveConfig(updatedConfig)
            NT4FieldPublisher.publishObstacles(obstacles)
            true
        } catch (e: Exception) {
            System.err.println("Failed to apply dashboard obstacles: ${e.message}")
            false
        }
    }

    /** Atomically replaces all authored field content from the canonical editor document. */
    fun replaceFieldDocumentJson(json: String): Boolean {
        return try {
            val config = RobotFieldDocument.decode(json)
            val validationIssues = RobotFieldValidator.validate(config)
            if (validationIssues.isNotEmpty()) {
                System.err.println("Failed to apply dashboard field document: ${validationIssues.first().message}")
                return false
            }
            loadFieldElements(config)
            true
        } catch (e: Exception) {
            System.err.println("Failed to apply dashboard field document: ${e.message}")
            false
        }
    }
}

/**
 * Candidate field documents in ownership order.
 *
 * The running project's own assets must win over developer-convenience fallbacks. Otherwise a
 * generated project can silently simulate an unrelated sibling checkout when both exist.
 */
internal fun canonicalFieldConfigPaths(workingDirectory: File, userHome: File): List<File> = listOf(
    File(workingDirectory, "TeamCode/src/main/assets/paths/field.json"),
    File(workingDirectory, "src/main/assets/paths/field.json"),
    File(workingDirectory, "src/main/deploy/paths/field.json"),
    File(workingDirectory, "../ARES-FTC/TeamCode/src/main/assets/paths/field.json"),
    File(userHome, "dev/robotics/ares/ARES-FTC/TeamCode/src/main/assets/paths/field.json"),
)
