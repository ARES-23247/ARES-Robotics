package com.ares.analytics.service

import java.io.File

/**
 * Owns the local ARES Robotics Studio data boundary.
 *
 * Normal installations use `~/.ares-analytics`. Automated desktop journeys must set
 * [DATA_DIRECTORY_PROPERTY] or [DATA_DIRECTORY_ENVIRONMENT] to an isolated directory so test
 * workspaces, credentials, layouts, logs, and databases never enter a student's real profile.
 */
object AppDataPaths {
    const val DATA_DIRECTORY_PROPERTY = "ares.analytics.dataDir"
    const val DATA_DIRECTORY_ENVIRONMENT = "ARES_ANALYTICS_DATA_DIR"

    fun rootDirectory(): File = resolveRootDirectory(
        configuredProperty = System.getProperty(DATA_DIRECTORY_PROPERTY),
        configuredEnvironment = System.getenv(DATA_DIRECTORY_ENVIRONMENT),
        userHome = System.getProperty("user.home"),
    )

    fun file(relativePath: String): File {
        require(relativePath.isNotBlank()) { "ARES app-data path must not be blank" }
        val relative = File(relativePath)
        require(!relative.isAbsolute && relative.toPath().none { it.toString() == ".." }) {
            "ARES app-data path must stay inside the configured data directory"
        }
        return File(rootDirectory(), relativePath)
    }

    internal fun resolveRootDirectory(
        configuredProperty: String?,
        configuredEnvironment: String?,
        userHome: String,
    ): File {
        val configured = configuredProperty?.trim()?.takeIf(String::isNotEmpty)
            ?: configuredEnvironment?.trim()?.takeIf(String::isNotEmpty)
        return configured?.let(::File)?.absoluteFile ?: File(userHome, ".ares-analytics")
    }
}
