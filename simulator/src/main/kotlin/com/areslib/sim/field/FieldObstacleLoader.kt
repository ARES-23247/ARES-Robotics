package com.areslib.sim.field

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.dyn4j.dynamics.Body
import org.dyn4j.dynamics.BodyFixture
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Vector2
import org.dyn4j.world.World
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldObstacle
import com.areslib.state.RobotFieldPoint

/**
 * Dyn4j 2D physics engine obstacle generator and deserializer.
 *
 * Consumes field obstacle JSON configurations exported from ARES-Analytics or FTC field configs
 * and instantiates static 2D physical collision bodies (`Body`) into the active Dyn4j simulation [World].
 *
 * ### Supported Geometries:
 * - **Circle**: Radii and center coordinates in meters ($m$).
 * - **Rectangle / Box**: Width, height, center $(x, y)$ position, and rotation angle ($\theta$ in radians).
 * - **Convex Polygon**: Arbitrary 2D vertex list $[(x_1, y_1), (x_2, y_2), \dots]$ in meters ($m$).
 *
 * ### Coordinate System:
 * Field-centric coordinates in meters ($m$) relative to field center $(0,0)$.
 */
/**
 * Object implementation for Field Obstacle Loader.
 *
 * Robotics framework control component.
 */
object FieldObstacleLoader {
    private val gson = Gson()

    /**
     * Parses a JSON string containing field obstacles and spawns them into the Dyn4j 2D physics world.
     *
     * @param world Target Dyn4j physics world instance.
     * @param jsonString JSON string matching [RobotFieldConfig] or ARES-Analytics field editor exports.
     * @param inMeters Flag indicating whether spatial parameters are in meters (default: true).
     * @return List of spawned Dyn4j physical [Body] instances.
     */
    fun loadObstacles(world: World<Body>, jsonString: String, @Suppress("UNUSED_PARAMETER") inMeters: Boolean = false): List<Body> {
        try {
            val root = gson.fromJson(jsonString, com.google.gson.JsonObject::class.java)
            if (root == null) return emptyList()

            val obstaclesList = mutableListOf<RobotFieldObstacle>()
            if (root.has("obstacles") && !root.get("obstacles").isJsonNull) {
                val array = root.getAsJsonArray("obstacles")
                for (i in 0 until array.size()) {
                    obstaclesList.add(gson.fromJson(array.get(i), RobotFieldObstacle::class.java))
                }
            } else {
                val config = gson.fromJson(jsonString, RobotFieldConfig::class.java)
                if (config != null) {
                    obstaclesList.addAll(config.obstacles)
                }
            }
            return loadObstacles(world, obstaclesList)
        } catch (e: Exception) {
            System.err.println("Failed to parse field obstacles JSON: ${e.message}")
            e.printStackTrace()
        }
        return emptyList()
    }

    /**
     * Parses the obstacles.json format exported from the ARES-Analytics Field Editor.
     * Maps polymorphic Circle, Rectangle, and Polygon JSON objects to [RobotFieldObstacle] models.
     *
     * @param jsonString JSON string containing obstacle array.
     * @return Deserialized list of [RobotFieldObstacle] instances.
     */
    fun loadObstaclesFromAnalyticsJson(jsonString: String): List<RobotFieldObstacle> {
        val list = mutableListOf<RobotFieldObstacle>()
        try {
            val array = gson.fromJson(jsonString, com.google.gson.JsonArray::class.java) ?: return emptyList()
            for (i in 0 until array.size()) {
                val obj = array.get(i).asJsonObject
                val id = obj.get("id")?.asString ?: ""
                val name = obj.get("name")?.asString ?: ""
                val type = obj.get("type")?.asString ?: ""

                when {
                    type.contains("Circle") || (obj.has("radius") && !obj.has("width")) -> {
                        val centerX = obj.get("centerX")?.asDouble ?: 0.0
                        val centerY = obj.get("centerY")?.asDouble ?: 0.0
                        val radius = obj.get("radius")?.asDouble ?: 0.25
                        list.add(
                            RobotFieldObstacle(
                                id = id,
                                name = name,
                                x = centerX,
                                y = centerY,
                                width = radius,
                                height = radius,
                                shape = "circle"
                            )
                        )
                    }
                    type.contains("Rectangle") || (obj.has("width") && obj.has("height")) -> {
                        val centerX = obj.get("centerX")?.asDouble ?: 0.0
                        val centerY = obj.get("centerY")?.asDouble ?: 0.0
                        val width = obj.get("width")?.asDouble ?: 0.5
                        val height = obj.get("height")?.asDouble ?: 0.5
                        val rotation = obj.get("rotation")?.asDouble ?: 0.0
                        list.add(
                            RobotFieldObstacle(
                                id = id,
                                name = name,
                                x = centerX,
                                y = centerY,
                                width = width,
                                height = height,
                                rotation = rotation,
                                shape = "rectangle"
                            )
                        )
                    }
                    type.contains("Polygon") || obj.has("vertices") -> {
                        val verticesArray = obj.getAsJsonArray("vertices")
                        val pts = mutableListOf<RobotFieldPoint>()
                        if (verticesArray != null) {
                            for (vIdx in 0 until verticesArray.size()) {
                                val vObj = verticesArray.get(vIdx).asJsonObject
                                val vx = vObj.get("x")?.asDouble ?: 0.0
                                val vy = vObj.get("y")?.asDouble ?: 0.0
                                pts.add(RobotFieldPoint(vx, vy))
                            }
                        }
                        list.add(
                            RobotFieldObstacle(
                                id = id,
                                name = name,
                                points = pts,
                                shape = "polygon"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("FieldObstacleLoader: Failed to parse analytics JSON: ${e.message}")
        }
        return list
    }

    /**
     * Converts a list of [RobotFieldObstacle] objects into static Dyn4j physical bodies and adds them to the physics world.
     *
     * @param world Target Dyn4j physics world instance.
     * @param obstacles List of obstacles to instantiate.
     * @return List of created Dyn4j physical [Body] objects.
     */
    fun loadObstacles(world: World<Body>, obstacles: List<RobotFieldObstacle>): List<Body> {
        val bodies = mutableListOf<Body>()

        for (obs in obstacles) {
            val body = Body()
            body.setMass(MassType.INFINITE)

            when (obs.shape.lowercase()) {
                "circle" -> {
                    val radius = obs.width.coerceAtLeast(0.05)
                    val circle = Geometry.createCircle(radius)
                    val fixture = BodyFixture(circle)
                    fixture.friction = obs.friction
                    fixture.restitution = obs.restitution
                    body.addFixture(fixture)
                    body.transform.setTranslation(obs.x, obs.y)
                }
                "polygon" -> {
                    if (obs.points.size >= 3) {
                        val pts = obs.points
                        var polySuccess = false
                        try {
                            val vecs = pts.map { Vector2(it.x, it.y) }.toTypedArray()
                            val poly = Geometry.createPolygon(*vecs)
                            val fixture = BodyFixture(poly)
                            fixture.friction = obs.friction
                            fixture.restitution = obs.restitution
                            body.addFixture(fixture)
                            polySuccess = true
                        } catch (_: Exception) {}

                        if (!polySuccess) {
                            val wallThickness = 0.04
                            for (i in pts.indices) {
                                val p1 = pts[i]
                                val p2 = pts[(i + 1) % pts.size]
                                val dx = p2.x - p1.x
                                val dy = p2.y - p1.y
                                val length = kotlin.math.hypot(dx, dy)
                                if (length < 1e-4) continue

                                val angle = kotlin.math.atan2(dy, dx)
                                val midX = (p1.x + p2.x) / 2.0
                                val midY = (p1.y + p2.y) / 2.0

                                val wallRect = Geometry.createRectangle(length, wallThickness)
                                wallRect.rotate(angle)
                                wallRect.translate(midX, midY)

                                val fixture = BodyFixture(wallRect)
                                fixture.friction = obs.friction
                                fixture.restitution = obs.restitution
                                body.addFixture(fixture)
                            }
                        }
                    } else {
                        continue
                    }
                }
                else -> { // Default to rectangle
                    val w = obs.width.coerceAtLeast(0.05)
                    val h = obs.height.coerceAtLeast(0.05)
                    val rect = Geometry.createRectangle(w, h)
                    val fixture = BodyFixture(rect)
                    fixture.friction = obs.friction
                    fixture.restitution = obs.restitution
                    body.addFixture(fixture)
                    body.transform.setTranslation(obs.x, obs.y)
                    body.transform.rotation = org.dyn4j.geometry.Rotation(Math.toRadians(obs.rotation))
                }
            }

            world.addBody(body)
            bodies.add(body)
        }

        return bodies
    }
}
