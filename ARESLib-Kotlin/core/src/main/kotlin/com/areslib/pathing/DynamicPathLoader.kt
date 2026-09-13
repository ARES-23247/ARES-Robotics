package com.areslib.pathing

import com.areslib.sequencer.Task
import com.areslib.state.Alliance
import java.io.File
import java.io.IOException
import java.io.Reader

/**
 * Loads UTF-8 PathPlanner assets from robot/development filesystem locations, then classpath
 * resources. Loading and motion profiling belong to autonomous setup, not the periodic loop.
 * Each call returns fresh data, so edits are visible and mutable paths are not shared by a cache.
 */
object DynamicPathLoader {
    private val SEARCH_PATHS = listOf(
        "/sdcard/FIRST/tuning/paths",
        "/sdcard/FIRST/paths",
        "src/main/deploy/pathplanner/paths",
        "deploy/pathplanner/paths",
        "src/main/resources/deploy/pathplanner/paths",
        "../deploy/pathplanner/paths",
        "../../deploy/pathplanner/paths",
        "src/main/assets/pathplanner/paths",
        "TeamCode/src/main/assets/pathplanner/paths",
        "../TeamCode/src/main/assets/pathplanner/paths",
        "../../TeamCode/src/main/assets/pathplanner/paths"
    )
    private val AUTO_SEARCH_PATHS = SEARCH_PATHS.map { it.removeSuffix("/paths") + "/autos" }
    private const val MAX_ASSET_NAME_LENGTH = 128
    private const val MAX_JSON_CHARACTERS = 4_194_304

    /** Searches a single asset name (without extension), then parses a fresh trajectory in meters. */
    fun loadPath(pathName: String): Path =
        PathPlannerParser.parsePath(loadJson(pathName, "path", SEARCH_PATHS))

    /** Searches a single auto name (without extension) and returns its UTF-8 JSON. */
    fun loadAutoJsonString(autoName: String): String = loadJson(autoName, "auto", AUTO_SEARCH_PATHS)

    /** Loads and compiles an auto for the explicitly selected alliance. */
    fun loadAuto(autoName: String, follower: HolonomicPathFollower, timestampMs: Long, alliance: Alliance = Alliance.BLUE): Task =
        PathPlannerAutoParser.parseAuto(loadAutoJsonString(autoName), follower, timestampMs, alliance)

    private fun loadJson(name: String, kind: String, searchPaths: List<String>): String {
        validateAssetName(name, kind)
        val fileName = "$name.$kind"
        for (directory in searchPaths) {
            val file = resolveContainedFile(directory, fileName) ?: continue
            if (file.isFile) {
                try {
                    return file.reader(Charsets.UTF_8).use(::readBounded)
                } catch (failure: IOException) {
                    System.err.println("WARN: Failed to read filesystem $kind at ${file.absolutePath}: ${failure.message}")
                }
            }
        }
        val resources = listOf("/deploy/pathplanner/${kind}s/$fileName", "/$fileName", "deploy/pathplanner/${kind}s/$fileName")
        for (resource in resources) {
            try {
                val stream = javaClass.getResourceAsStream(resource) ?: continue
                return stream.reader(Charsets.UTF_8).use(::readBounded)
            } catch (failure: IOException) {
                System.err.println("WARN: Failed to read classpath resource at $resource: ${failure.message}")
            }
        }
        val locations = searchPaths.map { File(it, fileName).absolutePath } + resources
        throw IOException("Could not locate $kind '$name' anywhere in search space!\nScanned locations:\n" +
            locations.joinToString("\n") { "  - $it" })
    }

    /** Caps input before a read can allocate an arbitrarily large string; malformed assets fail in place. */
    private fun readBounded(reader: Reader): String {
        val buffer = CharArray(8192)
        val text = StringBuilder()
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) return text.toString()
            require(text.length <= MAX_JSON_CHARACTERS - count) { "PathPlanner asset exceeds $MAX_JSON_CHARACTERS characters" }
            text.append(buffer, 0, count)
        }
    }

    private fun validateAssetName(name: String, kind: String) {
        require(name.isNotBlank()) { "PathPlanner $kind name must not be blank" }
        require(name.length <= MAX_ASSET_NAME_LENGTH) { "PathPlanner $kind name is too long" }
        require(name != "." && !name.contains("..")) { "PathPlanner $kind name must not contain traversal segments" }
        require(name.none { it == '/' || it == '\\' || it == ':' || it.code < 0x20 }) {
            "PathPlanner $kind name must be a single safe file name"
        }
    }

    private fun resolveContainedFile(directory: String, fileName: String): File? = try {
        val base = File(directory).canonicalFile
        val candidate = File(base, fileName).canonicalFile
        if (candidate.toPath().startsWith(base.toPath())) candidate else null
    } catch (_: IOException) {
        null
    }
}
