package com.ares.analytics.mcp.engine

import com.areslib.project.AresLeague

/**
 * Shared path, slug, and Kotlin identifier validators preventing path traversal, symlink escapes,
 * and Kotlin code injection across MCP mutators, templates, and hybrid registrars.
 */
internal object McpPathAndIdentifierValidators {
    val DOC_SLUG_REGEX = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    val KOTLIN_PACKAGE_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    val KOTLIN_FQN_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
    val KOTLIN_SIMPLE_TYPE_REGEX = Regex("^[A-Z][A-Za-z0-9_]{0,63}$")

    fun validateDocumentSlug(raw: String, label: String): String {
        val trimmed = raw.trim()
        require(DOC_SLUG_REGEX.matches(trimmed)) {
            "Invalid $label identifier '$raw'; must match ${DOC_SLUG_REGEX.pattern} without path separators."
        }
        return trimmed
    }

    fun validateKotlinPackageName(raw: String, label: String = "packageName"): String {
        val trimmed = raw.trim()
        require(KOTLIN_PACKAGE_REGEX.matches(trimmed)) {
            "Invalid Kotlin $label '$raw'; expected dot-separated Kotlin package identifiers."
        }
        return trimmed
    }

    fun validateKotlinFqn(raw: String, label: String): String {
        val trimmed = raw.trim()
        require(KOTLIN_FQN_REGEX.matches(trimmed)) {
            "Invalid Kotlin $label '$raw'; expected a fully qualified Kotlin type name."
        }
        return trimmed
    }

    fun validateSimpleTypeName(raw: String, label: String = "kotlinTypeName"): String {
        val trimmed = raw.trim()
        require(KOTLIN_SIMPLE_TYPE_REGEX.matches(trimmed)) {
            "Invalid Kotlin $label '$raw'; expected a PascalCase Kotlin type identifier."
        }
        return trimmed
    }

    fun validateScaffoldRelativePath(raw: String, league: AresLeague): String {
        val normalized = raw.replace('\\', '/').trim()
        require(!normalized.startsWith("/") && !normalized.contains("..") && !normalized.contains(":")) {
            "Invalid relativePathOverride '$raw': must be a clean relative path."
        }
        val lower = normalized.lowercase()
        require(lower.endsWith(".kt") || lower.endsWith(".java")) {
            "Invalid relativePathOverride '$raw': only .kt or .java source files are allowed."
        }
        require(!lower.startsWith(".git/") && !lower.contains("/.git/") && !lower.contains("build.gradle")) {
            "Invalid relativePathOverride '$raw': cannot target repository metadata or Gradle scripts."
        }
        val allowedPrefixes = when (league) {
            AresLeague.FTC -> listOf("TeamCode/src/", "simulator/src/")
            AresLeague.FRC -> listOf("src/")
            AresLeague.XRP -> emptyList()
        }
        require(allowedPrefixes.any { normalized.startsWith(it) }) {
            "Invalid relativePathOverride '$raw': must reside under ${allowedPrefixes.joinToString(" or ")}."
        }
        return normalized
    }
}
