package com.areslib.pathing

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.areslib.math.geometry.Translation2d

/**
 * PathPlanner `.path` Trajectory Data JSON Parser.
 *
 * Extracts raw Bezier control points, rotation targets, point-towards zones, constraint zones,
 * and marker events from PathPlanner `.path` JSON strings.
 *
 * ### Physical Units:
 * - Waypoint Coordinates $(x, y)$: Meters ($m$)
 * - Headings & Rotation Targets: Degrees ($^\circ$) in JSON, converted to **CCW-positive** radians ($rad$)
 * - Velocities: Meters per second ($m/s$)
 * - Accelerations: Meters per second squared ($m/s^2$)
 *
 * @see DynamicPathLoader
 * @see SplineMotionProfiler
 */
object PathPlannerJsonParser {
    private val gson = Gson()

    data class ParsedPathData(
        val waypoints: List<WaypointData>,
        val defaultMaxVel: Double,
        val defaultMaxAccel: Double,
        val startVel: Double,
        val startRotDeg: Double?,
        val endVel: Double,
        val endRotDeg: Double?,
        val rotationTargets: List<ParsedRotationTarget>,
        val constraintZones: List<ParsedConstraintsZone>,
        val pointTowardsZones: List<ParsedPointTowardsZone>,
        val eventMarkers: List<ParsedEventMarker>
    )

    /**
     * Class implementation for Waypoint Data.
     *
     * Autonomous path planning, trajectory generation, and obstacle avoidance module.
     *
     * ### Coordinate System:
     * Field-centric coordinates in meters ($m$) relative to field origin.
     */
    data class WaypointData(
        val anchor: Translation2d,
        val prevControl: Translation2d,
        val nextControl: Translation2d
    )

    /**
     * Class implementation for Parsed Rotation Target.
     *
     * Autonomous path planning, trajectory generation, and obstacle avoidance module.
     *
     * ### Coordinate System:
     * Field-centric coordinates in meters ($m$) relative to field origin.
     */
    data class ParsedRotationTarget(
        val waypointRelativePos: Double,
        val rotationDegrees: Double
    )

    /**
     * Class implementation for Parsed Constraints Zone.
     *
     * Autonomous path planning, trajectory generation, and obstacle avoidance module.
     *
     * ### Coordinate System:
     * Field-centric coordinates in meters ($m$) relative to field origin.
     */
    data class ParsedConstraintsZone(
        val minWaypointRelativePos: Double,
        val maxWaypointRelativePos: Double,
        val maxVelocity: Double,
        val maxAcceleration: Double
    )

    /**
     * Class implementation for Parsed Point Towards Zone.
     *
     * Autonomous path planning, trajectory generation, and obstacle avoidance module.
     *
     * ### Coordinate System:
     * Field-centric coordinates in meters ($m$) relative to field origin.
     */
    data class ParsedPointTowardsZone(
        val minWaypointRelativePos: Double,
        val maxWaypointRelativePos: Double,
        val rotationOffset: Double,
        val x: Double,
        val y: Double
    )

    /**
     * Class implementation for Parsed Event Marker.
     *
     * Autonomous path planning, trajectory generation, and obstacle avoidance module.
     *
     * ### Coordinate System:
     * Field-centric coordinates in meters ($m$) relative to field origin.
     */
    data class ParsedEventMarker(
        val waypointRelativePos: Double,
        val commandName: String
    )

    fun parse(jsonString: String, fallbackMaxVel: Double, fallbackMaxAccel: Double): ParsedPathData {
        return try {
            parseInternal(jsonString, fallbackMaxVel, fallbackMaxAccel)
        } catch (e: Exception) {
            System.err.println("[PathPlannerJsonParser] Failed to parse PathPlanner JSON: ${e.message}; returning empty result")
            emptyParsedData(fallbackMaxVel, fallbackMaxAccel)
        }
    }

    private fun emptyParsedData(fallbackMaxVel: Double, fallbackMaxAccel: Double): ParsedPathData =
        ParsedPathData(
            waypoints = emptyList(),
            defaultMaxVel = fallbackMaxVel,
            defaultMaxAccel = fallbackMaxAccel,
            startVel = 0.0,
            startRotDeg = null,
            endVel = 0.0,
            endRotDeg = null,
            rotationTargets = emptyList(),
            constraintZones = emptyList(),
            pointTowardsZones = emptyList(),
            eventMarkers = emptyList()
        )

    /**
     * Sanitizes a `waypointRelativePos` value.
     *
     * PathPlanner encodes marker/target position as `segmentIndex + fraction` (fraction in
     * [0,1]), so the valid global range is [0, numWaypoints-1]. Non-finite or out-of-range
     * values otherwise slip through and (via a silent binarySearch clamp in SplineMotionProfiler)
     * fire markers at the wrong trajectory point.
     */
    private fun sanitizeRelativePos(pos: Double, maxRelativePos: Double, context: String): Double {
        val max = if (maxRelativePos.isFinite() && maxRelativePos >= 0.0) maxRelativePos else 1.0
        return when {
            pos.isNaN() || pos.isInfinite() -> {
                System.err.println("[PathPlannerJsonParser] $context has non-finite waypointRelativePos ($pos); clamping to 0.0")
                0.0
            }
            pos < 0.0 -> {
                System.err.println("[PathPlannerJsonParser] $context has waypointRelativePos $pos < 0; clamping to 0.0")
                0.0
            }
            pos > max -> {
                System.err.println("[PathPlannerJsonParser] $context has waypointRelativePos $pos beyond path extent ($max); clamping to $max")
                max
            }
            else -> pos
        }
    }

    private fun parseInternal(jsonString: String, fallbackMaxVel: Double, fallbackMaxAccel: Double): ParsedPathData {
        val root = gson.fromJson(jsonString, JsonObject::class.java)

        val waypointsArray = root.getAsJsonArray("waypoints")
        val parsedWaypoints = mutableListOf<WaypointData>()
        for (i in 0 until waypointsArray.size()) {
            val wp = waypointsArray.get(i).asJsonObject
            val anchorNode = wp.getAsJsonObject("anchor")
            val anchor = Translation2d(anchorNode.get("x").asDouble, anchorNode.get("y").asDouble)

            val prevNode = if (wp.has("prevControl") && !wp.get("prevControl").isJsonNull) wp.getAsJsonObject("prevControl") else null
            val prevControl = prevNode?.let { Translation2d(it.get("x").asDouble, it.get("y").asDouble) } ?: anchor

            val nextNode = if (wp.has("nextControl") && !wp.get("nextControl").isJsonNull) wp.getAsJsonObject("nextControl") else null
            val nextControl = nextNode?.let { Translation2d(it.get("x").asDouble, it.get("y").asDouble) } ?: anchor

            parsedWaypoints.add(WaypointData(anchor, prevControl, nextControl))
        }

        // waypointRelativePos uses index.fraction semantics (segmentIndex + fraction), so a
        // marker on segment i has value in [i, i+1] and the valid global range is
        // [0, numWaypoints-1]. Out-of-range / non-finite values would make the marker fire at
        // the wrong trajectory point (SplineMotionProfiler binarySearch clamps silently).
        val maxRelativePos = (parsedWaypoints.size - 1).coerceAtLeast(0).toDouble()

        val globalConstraints = if (root.has("globalConstraints") && !root.get("globalConstraints").isJsonNull) {
            root.getAsJsonObject("globalConstraints")
        } else null
        val defaultMaxVel = globalConstraints?.get("maxVelocity")?.asDouble ?: fallbackMaxVel
        val defaultMaxAccel = globalConstraints?.get("maxAcceleration")?.asDouble ?: fallbackMaxAccel

        val startState = when {
            root.has("idealStartingState") && !root.get("idealStartingState").isJsonNull -> root.getAsJsonObject("idealStartingState")
            root.has("previewStartingState") && !root.get("previewStartingState").isJsonNull -> root.getAsJsonObject("previewStartingState")
            else -> null
        }
        val startVel = startState?.get("velocity")?.asDouble ?: 0.0
        val startRotDeg = startState?.get("rotation")?.asDouble

        val goalEndState = if (root.has("goalEndState") && !root.get("goalEndState").isJsonNull) {
            root.getAsJsonObject("goalEndState")
        } else null
        val endVel = goalEndState?.get("velocity")?.asDouble ?: 0.0
        val endRotDeg = goalEndState?.get("rotation")?.asDouble

        val parsedRotationTargets = mutableListOf<ParsedRotationTarget>()
        if (root.has("rotationTargets") && !root.get("rotationTargets").isJsonNull) {
            val arr = root.getAsJsonArray("rotationTargets")
            for (i in 0 until arr.size()) {
                val obj = arr.get(i).asJsonObject
                parsedRotationTargets.add(
                    ParsedRotationTarget(
                        waypointRelativePos = sanitizeRelativePos(obj.get("waypointRelativePos").asDouble, maxRelativePos, "rotation target $i"),
                        rotationDegrees = obj.get("rotationDegrees").asDouble
                    )
                )
            }
        }

        val parsedConstraintZones = mutableListOf<ParsedConstraintsZone>()
        if (root.has("constraintZones") && !root.get("constraintZones").isJsonNull) {
            val arr = root.getAsJsonArray("constraintZones")
            for (i in 0 until arr.size()) {
                val obj = arr.get(i).asJsonObject
                val minPos = obj.get("minWaypointRelativePos").asDouble
                val maxPos = obj.get("maxWaypointRelativePos").asDouble
                val cObj = obj.getAsJsonObject("constraints")
                val zMaxVel = cObj.get("maxVelocity").asDouble
                val zMaxAccel = cObj.get("maxAcceleration").asDouble
                parsedConstraintZones.add(ParsedConstraintsZone(minPos, maxPos, zMaxVel, zMaxAccel))
            }
        }

        val parsedPointTowardsZones = mutableListOf<ParsedPointTowardsZone>()
        if (root.has("pointTowardsZones") && !root.get("pointTowardsZones").isJsonNull) {
            val arr = root.getAsJsonArray("pointTowardsZones")
            for (i in 0 until arr.size()) {
                val obj = arr.get(i).asJsonObject
                val minPos = obj.get("minWaypointRelativePos").asDouble
                val maxPos = obj.get("maxWaypointRelativePos").asDouble
                val offset = if (obj.has("rotationOffset") && !obj.get("rotationOffset").isJsonNull) obj.get("rotationOffset").asDouble else 0.0
                val posObj = obj.getAsJsonObject("fieldPosition")
                val tx = posObj.get("x").asDouble
                val ty = posObj.get("y").asDouble
                parsedPointTowardsZones.add(ParsedPointTowardsZone(minPos, maxPos, offset, tx, ty))
            }
        }

        val eventMarkers = mutableListOf<ParsedEventMarker>()
        if (root.has("eventMarkers") && !root.get("eventMarkers").isJsonNull) {
            val markersArray = root.getAsJsonArray("eventMarkers")
            for (i in 0 until markersArray.size()) {
                val marker = markersArray.get(i).asJsonObject
                if (!marker.has("waypointRelativePos") || marker.get("waypointRelativePos").isJsonNull) continue
                val pos = sanitizeRelativePos(marker.get("waypointRelativePos").asDouble, maxRelativePos, "event marker")

                var commandName = "Unknown"
                if (marker.has("command") && !marker.get("command").isJsonNull) {
                    val cmd = marker.getAsJsonObject("command")
                    if (cmd.has("name") && !cmd.get("name").isJsonNull) {
                        commandName = cmd.get("name").asString
                    }
                }
                eventMarkers.add(ParsedEventMarker(pos, commandName))
            }
        }

        return ParsedPathData(
            waypoints = parsedWaypoints,
            defaultMaxVel = defaultMaxVel,
            defaultMaxAccel = defaultMaxAccel,
            startVel = startVel,
            startRotDeg = startRotDeg,
            endVel = endVel,
            endRotDeg = endRotDeg,
            rotationTargets = parsedRotationTargets,
            constraintZones = parsedConstraintZones,
            pointTowardsZones = parsedPointTowardsZones,
            eventMarkers = eventMarkers
        )
    }
}
