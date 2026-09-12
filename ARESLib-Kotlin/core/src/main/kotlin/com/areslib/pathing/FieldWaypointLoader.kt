package com.areslib.pathing

import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d

/**
 * Named Field Landmark Waypoint Definition.
 *
 * Represents a named target pose exported from the ARES-Analytics dashboard field editor.
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y)$: Field-centric meters ($m$)
 * - Heading ([headingDegrees]): Degrees ($^\circ$), converted to **CCW-positive** radians ($rad$) via [toPose].
 *
 * @property id Unique string identifier.
 * @property name Human-readable landmark name.
 * @property x Field X coordinate in meters ($m$).
 * @property y Field Y coordinate in meters ($m$).
 * @property headingDegrees Orientation heading in degrees ($^\circ$).
 * @property locked If true, waypoint is protected from dynamic dashboard edits.
 */
data class FieldWaypoint(
    val id: String,
    val name: String,
    val x: Double,
    val y: Double,
    val headingDegrees: Double,
    val locked: Boolean = false
) {
    /**
     * Converts this landmark waypoint into a field-centric 2D pose [Pose2d].
     *
     * @return Equivalent [Pose2d] in meters ($m$) and **CCW-positive** radians ($rad$).
     */
    fun toPose(): Pose2d {
        return Pose2d(x, y, Rotation2d(Math.toRadians(headingDegrees)))
    }
}

/**
 * Field Landmark Waypoint File Resolver and Cache.
 *
 * Parses `field_waypoints.json` from disk storage or embedded classpath resources. Successful,
 * missing, and invalid reads are cached until [clearCache]; preload before timing-critical motion.
 * Records require unique nonblank IDs/names and explicit finite X/Y/heading values. An invalid
 * file yields an empty map rather than a partially usable or ambiguous set of motion targets.
 */
object FieldWaypointLoader {
    private val SEARCH_PATHS = listOf(
        "/sdcard/FIRST/tuning/paths",
        "/sdcard/FIRST/paths",
        "src/main/deploy/paths",
        "deploy/paths",
        "src/main/resources/deploy/paths",
        "../deploy/paths",
        "../../deploy/paths",
        "src/main/assets/paths",
        "TeamCode/src/main/assets/paths",
        "../TeamCode/src/main/assets/paths",
        "../../TeamCode/src/main/assets/paths"
    )

    private val cache = FieldWaypointCache(::findJson)

    /** Loads an immutable waypoint map. Missing/invalid files stay empty until [clearCache]. */
    fun loadAllWaypoints(): Map<String, FieldWaypoint> = cache.load()

    private fun findJson(): String? {
        var jsonString: String? = null
        val fileName = "field_waypoints.json"

        // 1. Filesystem search
        for (dirPath in SEARCH_PATHS) {
            val file = File(dirPath, fileName)
            if (file.exists() && file.isFile) {
                try {
                    jsonString = file.readText(Charsets.UTF_8)
                    break
                } catch (e: Exception) {
                    System.err.println("WARN: Failed to read field waypoints at ${file.absolutePath}: ${e.message}")
                }
            }
        }

        // 2. Classpath search
        if (jsonString == null) {
            val classpathCandidates = listOf(
                "/deploy/paths/$fileName",
                "deploy/paths/$fileName",
                "/assets/paths/$fileName",
                "assets/paths/$fileName"
            )
            for (resourcePath in classpathCandidates) {
                try {
                    val stream = FieldWaypointLoader::class.java.getResourceAsStream(resourcePath)
                    if (stream != null) {
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                            jsonString = reader.readText()
                        }
                        break
                    }
                } catch (e: Exception) {
                    // Ignore and try next resource path
                }
            }
        }

        return jsonString
    }

    /**
     * Retrieves a single field landmark waypoint by name.
     *
     * @param name Name of target waypoint.
     * @return Matching [FieldWaypoint], or `null` if not found.
     */
    fun getWaypoint(name: String): FieldWaypoint? {
        return loadAllWaypoints()[name]
    }

    /**
     * Clears internal waypoint memory cache, forcing next read pass to reload from disk.
     */
    fun clearCache() {
        cache.clear()
    }
}


/** One immutable snapshot per explicit load/reload, including unsuccessful resolution. */
internal class FieldWaypointCache(private val readJson: () -> String?) {
    @Volatile private var snapshot: Map<String, FieldWaypoint>? = null

    fun load(): Map<String, FieldWaypoint> = snapshot ?: loadUncached()

    @Synchronized fun clear() { snapshot = null }

    @Synchronized private fun loadUncached(): Map<String, FieldWaypoint> {
        snapshot?.let { return it }
        val loaded = try {
            val json = readJson()
            if (json == null) {
                System.err.println("WARN: field_waypoints.json not found in any standard directory or classpath.")
                emptyMap()
            } else {
                val root = JsonParser.parseString(json)
                require(root.isJsonArray) { "Expected an array of waypoints" }
                val names = LinkedHashMap<String, FieldWaypoint>()
                val ids = HashSet<String>()
                for (element in root.asJsonArray) {
                    require(element.isJsonObject) { "Each waypoint must be an object" }
                    val item = element.asJsonObject
                    val id = requiredString(item, "id")
                    val name = requiredString(item, "name")
                    require(ids.add(id) && !names.containsKey(name)) { "Waypoint IDs and names must be unique" }
                    val lock = item.get("locked")
                    require(lock == null || (lock.isJsonPrimitive && lock.asJsonPrimitive.isBoolean)) {
                        "Waypoint locked must be boolean"
                    }
                    names[name] = FieldWaypoint(id, name,
                        requiredNumber(item, "x"), requiredNumber(item, "y"),
                        requiredNumber(item, "headingDegrees"), lock?.asBoolean ?: false)
                }
                Collections.unmodifiableMap(names)
            }
        } catch (failure: Exception) {
            System.err.println("ERROR: Failed to load field_waypoints.json: ${failure.message}")
            emptyMap()
        }
        snapshot = loaded
        return loaded
    }

    private fun requiredString(item: JsonObject, key: String): String {
        val value = item.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "Waypoint $key must be a string"
        }
        return value.asString.also { require(it.isNotBlank()) { "Waypoint $key must not be blank" } }
    }

    private fun requiredNumber(item: JsonObject, key: String): Double {
        val value = item.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "Waypoint $key must be an explicit number"
        }
        return value.asDouble.also { require(it.isFinite()) { "Waypoint $key must be finite" } }
    }
}
