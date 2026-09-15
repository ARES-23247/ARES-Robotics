package com.ares.analytics.service.project.persistence

import com.ares.analytics.service.forceDirectoryIfSupported
import com.ares.analytics.service.publishPreparedFileExclusively
import com.ares.analytics.service.resolveExistingPath
import com.ares.analytics.service.writeAndForceFile
import com.ares.analytics.util.Sha256
import com.areslib.project.schema.ProjectDocumentId
import com.areslib.project.schema.ProjectDocumentKind
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A bad file is reported without preventing the rest of an offline project from opening. */
data class ProjectDocumentDiagnostic(
    val kind: ProjectDocumentKind,
    val file: File,
    val message: String
)

data class ProjectDocumentListing<T>(
    val documents: List<T>,
    val diagnostics: List<ProjectDocumentDiagnostic>
)

data class ProjectRevisionSummary(
    val revision: Int,
    val contentHash: String,
    val displayName: String,
    val file: File
)

data class SavedProjectRevision<T>(
    val document: T,
    val contentHash: String,
    val currentFile: File,
    val historyFile: File,
    val createdRevision: Boolean
)

/** Hash-bound, recoverable proposal for removing one canonical project document. */
data class ProjectDocumentRemovalPlan(
    val documentId: String,
    val displayName: String,
    val revision: Int,
    val contentHash: String,
    val currentFile: File,
    val recoveryFile: File,
)

/** Evidence produced after the canonical file has been moved into project-local recovery. */
data class RemovedProjectDocument(
    val documentId: String,
    val displayName: String,
    val contentHash: String,
    val removedFile: File,
    val recoveryFile: File,
)

/**
 * Shared crash-safe mechanics for the three versioned student-authored document types.
 *
 * The current document and its immutable history checkpoint are each written through a
 * same-directory temporary file. A corrupt current file is never silently overwritten: callers
 * must repair or remove it explicitly, which preserves the evidence needed to recover student work.
 */
abstract class VersionedProjectDocumentStore<T>(
    private val kind: ProjectDocumentKind,
    private val directoryName: String,
    private val historyName: String,
    private val extension: String
) {
    protected abstract fun encode(document: T): String
    protected abstract fun decode(json: String): T
    protected abstract fun contentHash(document: T): String
    protected abstract fun documentId(document: T): String
    protected abstract fun revision(document: T): Int
    protected abstract fun displayName(document: T): String
    protected abstract fun withRevision(document: T, revision: Int, parentHash: String?): T
    protected abstract fun sameContent(previous: T, draft: T): Boolean

    fun list(projectPath: String): ProjectDocumentListing<T> {
        val paths = ProjectPathOwnership(projectPath)
        val directory = projectDirectory(paths)
        if (!directory.isDirectory) return ProjectDocumentListing(emptyList(), emptyList())

        val decodedDocuments = mutableListOf<Pair<File, T>>()
        val diagnostics = mutableListOf<ProjectDocumentDiagnostic>()
        directory.listFiles { file ->
            file.isFile && file.name.endsWith(".$extension", ignoreCase = true)
        }.orEmpty().sortedBy { it.name.lowercase() }.forEach { file ->
            runCatching {
                decode(paths.check(file).readText()).also { document -> ProjectDocumentId(documentId(document)) }
            }
                .onSuccess { document -> decodedDocuments += file to document }
                .onFailure { error ->
                    diagnostics += ProjectDocumentDiagnostic(
                        kind,
                        file,
                        error.message ?: "Document could not be decoded"
                    )
                }
        }
        val documentsById = decodedDocuments.groupBy { (_, document) -> documentId(document) }
        val documents = mutableListOf<T>()
        decodedDocuments.forEach { (file, document) ->
            val id = documentId(document)
            val fileId = file.name.substringBeforeLast('.')
            var validIdentity = true
            if (fileId != id) {
                diagnostics += ProjectDocumentDiagnostic(
                    kind,
                    file,
                    "File name '$fileId' does not match documentId '$id'",
                )
                validIdentity = false
            }
            val duplicates = documentsById.getValue(id)
            if (duplicates.size > 1) {
                diagnostics += ProjectDocumentDiagnostic(
                    kind,
                    file,
                    "Duplicate documentId '$id' appears in ${duplicates.joinToString { it.first.name }}",
                )
                validIdentity = false
            }
            if (validIdentity) documents += document
        }
        return ProjectDocumentListing(
            documents = documents.sortedWith(
                compareBy<T> { displayName(it).lowercase() }.thenBy { documentId(it) }
            ),
            diagnostics = diagnostics.sortedBy { it.file.name.lowercase() }
        )
    }

    fun load(projectPath: String, rawDocumentId: String): T {
        val id = ProjectDocumentId(rawDocumentId)
        val paths = ProjectPathOwnership(projectPath)
        val file = currentFile(paths, id)
        require(file.isFile) { "${kind.displayName} '${id.value}' does not exist" }
        return decode(file.readText()).also { document ->
            require(documentId(document) == id.value) {
                "${kind.displayName} file '${file.name}' declares documentId '${documentId(document)}'"
            }
        }
    }

    fun save(projectPath: String, draft: T): SavedProjectRevision<T> {
        // Encode first so validation fails before any directory or file is changed.
        val validatedDraft = decode(encode(draft))
        val id = ProjectDocumentId(documentId(validatedDraft))
        val paths = ProjectPathOwnership(projectPath)
        val currentFile = currentFile(paths, id)
        return ProjectDocumentWriteLocks.withLock(currentFile) {
        val previous = currentFile.takeIf(File::isFile)?.let { file ->
            decode(file.readText()).also { document ->
                require(documentId(document) == id.value) {
                    "${kind.displayName} file '${file.name}' declares documentId '${documentId(document)}'"
                }
            }
        }
        val previousHash = previous?.let(::contentHash)
        val normalized = when {
            previous == null -> withRevision(validatedDraft, revision = 1, parentHash = null)
            sameContent(previous, validatedDraft) -> previous
            else -> withRevision(validatedDraft, revision(previous) + 1, previousHash)
        }
        val encoded = encode(normalized)
        val hash = if (normalized === previous) requireNotNull(previousHash) else contentHash(normalized)
        val directory = historyDirectory(paths, id)
        if (previous != null && normalized !== previous) {
            val parentHash = requireNotNull(previousHash)
            ensureHistoryCheckpoint(
                paths.check(File(directory, historyFileName(revision(previous), parentHash, extension))),
                encode(previous), parentHash, ::decode, ::contentHash,
            )
        }
        val historyFile = paths.check(File(directory, historyFileName(revision(normalized), hash, extension)))
        val createdRevision = ensureHistoryCheckpoint(historyFile, encoded, hash, ::decode, ::contentHash)
        if (previous != normalized || !currentFile.exists()) {
            AtomicProjectFileWriter.write(currentFile, encoded, replaceExisting = true)
        }
        SavedProjectRevision(normalized, hash, currentFile, historyFile, createdRevision)
        }
    }

    /**
     * Plans a removal without changing bytes. The content hash is the confirmation token: if the
     * descriptor changes after review, [remove] refuses to move it.
     */
    fun removalPlan(projectPath: String, rawDocumentId: String): ProjectDocumentRemovalPlan {
        val id = ProjectDocumentId(rawDocumentId)
        val paths = ProjectPathOwnership(projectPath)
        val currentFile = currentFile(paths, id)
        require(currentFile.isFile) { "${kind.displayName} '${id.value}' does not exist" }
        return ProjectDocumentWriteLocks.withLock(currentFile) {
            val document = decode(currentFile.readText()).also { loaded ->
                require(documentId(loaded) == id.value) {
                    "${kind.displayName} file '${currentFile.name}' declares documentId '${documentId(loaded)}'"
                }
            }
            val hash = contentHash(document)
            ProjectDocumentRemovalPlan(
                documentId = id.value,
                displayName = displayName(document),
                revision = revision(document),
                contentHash = hash,
                currentFile = currentFile,
                recoveryFile = paths.check(File(
                    recoveryDirectory(paths, id),
                    "${revision(document).toString().padStart(4, '0')}-${hash.take(12)}.$extension",
                )),
            )
        }
    }

    /**
     * Publishes the reviewed canonical file into `.ares/recovery` before removing its current name.
     * History is retained and no source file is touched. A crash may retain both copies; a stale
     * hash fails before any filesystem mutation.
     */
    fun remove(
        projectPath: String,
        rawDocumentId: String,
        expectedContentHash: String,
    ): RemovedProjectDocument {
        require(expectedContentHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid removal confirmation hash" }
        val id = ProjectDocumentId(rawDocumentId)
        val paths = ProjectPathOwnership(projectPath)
        val currentFile = currentFile(paths, id)
        return ProjectDocumentWriteLocks.withLock(currentFile) {
            require(currentFile.isFile) { "${kind.displayName} '${id.value}' no longer exists" }
            val document = decode(currentFile.readText()).also { loaded ->
                require(documentId(loaded) == id.value) {
                    "${kind.displayName} file '${currentFile.name}' declares documentId '${documentId(loaded)}'"
                }
            }
            val currentHash = contentHash(document)
            require(currentHash == expectedContentHash) {
                "${kind.displayName.capitalizeForMessage()} '${id.value}' changed after review. Review the removal again."
            }
            val recoveryFile = paths.check(File(
                recoveryDirectory(paths, id),
                "${revision(document).toString().padStart(4, '0')}-${currentHash.take(12)}.$extension",
            ))
            recoveryFile.parentFile.mkdirs()
            if (recoveryFile.exists()) {
                require(Files.mismatch(currentFile.toPath(), recoveryFile.toPath()) == -1L) {
                    "Recovery file '${recoveryFile.name}' already exists with different contents"
                }
                Files.delete(currentFile.toPath())
            } else {
                moveWithoutReplacement(currentFile, recoveryFile)
            }
            RemovedProjectDocument(
                documentId = id.value,
                displayName = displayName(document),
                contentHash = currentHash,
                removedFile = currentFile,
                recoveryFile = recoveryFile,
            )
        }
    }

    /**
     * Restores the exact descriptor moved by [remove] without overwriting a replacement document.
     *
     * The recovery path and full content hash are both checked so a stale UI action cannot restore
     * different bytes or escape the project-local recovery directory.
     */
    fun restoreRemoved(
        projectPath: String,
        rawDocumentId: String,
        expectedContentHash: String,
        rawRecoveryPath: String,
    ): T {
        require(expectedContentHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid recovery confirmation hash" }
        val id = ProjectDocumentId(rawDocumentId)
        val paths = ProjectPathOwnership(projectPath)
        val currentFile = currentFile(paths, id)
        val recoveryRoot = recoveryDirectory(paths, id).canonicalFile
        val recoveryFile = paths.resolve(rawRecoveryPath)
        require(
            recoveryFile.parentFile == recoveryRoot &&
                recoveryFile.name.endsWith(".$extension", ignoreCase = true)
        ) { "Recovery file is outside the reviewed ${kind.displayName.lowercase()} recovery directory" }

        return ProjectDocumentWriteLocks.withLock(currentFile) {
            require(!currentFile.exists()) {
                "${kind.displayName.capitalizeForMessage()} '${id.value}' already exists. A recovery restore will never overwrite it."
            }
            require(recoveryFile.isFile) { "The reviewed recovery copy no longer exists" }
            val document = decode(recoveryFile.readText()).also { loaded ->
                require(documentId(loaded) == id.value) {
                    "Recovery file '${recoveryFile.name}' declares documentId '${documentId(loaded)}'"
                }
            }
            require(contentHash(document) == expectedContentHash) {
                "The recovery copy changed after removal. Review it before restoring."
            }
            currentFile.parentFile.mkdirs()
            moveWithoutReplacement(recoveryFile, currentFile)
            document
        }
    }

    fun listRevisions(projectPath: String, rawDocumentId: String): List<ProjectRevisionSummary> {
        val id = ProjectDocumentId(rawDocumentId)
        val revisions = mutableListOf<ProjectRevisionSummary>()
        scanHistory(projectPath, id) { document, hash, file ->
            revisions += ProjectRevisionSummary(revision(document), hash, displayName(document), file)
        }
        return revisions.distinctBy { it.revision to it.contentHash }
            .sortedWith(compareByDescending<ProjectRevisionSummary> { it.revision }.thenByDescending { it.contentHash })
    }

    /** Decode each checkpoint once; keep the selected immutable document instead of reading it again. */
    private fun scanHistory(projectPath: String, id: ProjectDocumentId, accept: (T, String, File) -> Unit) {
        val paths = ProjectPathOwnership(projectPath)
        val diagnostics = mutableListOf<ProjectDocumentDiagnostic>()
        historyDirectory(paths, id)
            .listFiles { file -> file.isFile && file.name.endsWith(".$extension", ignoreCase = true) }
            .orEmpty().forEach { file ->
                runCatching {
                    val raw = paths.check(file).readText()
                    val document = decode(raw)
                    require(documentId(document) == id.value) {
                        "History file '${file.name}' declares '${documentId(document)}', not '${id.value}'"
                    }
                    val hash = contentHash(document)
                    validateHistoryFileName(file, revision(document), hash, raw, extension)
                    accept(document, hash, file)
                }.onFailure { error ->
                    diagnostics += ProjectDocumentDiagnostic(kind, file, error.message ?: "Revision could not be decoded")
                }
            }
        require(diagnostics.isEmpty()) { diagnostics.joinToString("; ") { "${it.file.name}: ${it.message}" } }
    }

    /** Restores the selected historical content as a new revision with the current linear parent chain. */
    fun restore(projectPath: String, rawDocumentId: String, requestedHash: String): SavedProjectRevision<T> {
        val id = ProjectDocumentId(rawDocumentId)
        require(requestedHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid revision hash" }
        var selected: T? = null
        scanHistory(projectPath, id) { document, hash, _ ->
            if (hash == requestedHash && selected == null) selected = document
        }
        val historical = selected ?: error("Revision $requestedHash was not found for '${id.value}'")
        val current = load(projectPath, id.value)
        return save(projectPath, withRevision(historical, revision(current), contentHash(current)))
    }

    private fun projectDirectory(paths: ProjectPathOwnership): File = paths.resolve(".ares/$directoryName")

    private fun historyDirectory(paths: ProjectPathOwnership, id: ProjectDocumentId): File =
        paths.resolve(".ares/history/$historyName/${id.value}")

    private fun recoveryDirectory(paths: ProjectPathOwnership, id: ProjectDocumentId): File =
        paths.resolve(".ares/recovery/$historyName/${id.value}")

    private fun currentFile(paths: ProjectPathOwnership, id: ProjectDocumentId): File =
        paths.check(File(projectDirectory(paths), "${id.value}.$extension"))
}

private fun String.capitalizeForMessage(): String = replaceFirstChar { character ->
    if (character.isLowerCase()) character.titlecase() else character.toString()
}

internal fun moveWithoutReplacement(source: File, destination: File) {
    // A crash between publication and deletion retains both copies. Existing destinations are
    // never replaced, and a failed publication leaves the original source available for recovery.
    publishPreparedFileExclusively(source.toPath(), destination.toPath())
    Files.delete(source.toPath())
    forceDirectoryIfSupported(source.parentFile.toPath())
}

/** Crash-safe store for a project-wide singleton document such as a generated catalog. */
internal abstract class SingletonProjectDocumentStore<T>(
    private val kind: ProjectDocumentKind,
    private val fileName: String,
    private val historyName: String,
    private val extension: String = "json"
) {
    protected abstract fun encode(document: T): String
    protected abstract fun decode(json: String): T
    protected abstract fun contentHash(document: T): String
    protected abstract fun revision(document: T): Int
    protected abstract fun withRevision(document: T, revision: Int): T
    protected abstract fun sameContent(previous: T, draft: T): Boolean

    fun load(projectPath: String): Result<T> {
        val paths = ProjectPathOwnership(projectPath)
        val file = currentFile(paths)
        if (!file.isFile) return Result.failure(
            NoSuchElementException("${kind.displayName} does not exist at ${file.path}")
        )
        return runCatching { decode(file.readText()) }
    }

    fun save(projectPath: String, draft: T): SavedProjectRevision<T> {
        val validatedDraft = decode(encode(draft))
        val paths = ProjectPathOwnership(projectPath)
        val currentFile = currentFile(paths)
        return ProjectDocumentWriteLocks.withLock(currentFile) {
        val previous = currentFile.takeIf(File::isFile)?.let { decode(it.readText()) }
        val previousHash = previous?.let(::contentHash)
        val normalized = when {
            previous == null -> withRevision(validatedDraft, 1)
            sameContent(previous, validatedDraft) -> previous
            else -> withRevision(validatedDraft, revision(previous) + 1)
        }
        val encoded = encode(normalized)
        val hash = if (normalized === previous) requireNotNull(previousHash) else contentHash(normalized)
        val directory = historyDirectory(paths)
        if (previous != null && normalized !== previous) {
            val parentHash = requireNotNull(previousHash)
            ensureHistoryCheckpoint(
                paths.check(File(directory, historyFileName(revision(previous), parentHash, extension))),
                encode(previous), parentHash, ::decode, ::contentHash,
            )
        }
        val historyFile = paths.check(File(directory, historyFileName(revision(normalized), hash, extension)))
        val createdRevision = ensureHistoryCheckpoint(historyFile, encoded, hash, ::decode, ::contentHash)
        if (previous != normalized || !currentFile.exists()) {
            AtomicProjectFileWriter.write(currentFile, encoded, replaceExisting = true)
        }
        SavedProjectRevision(normalized, hash, currentFile, historyFile, createdRevision)
        }
    }

    fun listRevisions(projectPath: String): List<ProjectRevisionSummary> {
        val revisions = mutableListOf<ProjectRevisionSummary>()
        scanHistory(projectPath) { document, hash, file ->
            revisions += ProjectRevisionSummary(revision(document), hash, kind.displayName, file)
        }
        return revisions.distinctBy { it.revision to it.contentHash }
            .sortedWith(compareByDescending<ProjectRevisionSummary> { it.revision }.thenByDescending { it.contentHash })
    }

    private fun scanHistory(projectPath: String, accept: (T, String, File) -> Unit) {
        val paths = ProjectPathOwnership(projectPath)
        historyDirectory(paths)
            .listFiles { file -> file.isFile && file.name.endsWith(".$extension", ignoreCase = true) }
            .orEmpty().forEach { file ->
                val raw = paths.check(file).readText()
                val document = decode(raw)
                val hash = contentHash(document)
                validateHistoryFileName(file, revision(document), hash, raw, extension)
                accept(document, hash, file)
            }
    }

    fun restore(
        projectPath: String,
        requestedHash: String,
        validateSelected: (T) -> Unit = {},
    ): SavedProjectRevision<T> {
        require(requestedHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid revision hash" }
        var selected: T? = null
        scanHistory(projectPath) { document, hash, _ ->
            if (hash == requestedHash && selected == null) selected = document
        }
        val historical = selected ?: error("Revision $requestedHash was not found for ${kind.displayName}")
        validateSelected(historical)
        val current = load(projectPath).getOrThrow()
        return save(projectPath, withRevision(historical, revision(current)))
    }

    fun diagnostic(projectPath: String): ProjectDocumentDiagnostic? {
        val paths = ProjectPathOwnership(projectPath)
        val file = currentFile(paths)
        if (!file.isFile) return null
        return runCatching { decode(file.readText()) }.exceptionOrNull()?.let { error ->
            ProjectDocumentDiagnostic(kind, file, error.message ?: "Document could not be decoded")
        }
    }

    private fun currentFile(paths: ProjectPathOwnership): File = paths.resolve(".ares/$fileName")
    private fun historyDirectory(paths: ProjectPathOwnership): File = paths.resolve(".ares/history/$historyName")
}

private fun historyFileName(revision: Int, hash: String, extension: String): String =
    "${revision.toString().padStart(4, '0')}-${hash.take(12)}.$extension"

private fun validateHistoryFileName(file: File, revision: Int, hash: String, raw: String, extension: String) {
    val canonical = historyFileName(revision, hash, extension)
    // A codec can normalize older/defaulted fields while decoding. Preserve checkpoints whose
    // original serialized bytes match the name, including text line-ending normalization.
    require(file.name.equals(canonical, ignoreCase = true) ||
        file.name.equals(historyFileName(revision, Sha256.hex(raw), extension), ignoreCase = true) ||
        file.name.equals(historyFileName(revision, Sha256.hex(raw.replace("\r\n", "\n")), extension), ignoreCase = true)) {
        "History file '${file.name}' does not match its revision and content hash"
    }
}

internal fun <T> ensureHistoryCheckpoint(
    file: File,
    encoded: String,
    expectedHash: String,
    decode: (String) -> T,
    hashOf: (T) -> String,
): Boolean {
    if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        require(file.isFile && hashOf(decode(file.readText())) == expectedHash) {
            "History checkpoint '${file.name}' already exists with invalid or different content"
        }
        return false
    }
    AtomicProjectFileWriter.write(file, encoded, replaceExisting = false)
    return true
}

private val ProjectDocumentKind.displayName: String
    get() = name.lowercase().replace('_', ' ')
