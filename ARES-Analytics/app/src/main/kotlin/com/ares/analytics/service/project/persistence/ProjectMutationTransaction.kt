package com.ares.analytics.service.project.persistence

import com.ares.analytics.service.resolveExistingPath
import com.ares.analytics.util.Sha256
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Recoverable transaction envelope for commands that update more than one canonical document.
 *
 * The baseline for each declared scope is copied beneath `.ares/recovery/transactions` before the
 * first repository write. A process failure restores that baseline immediately. If the desktop is
 * terminated between writes, the absence of `COMMITTED` causes the next ProjectSession load to
 * restore the baseline before decoding any project document. A committed marker makes the new
 * files authoritative even if cleanup itself was interrupted.
 */
internal object ProjectMutationTransaction {
    private const val MANIFEST = "manifest.tsv"
    private const val COMMITTED = "COMMITTED"
    private const val BACKUP = "baseline"
    private const val MANIFEST_HEADER = "V\t2\n"

    private data class RecoveryManifest(val scopes: List<String>, val baseline: Set<String>)

    fun <T> run(
        projectRoot: File,
        operation: String,
        relativeScopes: Collection<String>,
        block: () -> T,
    ): T {
        val root = projectRoot.canonicalFile
        require(root.isDirectory) { "Project directory does not exist: ${root.path}" }
        val lockTarget = File(root, ".ares/.project-mutation-transaction")
        return ProjectDocumentWriteLocks.withLock(lockTarget) {
            recover(root)
            val scopes = relativeScopes.map(::normalizeRelativePath).distinct().sorted()
            require(scopes.isNotEmpty()) { "A project transaction must declare at least one canonical scope." }
            val transaction = File(
                root,
                ".ares/recovery/transactions/${operation.safeSegment()}-${UUID.randomUUID()}",
            )
            val baseline = baselineFiles(root, scopes).onEach { relative ->
                require(normalizeRelativePath(relative) == relative) {
                    "Project transaction path cannot be represented in its recovery manifest: $relative"
                }
            }
            validateBaselineScope(root, scopes, baseline)
            transaction.mkdirs()
            baseline.forEach { relative ->
                val source = resolveInside(root, relative)
                val backup = File(transaction, "$BACKUP/$relative")
                backup.parentFile.mkdirs()
                Files.copy(source.toPath(), backup.toPath())
            }
            AtomicProjectFileWriter.write(
                File(transaction, MANIFEST),
                encodeManifest(scopes, baseline),
                replaceExisting = false,
            )

            try {
                block().also {
                    AtomicProjectFileWriter.write(File(transaction, COMMITTED), "committed\n", replaceExisting = false)
                    transaction.deleteRecursively()
                }
            } catch (failure: Throwable) {
                runCatching { restore(root, transaction) }
                    .onFailure { recoveryFailure -> failure.addSuppressed(recoveryFailure) }
                throw failure
            }
        }
    }

    /** Restores every incomplete transaction before a canonical project is read. */
    fun recover(projectRoot: File) {
        val root = projectRoot.canonicalFile
        // Another session can load while a save is active. Its journal is recoverable only after
        // that owner releases the same project lock; otherwise recovery undoes a live mutation.
        ProjectDocumentWriteLocks.withLock(File(root, ".ares/.project-mutation-transaction")) {
            val transactions = File(root, ".ares/recovery/transactions")
            transactions.listFiles(File::isDirectory).orEmpty().sortedBy(File::getName).forEach { transaction ->
                if (File(transaction, COMMITTED).isFile) {
                    transaction.deleteRecursively()
                } else {
                    restore(root, transaction)
                }
            }
        }
    }

    private fun restore(root: File, transaction: File) {
        val manifest = File(transaction, MANIFEST)
        require(manifest.isFile) {
            "Incomplete project transaction '${transaction.name}' has no recovery manifest. Preserve it for manual recovery."
        }
        val (scopes, baseline) = decodeManifest(root, manifest.readText())

        baseline.forEach { relative ->
            val backup = File(transaction, "$BACKUP/$relative")
            require(backup.isFile) { "Project transaction backup is missing '$relative'." }
        }
        currentFiles(root, scopes)
            .filterNot(baseline::contains)
            .sortedDescending()
            .forEach { relative -> Files.deleteIfExists(resolveInside(root, relative).toPath()) }
        baseline.sorted().forEach { relative ->
            val backup = File(transaction, "$BACKUP/$relative")
            AtomicProjectFileWriter.write(resolveInside(root, relative), backup.readBytes(), replaceExisting = true)
        }
        transaction.deleteRecursively()
    }

    private fun encodeManifest(scopes: List<String>, baseline: List<String>): String {
        val body = buildString {
            append(MANIFEST_HEADER)
            scopes.forEach { append("S\t").append(it).append('\n') }
            baseline.forEach { append("F\t").append(it).append('\n') }
        }
        return body + "H\t${Sha256.hex(body)}\n"
    }

    private fun decodeManifest(root: File, raw: String): RecoveryManifest {
        // Exclusive publication can fall back to CREATE_NEW on providers without hard links.
        // Validate the complete new-format file before treating any prefix as a rollback plan.
        val entries = if (raw.startsWith("V\t")) {
            require(raw.startsWith(MANIFEST_HEADER)) { "Unsupported project transaction manifest version." }
            val checksumStart = raw.lastIndexOf("\nH\t") + 1
            require(checksumStart >= MANIFEST_HEADER.length) { "Incomplete project transaction manifest." }
            val body = raw.substring(0, checksumStart)
            require(raw.substring(checksumStart) == "H\t${Sha256.hex(body)}\n") {
                "Project transaction manifest checksum is invalid or incomplete. Preserve it for manual recovery."
            }
            body.removePrefix(MANIFEST_HEADER)
        } else {
            // Preserve recovery of transactions written before versioned manifests were introduced.
            raw
        }
        val scopes = mutableListOf<String>()
        val baseline = linkedSetOf<String>()
        entries.lineSequence().filter(String::isNotEmpty).forEach { line ->
            require(line.startsWith("S\t") || line.startsWith("F\t")) {
                "Invalid project transaction manifest record."
            }
            val relative = normalizeRelativePath(line.substring(2))
            if (line.startsWith("S\t")) scopes += relative else baseline += relative
        }
        require(scopes.isNotEmpty()) { "Project transaction manifest has no declared recovery scopes." }
        validateBaselineScope(root, scopes, baseline)
        return RecoveryManifest(scopes, baseline)
    }

    private fun validateBaselineScope(root: File, scopes: List<String>, baseline: Collection<String>) {
        val realRoot = root.toPath().toRealPath()
        fun ownedPath(relative: String) = resolveExistingPath(resolveInside(root, relative).toPath()).also { path ->
            require(path.startsWith(realRoot)) { "Project transaction path escapes the real project root: $relative" }
        }
        val scopePaths = scopes.map(::ownedPath)
        baseline.forEach { relative ->
            val path = ownedPath(relative)
            require(scopePaths.any(path::startsWith)) { "Project transaction baseline is outside its declared scopes: $relative" }
        }
    }

    private fun baselineFiles(root: File, scopes: List<String>): List<String> = currentFiles(root, scopes)

    private fun currentFiles(root: File, scopes: List<String>): List<String> = scopes.flatMap { relative ->
        val target = resolveInside(root, relative)
        when {
            target.isFile -> listOf(target.relativeTo(root).invariantSeparatorsPath)
            target.isDirectory -> target.walkTopDown()
                .filter(File::isFile)
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
            else -> emptyList()
        }
    }.distinct().sorted()

    private fun resolveInside(root: File, relative: String): File = File(root, relative).canonicalFile.also { resolved ->
        require(resolved.toPath().startsWith(root.toPath())) { "Project transaction path escapes the project root: $relative" }
    }

    private fun normalizeRelativePath(value: String): String {
        require(value.none { it == '\t' || it == '\r' || it == '\n' }) {
            "Project transaction paths must not contain manifest delimiters."
        }
        val normalized = value.replace('\\', '/').trim().trimStart('/')
        require(normalized.isNotBlank() && normalized != ".") { "Project transaction scope is empty." }
        require(normalized.split('/').none { it == ".." || it.isBlank() }) { "Invalid project transaction scope: $value" }
        return normalized
    }

    private fun String.safeSegment(): String = lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .ifBlank { "mutation" }
}
