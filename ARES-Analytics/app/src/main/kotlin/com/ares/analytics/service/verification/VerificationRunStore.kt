package com.ares.analytics.service.verification

import com.ares.analytics.BuildConfig
import com.ares.analytics.shared.AppJson
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

const val VERIFICATION_RESULT_SCHEMA_VERSION: Int = 1

@Serializable
data class VerificationRunProvenance(
    val schemaVersion: Int = VERIFICATION_RESULT_SCHEMA_VERSION,
    val runId: String,
    val canonicalContentHash: String,
    val aresVersion: String,
    val generatorVersion: String,
    val studioVersion: String,
    val gitRevision: String? = null,
    val command: List<String>,
    val startedAt: String,
    val finishedAt: String,
    val buildExitCode: Int,
)

data class PendingVerificationRun(
    val runId: String,
    val canonicalContentHash: String,
    val aresVersion: String,
    val generatorVersion: String,
    val studioVersion: String,
    val gitRevision: String?,
    val command: List<String>,
    val startedAt: String,
)

/**
 * Owns immutable, run-scoped verification evidence under `.ares/local/verification`.
 * JUnit XML is an input adapter for one build only; Studio reloads the normalized JSON result.
 */
object VerificationRunStore {
    fun begin(projectRoot: File, command: List<String>, aresVersion: String): PendingVerificationRun =
        PendingVerificationRun(
            runId = UUID.randomUUID().toString(),
            canonicalContentHash = canonicalContentHash(projectRoot),
            aresVersion = aresVersion,
            generatorVersion = aresVersion,
            studioVersion = BuildConfig.VERSION,
            gitRevision = resolveGitRevision(projectRoot),
            command = command.toList(),
            startedAt = Instant.now().toString(),
        )

    fun complete(pending: PendingVerificationRun, buildExitCode: Int): VerificationRunProvenance =
        VerificationRunProvenance(
            runId = pending.runId,
            canonicalContentHash = pending.canonicalContentHash,
            aresVersion = pending.aresVersion,
            generatorVersion = pending.generatorVersion,
            studioVersion = pending.studioVersion,
            gitRevision = pending.gitRevision,
            command = pending.command,
            startedAt = pending.startedAt,
            finishedAt = Instant.now().toString(),
            buildExitCode = buildExitCode,
        )

    fun saveAndReload(projectRoot: File, report: RobotVerificationReport): RobotVerificationReport {
        require(report.provenance.canonicalContentHash == canonicalContentHash(projectRoot)) {
            "Canonical .ares documents changed during verification. Run Verify & build again."
        }
        val directory = File(projectRoot, ".ares/local/verification/${report.provenance.runId}")
        check(directory.mkdirs() || directory.isDirectory) { "Could not create verification result directory." }
        val target = File(directory, "report.json")
        val encoded = AppJson.encodeToString(report)
        val temporary = File(directory, "report.json.partial-${UUID.randomUUID()}")
        temporary.writeText(encoded)
        check(temporary.renameTo(target)) { "Could not publish the run-scoped verification result." }
        return AppJson.decodeFromString(target.readText())
    }

    fun canonicalContentHash(projectRoot: File): String {
        val root = File(projectRoot, ".ares").canonicalFile
        require(root.isDirectory) { "Canonical .ares project documents are missing." }
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown()
            .filter(File::isFile)
            .map { file -> root.toPath().relativize(file.toPath()).toString().replace('\\', '/') to file }
            .filterNot { (path, _) ->
                path.startsWith("history/") ||
                    path.startsWith("recovery/") ||
                    path.startsWith("local/") ||
                    path.startsWith("evidence/")
            }
            .sortedBy { it.first }
            .forEach { (path, file) ->
                digest.update(path.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(file.readBytes())
                digest.update(0.toByte())
            }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun resolveGitRevision(projectRoot: File): String? {
        val dotGit = File(projectRoot, ".git")
        val gitDir = when {
            dotGit.isDirectory -> dotGit
            dotGit.isFile -> dotGit.readText().trim().removePrefix("gitdir:").trim()
                .let { path -> File(projectRoot, path).canonicalFile }
            else -> return null
        }
        val head = File(gitDir, "HEAD").takeIf(File::isFile)?.readText()?.trim() ?: return null
        if (!head.startsWith("ref:")) return head.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }
        val ref = head.removePrefix("ref:").trim()
        return File(gitDir, ref).takeIf(File::isFile)?.readText()?.trim()
            ?: File(gitDir, "packed-refs").takeIf(File::isFile)?.useLines { lines ->
                lines.firstOrNull { it.endsWith(" $ref") }?.substringBefore(' ')
            }
    }
}
