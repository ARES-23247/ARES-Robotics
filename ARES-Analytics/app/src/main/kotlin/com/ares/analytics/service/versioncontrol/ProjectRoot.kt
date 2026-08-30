package com.ares.analytics.service.versioncontrol

import java.io.File

/** Resolves the one current canonical ARES project root contract. */
internal fun requireCanonicalProjectRoot(projectPath: String): File {
    require(projectPath.isNotBlank()) { "Choose a robot project first." }
    val root = File(projectPath).canonicalFile
    require(root.isDirectory) { "The selected robot project folder does not exist." }
    require(File(root, ".ares/project.json").isFile) {
        "The selected folder is not a canonical ARES robot project."
    }
    return root
}
