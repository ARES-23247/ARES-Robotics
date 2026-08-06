package com.areslib.pathing

import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
 * Dynamically parses `field_waypoints.json` from disk storage or embedded classpath resources.
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

    private val gson = Gson()
    private val waypointListType = object : TypeToken<List<FieldWaypoint>>() {}.type
    private var cachedWaypoints: Map<String, FieldWaypoint>? = null

    /**
     * Loads and returns all registered field landmark waypoints indexed by name.
     *
     * @return Map of waypoint names to [FieldWaypoint] records.
     */
    fun loadAllWaypoints(): Map<String, FieldWaypoint> {
        if (cachedWaypoints != null) return cachedWaypoints!!

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

        if (jsonString == null) {
            System.err.println("WARN: field_waypoints.json not found in any standard directory or classpath.")
            return emptyMap()
        }

        return try {
            val list: List<FieldWaypoint> = gson.fromJson(jsonString, waypointListType)
            val map = list.associateBy { it.name }
            cachedWaypoints = map
            map
        } catch (e: Exception) {
            System.err.println("ERROR: Failed to parse field_waypoints.json: ${e.message}")
            emptyMap()
        }
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
        cachedWaypoints = null
    }
}

