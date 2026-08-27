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
    private const val MAX_WAYPOINTS = 512
    private const val MAX_METADATA_ENTRIES = 2_048
    private const val MAX_ABS_COORDINATE_METERS = 1_000.0
    private const val MAX_JSON_CHARACTERS = 4_194_304

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
        require(jsonString.isNotBlank()) { "PathPlanner JSON must not be blank" }
        require(jsonString.length <= MAX_JSON_CHARACTERS) { "PathPlanner JSON exceeds $MAX_JSON_CHARACTERS characters" }
        requirePositiveFinite(fallbackMaxVel, "fallback max velocity")
        requirePositiveFinite(fallbackMaxAccel, "fallback max acceleration")
        return try {
            parseInternal(jsonString, fallbackMaxVel, fallbackMaxAccel)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse PathPlanner path JSON: ${e.message}", e)
        }
    }

    /**
     * Sanitizes a `waypointRelativePos` value.
     *
     * PathPlanner encodes marker/target position as `segmentIndex + fraction` (fraction in
     * [0,1]), so the valid global range is [0, numWaypoints-1]. Non-finite or out-of-range
     * values otherwise slip through and (via a silent binarySearch clamp in SplineMotionProfiler)
     * fire markers at the wrong trajectory point.
     */
    private fun requireRelativePos(pos: Double, maxRelativePos: Double, context: String): Double {
        require(pos.isFinite() && pos in 0.0..maxRelativePos) {
            "$context waypointRelativePos must be finite and within [0, $maxRelativePos]"
        }
        return pos
    }

    private fun requirePositiveFinite(value: Double, context: String): Double {
        require(value.isFinite() && value > 0.0) { "$context must be finite and positive" }
        return value
    }

    private fun requireNonNegativeFinite(value: Double, context: String): Double {
        require(value.isFinite() && value >= 0.0) { "$context must be finite and non-negative" }
        return value
    }

    private fun coordinate(node: JsonObject, axis: String, context: String): Double {
        val value = requiredNumber(node, axis, context)
        require(value.isFinite() && kotlin.math.abs(value) <= MAX_ABS_COORDINATE_METERS) {
            "$context $axis must be finite and within +/-$MAX_ABS_COORDINATE_METERS m"
        }
        return value
    }

    private fun requiredNumber(node: JsonObject, key: String, context: String): Double {
        val element = node.get(key) ?: throw IllegalArgumentException("$context is missing '$key'")
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber) { "$context '$key' must be numeric" }
        return element.asDouble
    }

    private fun validateOptionalPositiveNumber(node: JsonObject, key: String, context: String) {
        if (node.has(key) && !node.get(key).isJsonNull) {
            requirePositiveFinite(requiredNumber(node, key, context), "$context $key")
        }
    }

    private fun parseInternal(jsonString: String, fallbackMaxVel: Double, fallbackMaxAccel: Double): ParsedPathData {
        val root = gson.fromJson(jsonString, JsonObject::class.java)
            ?: throw IllegalArgumentException("PathPlanner JSON must contain an object")

        val waypointsArray = root.getAsJsonArray("waypoints")
            ?: throw IllegalArgumentException("PathPlanner path is missing 'waypoints'")
        require(waypointsArray.size() >= 2) { "PathPlanner path requires at least two waypoints" }
        require(waypointsArray.size() <= MAX_WAYPOINTS) { "PathPlanner path exceeds $MAX_WAYPOINTS waypoints" }
        val parsedWaypoints = mutableListOf<WaypointData>()
        for (i in 0 until waypointsArray.size()) {
            val wp = waypointsArray.get(i).asJsonObject
            val anchorNode = wp.getAsJsonObject("anchor")
            val anchor = Translation2d(coordinate(anchorNode, "x", "waypoint $i anchor"), coordinate(anchorNode, "y", "waypoint $i anchor"))

            val prevNode = if (wp.has("prevControl") && !wp.get("prevControl").isJsonNull) wp.getAsJsonObject("prevControl") else null
            val prevControl = prevNode?.let { Translation2d(coordinate(it, "x", "waypoint $i prevControl"), coordinate(it, "y", "waypoint $i prevControl")) } ?: anchor

            val nextNode = if (wp.has("nextControl") && !wp.get("nextControl").isJsonNull) wp.getAsJsonObject("nextControl") else null
            val nextControl = nextNode?.let { Translation2d(coordinate(it, "x", "waypoint $i nextControl"), coordinate(it, "y", "waypoint $i nextControl")) } ?: anchor

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
        val defaultMaxVel = globalConstraints?.let { requiredNumber(it, "maxVelocity", "global constraints") }
            ?: fallbackMaxVel
        val defaultMaxAccel = globalConstraints?.let { requiredNumber(it, "maxAcceleration", "global constraints") }
            ?: fallbackMaxAccel
        requirePositiveFinite(defaultMaxVel, "global max velocity")
        requirePositiveFinite(defaultMaxAccel, "global max acceleration")
        globalConstraints?.let { constraints ->
            validateOptionalPositiveNumber(constraints, "maxAngularVelocity", "global constraints")
            validateOptionalPositiveNumber(constraints, "maxAngularAcceleration", "global constraints")
            validateOptionalPositiveNumber(constraints, "nominalVoltage", "global constraints")
        }

        val startState = when {
            root.has("idealStartingState") && !root.get("idealStartingState").isJsonNull -> root.getAsJsonObject("idealStartingState")
            root.has("previewStartingState") && !root.get("previewStartingState").isJsonNull -> root.getAsJsonObject("previewStartingState")
            else -> null
        }
        val startVel = startState?.let { requiredNumber(it, "velocity", "starting state") } ?: 0.0
        val startRotDeg = startState?.let { requiredNumber(it, "rotation", "starting state") }
        requireNonNegativeFinite(startVel, "starting velocity")
        require(startVel <= defaultMaxVel) { "starting velocity exceeds global max velocity" }
        require(startRotDeg == null || startRotDeg.isFinite()) { "starting rotation must be finite" }

        val goalEndState = if (root.has("goalEndState") && !root.get("goalEndState").isJsonNull) {
            root.getAsJsonObject("goalEndState")
        } else null
        val endVel = goalEndState?.let { requiredNumber(it, "velocity", "goal end state") } ?: 0.0
        val endRotDeg = goalEndState?.let { requiredNumber(it, "rotation", "goal end state") }
        requireNonNegativeFinite(endVel, "goal velocity")
        require(endVel <= defaultMaxVel) { "goal velocity exceeds global max velocity" }
        require(endRotDeg == null || endRotDeg.isFinite()) { "goal rotation must be finite" }

        val parsedRotationTargets = mutableListOf<ParsedRotationTarget>()
        if (root.has("rotationTargets") && !root.get("rotationTargets").isJsonNull) {
            val arr = root.getAsJsonArray("rotationTargets")
            require(arr.size() <= MAX_METADATA_ENTRIES) { "Too many rotation targets" }
            for (i in 0 until arr.size()) {
                val obj = arr.get(i).asJsonObject
                parsedRotationTargets.add(
                    ParsedRotationTarget(
                        waypointRelativePos = requireRelativePos(requiredNumber(obj, "waypointRelativePos", "rotation target $i"), maxRelativePos, "rotation target $i"),
                        rotationDegrees = requiredNumber(obj, "rotationDegrees", "rotation target $i").also { require(it.isFinite()) { "rotation target $i must be finite" } }
                    )
                )
            }
        }

        val parsedConstraintZones = mutableListOf<ParsedConstraintsZone>()
        if (root.has("constraintZones") && !root.get("constraintZones").isJsonNull) {
            val arr = root.getAsJsonArray("constraintZones")
            require(arr.size() <= MAX_METADATA_ENTRIES) { "Too many constraint zones" }
            for (i in 0 until arr.size()) {
                val obj = arr.get(i).asJsonObject
                val minPos = requiredNumber(obj, "minWaypointRelativePos", "constraint zone $i")
                val maxPos = requiredNumber(obj, "maxWaypointRelativePos", "constraint zone $i")
                val cObj = obj.getAsJsonObject("constraints")
                val zMaxVel = requiredNumber(cObj, "maxVelocity", "constraint zone $i constraints")
                val zMaxAccel = requiredNumber(cObj, "maxAcceleration", "constraint zone $i constraints")
                requireRelativePos(minPos, maxRelativePos, "constraint zone $i minimum")
                requireRelativePos(maxPos, maxRelativePos, "constraint zone $i maximum")
                require(minPos <= maxPos) { "constraint zone $i minimum exceeds maximum" }
                requirePositiveFinite(zMaxVel, "constraint zone $i max velocity")
                requirePositiveFinite(zMaxAccel, "constraint zone $i max acceleration")
                validateOptionalPositiveNumber(cObj, "maxAngularVelocity", "constraint zone $i constraints")
                validateOptionalPositiveNumber(cObj, "maxAngularAcceleration", "constraint zone $i constraints")
                validateOptionalPositiveNumber(cObj, "nominalVoltage", "constraint zone $i constraints")
                parsedConstraintZones.add(ParsedConstraintsZone(minPos, maxPos, zMaxVel, zMaxAccel))
            }
        }

        val parsedPointTowardsZones = mutableListOf<ParsedPointTowardsZone>()
        if (root.has("pointTowardsZones") && !root.get("pointTowardsZones").isJsonNull) {
            val arr = root.getAsJsonArray("pointTowardsZones")
            require(arr.size() <= MAX_METADATA_ENTRIES) { "Too many point-towards zones" }
            for (i in 0 until arr.size()) {
                val obj = arr.get(i).asJsonObject
                val minPos = requiredNumber(obj, "minWaypointRelativePos", "point-towards zone $i")
                val maxPos = requiredNumber(obj, "maxWaypointRelativePos", "point-towards zone $i")
                val offset = if (obj.has("rotationOffset") && !obj.get("rotationOffset").isJsonNull) {
                    requiredNumber(obj, "rotationOffset", "point-towards zone $i")
                } else 0.0
                val posObj = obj.getAsJsonObject("fieldPosition")
                val tx = coordinate(posObj, "x", "point-towards zone $i fieldPosition")
                val ty = coordinate(posObj, "y", "point-towards zone $i fieldPosition")
                requireRelativePos(minPos, maxRelativePos, "point-towards zone $i minimum")
                requireRelativePos(maxPos, maxRelativePos, "point-towards zone $i maximum")
                require(minPos <= maxPos) { "point-towards zone $i minimum exceeds maximum" }
                require(offset.isFinite()) { "point-towards zone $i rotation offset must be finite" }
                require(tx.isFinite() && kotlin.math.abs(tx) <= MAX_ABS_COORDINATE_METERS &&
                    ty.isFinite() && kotlin.math.abs(ty) <= MAX_ABS_COORDINATE_METERS) {
                    "point-towards zone $i position is outside the finite coordinate bounds"
                }
                parsedPointTowardsZones.add(ParsedPointTowardsZone(minPos, maxPos, offset, tx, ty))
            }
        }

        val eventMarkers = mutableListOf<ParsedEventMarker>()
        if (root.has("eventMarkers") && !root.get("eventMarkers").isJsonNull) {
            val markersArray = root.getAsJsonArray("eventMarkers")
            require(markersArray.size() <= MAX_METADATA_ENTRIES) { "Too many event markers" }
            for (i in 0 until markersArray.size()) {
                val marker = markersArray.get(i).asJsonObject
                require(marker.has("waypointRelativePos") && !marker.get("waypointRelativePos").isJsonNull) {
                    "event marker $i is missing waypointRelativePos"
                }
                val pos = requireRelativePos(requiredNumber(marker, "waypointRelativePos", "event marker $i"), maxRelativePos, "event marker $i")
                if (marker.has("endWaypointRelativePos") && !marker.get("endWaypointRelativePos").isJsonNull) {
                    val endPos = requireRelativePos(
                        requiredNumber(marker, "endWaypointRelativePos", "event marker $i"),
                        maxRelativePos,
                        "event marker $i end"
                    )
                    require(endPos >= pos) { "event marker $i end precedes its start" }
                }

                var commandName: String? = null
                if (marker.has("command") && !marker.get("command").isJsonNull) {
                    val cmd = marker.getAsJsonObject("command")
                    val typeNode = cmd.get("type")
                        ?: throw IllegalArgumentException("event marker $i command is missing type")
                    require(typeNode.isJsonPrimitive && typeNode.asJsonPrimitive.isString && typeNode.asString == "named") {
                        "event marker $i supports only a named command"
                    }
                    if (cmd.has("name") && !cmd.get("name").isJsonNull) {
                        val nameNode = cmd.get("name")
                        require(nameNode.isJsonPrimitive && nameNode.asJsonPrimitive.isString) {
                            "event marker $i command name must be a string"
                        }
                        commandName = nameNode.asString
                    }
                }
                val validatedCommandName = requireNotNull(commandName) { "event marker $i is missing a command name" }
                require(validatedCommandName.isNotBlank() && validatedCommandName.length <= 256) {
                    "event marker $i has an invalid command name"
                }
                eventMarkers.add(ParsedEventMarker(pos, validatedCommandName))
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
