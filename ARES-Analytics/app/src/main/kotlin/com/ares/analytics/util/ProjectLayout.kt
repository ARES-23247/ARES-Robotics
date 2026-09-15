package com.ares.analytics.util

import com.ares.analytics.shared.models.League
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Resolves files owned by an FTC, FRC or XRP robot project.
 *
 * FTC workspaces may point either at the Android project root (which contains
 * `TeamCode`) or directly at a desktop/simulator module. FRC deploy assets have
 * one canonical root. Keeping that distinction here prevents screens and view
 * models from silently choosing different locations for the same asset.
 */
internal object ProjectLayout {
    fun assetsDirectory(projectPath: String, league: League): File = when (league) {
        League.FTC -> {
            val teamCodeAssets = File(projectPath, "TeamCode/src/main/assets")
            teamCodeAssets.takeIf(File::isDirectory)
                ?: File(projectPath, "src/main/assets")
        }

        League.FRC -> File(projectPath, "src/main/deploy")
        League.XRP -> File(projectPath, "deploy")
    }

    /** Directory containing obstacles, game pieces, AprilTags, and field waypoints. */
    fun fieldDataDirectory(projectPath: String, league: League): File =
        File(assetsDirectory(projectPath, league), "paths")

    /** Canonical versioned field document consumed by the editor and simulators. */
    fun fieldDefinitionFile(projectPath: String, league: League): File =
        File(fieldDataDirectory(projectPath, league), "field.json")

    /** Resolves a portable field-image path inside the robot asset root. */
    fun fieldImageFile(projectPath: String, league: League, configuredPath: String?): File {
        val assets = resolveExistingParents(assetsDirectory(projectPath, league).toPath())
        val relative = configuredPath?.trim()?.takeIf(String::isNotEmpty) ?: "field_image.png"
        require(!File(relative).isAbsolute) { "Field image path must be relative to the robot asset folder" }
        val image = resolveExistingParents(assets.resolve(relative))
        require(image.startsWith(assets)) {
            "Field image path escapes the robot asset folder"
        }
        return image.toFile()
    }

    /** Resolve links in existing ancestors while still permitting a not-yet-created image path. */
    private fun resolveExistingParents(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing = absolute
        val missing = ArrayDeque<Path>()
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(existing.fileName)
            existing = existing.parent ?: throw IOException("Cannot resolve path root: $path")
        }
        var resolved = existing.toRealPath()
        for (part in missing) resolved = resolved.resolve(part)
        return resolved.normalize()
    }

    /** Returns null when the folder exists and contains league-appropriate source files. */
    fun validationError(projectPath: String, league: League): String? {
        if (projectPath.isBlank()) return "Choose the robot repository folder."
        val root = File(projectPath)
        if (!root.isDirectory) return "That folder does not exist."
        if (!containsRobotSource(root, league)) {
            return "This folder is not a complete ${league.name} robot project: no robot source was found. " +
                "Choose an existing robot repository root, or create a new project from the official ${league.name} starter."
        }
        return null
    }

    fun containsRobotSource(root: File, league: League): Boolean {
        val sourceRoots = when (league) {
            League.FTC -> listOf(
                File(root, "TeamCode/src/main/java"),
                File(root, "TeamCode/src/main/kotlin"),
                File(root, "src/main/java"),
                File(root, "src/main/kotlin")
            )
            League.FRC -> listOf(File(root, "src/main/kotlin"), File(root, "src/main/java"))
            League.XRP -> listOf(
                File(root, "build/generated/ares/python"),
                File(root, "extensions"),
                root,
            )
        }
        val visited = HashSet<Path>()
        val pending = ArrayDeque<File>()
        for (sourceRoot in sourceRoots) {
            pending.addLast(sourceRoot)
            while (pending.isNotEmpty()) {
                val directory = pending.removeLast()
                if (!directory.isDirectory) continue
                val realPath = try {
                    directory.toPath().toRealPath()
                } catch (_: IOException) {
                    continue
                }
                if (!visited.add(realPath)) continue
                val files = directory.listFiles() ?: continue
                for (file in files) {
                    if (file.isDirectory) pending.addLast(file)
                    else if (file.isFile && when (league) {
                        League.XRP -> file.extension == "py"
                        League.FTC, League.FRC -> file.extension == "kt" || file.extension == "java"
                    }) return true
                }
            }
        }
        return false
    }
}
