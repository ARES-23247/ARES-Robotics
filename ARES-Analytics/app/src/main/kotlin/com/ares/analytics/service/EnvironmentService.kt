package com.ares.analytics.service

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.shared.models.AppWorkspaces
import com.areslib.project.AresProjectMetadataCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Workspace and environment configuration management service.
 *
 * Manages active team workspaces, season identifiers, robot configurations, and league settings across FTC and FRC.
 *
 * ### Configuration Data:
 * - Active workspace ID mapping
 * - League enum configuration ([League.FTC] / [League.FRC])
 * - Team number, season ID, and robot hardware ID metadata
 *
 * ### Thread Safety & Performance Guarantees:
 * All file read/write operations execute asynchronously on `Dispatchers.IO`. Thread-safe.
 *
 * @param workspacesPath Multi-workspace configuration JSON path (`~/.ares-analytics/workspaces.json`).
 *
 * @see com.ares.analytics.shared.models.AppWorkspaces
 * @see com.ares.analytics.shared.models.WorkspaceConfig
 */
class EnvironmentService(
    private val workspacesPath: String = AppDataPaths.file("workspaces.json").path,
    private val secretsWriter: (File, ByteArray) -> Unit = ::writeSecrets,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun loadWorkspaces(): AppWorkspaces = withContext(Dispatchers.IO) {
        val file = File(workspacesPath)

        if (file.exists()) {
            val saved = try {
                json.decodeFromString<AppWorkspaces>(file.readText())
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            if (saved != null) {
                val resolved = saved.copy(
                    workspaces = saved.workspaces
                        .map(::synchronizeCanonicalIdentity),
                )
                if (resolved != saved) {
                    secretsWriter(file, json.encodeToString(resolved).toByteArray(Charsets.UTF_8))
                }
                return@withContext resolved
            }
        }

        AppWorkspaces(activeWorkspaceId = null, workspaces = emptyList())
    }

    /**
     * Workspace identity fields are compatibility/display caches only. Whenever a canonical
     * project exists, .ares/project.json wins and the cache is refreshed before use or save.
     */
    private fun synchronizeCanonicalIdentity(config: WorkspaceConfig): WorkspaceConfig {
        val identity = readProjectIdentityBlocking(config.projectPath) ?: return config
        return config.copy(
            teamId = identity.teamId,
            seasonId = identity.seasonId,
            robotId = identity.robotId,
            robotName = identity.name,
            league = identity.league.toLeague(),
        )
    }

    suspend fun saveWorkspaces(appWorkspaces: AppWorkspaces) = withContext(Dispatchers.IO) {
        val file = File(workspacesPath)
        // workspaces.json holds secrets (googleClientSecret, geminiApiKey, toaApiKey,
        // vertexServiceAccountPath) → restrict to owner-only via writeSecrets (AUDIT H2).
        secretsWriter(file, json.encodeToString(appWorkspaces).toByteArray(Charsets.UTF_8))
    }

    suspend fun loadConfig(): WorkspaceConfig? {
        val app = loadWorkspaces()
        val baseConfig = app.workspaces.find { it.id == app.activeWorkspaceId } ?: app.workspaces.firstOrNull()
        if (baseConfig != null) {
            val projectIdentity = readProjectIdentity(baseConfig.projectPath)
            if (projectIdentity != null) {
                return baseConfig.copy(
                    teamId = projectIdentity.teamId,
                    seasonId = projectIdentity.seasonId,
                    robotId = projectIdentity.robotId,
                    robotName = projectIdentity.name,
                    league = projectIdentity.league.toLeague()
                )
            }
        }
        return baseConfig
    }

    suspend fun saveConfig(config: WorkspaceConfig) {
        val app = loadWorkspaces()
        val canonicalConfig = synchronizeCanonicalIdentity(config)
        val configWithId = if (canonicalConfig.id.isEmpty()) {
            canonicalConfig.copy(id = "${canonicalConfig.league}-${canonicalConfig.teamId}-${canonicalConfig.robotId}-${canonicalConfig.seasonId}")
        } else {
            canonicalConfig
        }
        val newList = app.workspaces.filter { it.id != configWithId.id } + configWithId
        saveWorkspaces(AppWorkspaces(activeWorkspaceId = configWithId.id, workspaces = newList))
    }

    suspend fun verifyJavaEnvironment(): JavaEnvResult = withContext(Dispatchers.IO) {
        val javaExe = ManagedToolchainPaths.javaExecutable()?.path ?: "java"

        if (javaExe != "java" && !File(javaExe).exists()) {
            return@withContext JavaEnvResult(false, "java executable not found at $javaExe")
        }

        try {
            val process = ProcessBuilder(javaExe, "-version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext JavaEnvResult(false, "Java verification timed out.")
            }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                JavaEnvResult(true, "Java executable valid. Output:\n$output")
            } else {
                JavaEnvResult(false, "Java version execution failed with exit code $exitCode. Output:\n$output")
            }
        } catch (e: Exception) {
            JavaEnvResult(false, "Failed to run Java verification: ${e.message}")
        }
    }

    suspend fun detectLeague(projectPath: String): League = withContext(Dispatchers.IO) {
        val root = File(projectPath)
        if (!root.exists() || !root.isDirectory) return@withContext League.FTC

        readProjectIdentityBlocking(projectPath)?.let { return@withContext it.league.toLeague() }

        if (File(root, "main.py").isFile && File(root, "ares_micro").isDirectory) {
            return@withContext League.XRP
        }

        // Look for typical FRC indicators: build.gradle/settings.gradle mentioning 'frc', or 'wpilibj'
        // or a build.gradle with wpilib dependency.
        val searchFiles = root.walkTopDown().maxDepth(3)
        for (file in searchFiles) {
            if (file.name == "build.gradle" || file.name == "build.gradle.kts") {
                val content = file.readText()
                if (content.contains("edu.wpi.first") || content.contains("wpilibj")) {
                    return@withContext League.FRC
                }
            }
        }

        // Default to FTC (the workspace features TeamCode/ARESLib-Kotlin)
        League.FTC
    }

    fun getDefaultNt4Host(league: League, teamId: String): String {
        return when (league) {
            League.FTC -> "192.168.43.1"
            League.FRC -> {
                // FRC team host convention: 10.TE.AM.2
                val teamNumber = teamId.filter { it.isDigit() }
                if (teamNumber.length in 1..4) {
                    val padded = teamNumber.padStart(4, '0')
                    val te = padded.substring(0, 2).toInt()
                    val am = padded.substring(2, 4).toInt()
                    "10.$te.$am.2"
                } else {
                    "10.0.0.2"
                }
            }
            League.XRP -> "192.168.4.1"
        }
    }

    /** Reads the one canonical project identity. Unsupported schemas fail closed. */
    suspend fun readProjectIdentity(projectPath: String): DetectedProjectIdentity? = withContext(Dispatchers.IO) {
        readProjectIdentityBlocking(projectPath)
    }

    private fun readProjectIdentityBlocking(projectPath: String): DetectedProjectIdentity? {
        val canonical = File(projectPath, ".ares/project.json")
        if (canonical.isFile) {
            runCatching { AresProjectMetadataCodec.decode(canonical.readText()) }.getOrNull()?.let { project ->
                return DetectedProjectIdentity(
                    teamId = project.identity.teamId,
                    seasonId = project.identity.seasonId,
                    robotId = project.identity.robotId,
                    name = project.identity.displayName,
                    league = project.league.name,
                )
            }
        }
        return null
    }
}

private fun String.toLeague(): League = when (uppercase()) {
    "FTC" -> League.FTC
    "FRC" -> League.FRC
    "XRP" -> League.XRP
    else -> throw IllegalArgumentException("Unsupported robot league '$this'")
}

data class JavaEnvResult(
    val isValid: Boolean,
    val message: String
)

@kotlinx.serialization.Serializable
data class DetectedProjectIdentity(
    val teamId: String,
    val seasonId: String,
    val robotId: String,
    val name: String = "",
    val league: String = "FTC",
)

/**
 * Atomically writes [bytes] to [file] through a force-flushed sibling temporary file, then
 * best-effort restricts it to owner-only `rw-------` (AUDIT H2). Used for every file that holds secrets — `workspaces.json` /
 * `config.json` (googleClientSecret, geminiApiKey, toaApiKey, vertexServiceAccountPath)
 * and `auth.json` (OAuth tokens). The permission step is POSIX-only; it is skipped on Windows.
 * Write and atomic-replace failures propagate to the caller and never truncate the prior file.
 */
fun writeSecrets(
    file: File,
    bytes: ByteArray,
    beforeReplace: BeforeAtomicReplace = NO_OP_BEFORE_ATOMIC_REPLACE,
) {
    writeFileAtomically(file, beforeReplace) { temporary ->
        try {
            Files.setPosixFilePermissions(
                temporary.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            )
        } catch (e: UnsupportedOperationException) {
            // Windows / non-POSIX FS — no action possible.
        }
        Files.write(
            temporary.toPath(),
            bytes,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }
}
