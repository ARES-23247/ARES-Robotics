package com.ares.analytics.util

import com.ares.analytics.shared.models.League
import java.io.File

/**
 * Resolves files owned by an FTC or FRC robot project.
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
        val assets = assetsDirectory(projectPath, league).canonicalFile
        val relative = configuredPath?.trim()?.takeIf(String::isNotEmpty) ?: "field_image.png"
        require(!File(relative).isAbsolute) { "Field image path must be relative to the robot asset folder" }
        val image = File(assets, relative).canonicalFile
        require(image.toPath().startsWith(assets.toPath())) {
            "Field image path escapes the robot asset folder"
        }
        return image
    }

    /** Returns null only when [projectPath] is a usable robot source repository. */
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
            League.XRP -> listOf(File(root, "src"), File(root, "."))
        }
        return sourceRoots.any { sourceRoot ->
            sourceRoot.isDirectory && sourceRoot.walkTopDown().any { file ->
                file.isFile && (file.extension == "kt" || file.extension == "java" || file.extension == "py")
            }
        }
    }
}
