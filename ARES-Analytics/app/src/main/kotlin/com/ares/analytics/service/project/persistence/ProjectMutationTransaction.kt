package com.ares.analytics.service.project.persistence

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
            val baseline = baselineFiles(root, scopes)
            transaction.mkdirs()
            baseline.forEach { relative ->
                val source = resolveInside(root, relative)
                val backup = File(transaction, "$BACKUP/$relative")
                backup.parentFile.mkdirs()
                Files.copy(source.toPath(), backup.toPath())
            }
            AtomicProjectFileWriter.write(
                File(transaction, MANIFEST),
                buildString {
                    scopes.forEach { append("S\t").appendLine(it) }
                    baseline.forEach { append("F\t").appendLine(it) }
                },
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
        val transactions = File(root, ".ares/recovery/transactions")
        transactions.listFiles(File::isDirectory).orEmpty().sortedBy(File::getName).forEach { transaction ->
            if (File(transaction, COMMITTED).isFile) {
                transaction.deleteRecursively()
            } else {
                restore(root, transaction)
            }
        }
    }

    private fun restore(root: File, transaction: File) {
        val manifest = File(transaction, MANIFEST)
        require(manifest.isFile) {
            "Incomplete project transaction '${transaction.name}' has no recovery manifest. Preserve it for manual recovery."
        }
        val lines = manifest.readLines()
        val scopes = lines.filter { it.startsWith("S\t") }.map { normalizeRelativePath(it.substring(2)) }
        val baseline = lines.filter { it.startsWith("F\t") }.map { normalizeRelativePath(it.substring(2)) }.toSet()
        require(scopes.isNotEmpty()) { "Project transaction '${transaction.name}' has no declared recovery scopes." }

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
