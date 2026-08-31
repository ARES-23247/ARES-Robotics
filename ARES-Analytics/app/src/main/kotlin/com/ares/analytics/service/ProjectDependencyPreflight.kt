package com.ares.analytics.service

import java.io.File

private const val RETIRED_ARES_REPOSITORY =
    "https://raw.githubusercontent.com/ARES-23247/ARESLib-Kotlin/maven"
private const val CURRENT_ARES_REPOSITORY =
    "https://raw.githubusercontent.com/ARES-23247/ARES-Robotics/maven"

internal data class ProjectDependencyPreflightResult(
    val pinnedVersion: String?,
    val expectedVersion: String?,
    val problems: List<String>,
) {
    val isCompatible: Boolean get() = problems.isEmpty()

    fun requireCompatible() {
        require(isCompatible) {
            buildString {
                appendLine("This robot project is not compatible with the current ARES runtime:")
                problems.forEach { appendLine("- $it") }
                append(
                    "Export a current standalone project or update the project's pinned ARES dependency and " +
                        "USER-OWNED extensions together. Studio will not silently mix incompatible runtime APIs.",
                )
            }
        }
    }
}

/**
 * Fast, read-only dependency check that runs before Studio starts Gradle, a simulator, or deployment.
 *
 * A command-line `-ParesVersion` override can otherwise hide an older project's declared runtime until
 * Kotlin compilation fails deep inside generated or USER-OWNED code. ARES intentionally does not maintain
 * compatibility shims for unreleased projects, so mismatches fail here with a direct migration path.
 */
internal object ProjectDependencyPreflight {
    private val projectBuildFiles = listOf(
        "gradle.properties",
        "gradle/libs.versions.toml",
        "settings.gradle",
        "settings.gradle.kts",
        "build.gradle",
        "build.gradle.kts",
        "TeamCode/build.gradle",
        "TeamCode/build.gradle.kts",
        "simulator/build.gradle",
        "simulator/build.gradle.kts",
    )

    fun inspect(
        projectRoot: File,
        expectedVersion: String?,
        pinnedVersion: String?,
    ): ProjectDependencyPreflightResult {
        val canonicalRoot = projectRoot.canonicalFile
        val normalizedExpected = expectedVersion?.trim()?.takeIf(String::isNotEmpty)
        val normalizedPinned = pinnedVersion?.trim()?.takeIf(String::isNotEmpty)
        val problems = buildList {
            if (normalizedExpected != null && normalizedPinned == null) {
                add("The project does not pin aresVersion; this Studio requires ARES $normalizedExpected.")
            } else if (normalizedExpected != null && normalizedPinned != normalizedExpected) {
                add(
                    "The project pins ARES $normalizedPinned, while this Studio requires ARES " +
                        "$normalizedExpected.",
                )
            }

            val retiredReferences = projectBuildFiles.mapNotNull { relativePath ->
                val file = File(canonicalRoot, relativePath).canonicalFile
                if (!file.toPath().startsWith(canonicalRoot.toPath()) || !file.isFile) return@mapNotNull null
                val usesRetiredRepository = runCatching {
                    file.useLines { lines -> lines.any { it.contains(RETIRED_ARES_REPOSITORY, ignoreCase = true) } }
                }.getOrDefault(false)
                relativePath.takeIf { usesRetiredRepository }
            }
            if (retiredReferences.isNotEmpty()) {
                add(
                    "Retired ARESLib-Kotlin Maven repository reference found in " +
                        "${retiredReferences.joinToString()}; use $CURRENT_ARES_REPOSITORY.",
                )
            }
        }
        return ProjectDependencyPreflightResult(
            pinnedVersion = normalizedPinned,
            expectedVersion = normalizedExpected,
            problems = problems,
        )
    }
}
