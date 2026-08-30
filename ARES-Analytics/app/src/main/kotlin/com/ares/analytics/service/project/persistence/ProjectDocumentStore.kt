package com.ares.analytics.service.project.persistence

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
        val directory = projectDirectory(projectPath)
        if (!directory.isDirectory) return ProjectDocumentListing(emptyList(), emptyList())

        val decodedDocuments = mutableListOf<Pair<File, T>>()
        val diagnostics = mutableListOf<ProjectDocumentDiagnostic>()
        directory.listFiles { file ->
            file.isFile && file.name.endsWith(".$extension", ignoreCase = true)
        }.orEmpty().sortedBy { it.name.lowercase() }.forEach { file ->
            runCatching {
                decode(file.readText()).also { document -> ProjectDocumentId(documentId(document)) }
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
        val file = currentFile(projectPath, id)
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
        val currentFile = currentFile(projectPath, id)
        return ProjectDocumentWriteLocks.withLock(currentFile) {
        val previous = currentFile.takeIf(File::isFile)?.let { file ->
            decode(file.readText()).also { document ->
                require(documentId(document) == id.value) {
                    "${kind.displayName} file '${file.name}' declares documentId '${documentId(document)}'"
                }
            }
        }
        val normalized = when {
            previous == null -> withRevision(validatedDraft, revision = 1, parentHash = null)
            sameContent(previous, validatedDraft) -> previous
            else -> withRevision(validatedDraft, revision(previous) + 1, contentHash(previous))
        }
        val encoded = encode(normalized)
        val hash = contentHash(normalized)
        val historyFile = File(
            historyDirectory(projectPath, id),
            "${revision(normalized).toString().padStart(4, '0')}-${hash.take(12)}.$extension"
        )
        val createdRevision = !historyFile.exists()

        if (createdRevision) AtomicProjectFileWriter.write(historyFile, encoded, replaceExisting = false)
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
        val currentFile = currentFile(projectPath, id)
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
                recoveryFile = File(
                    recoveryDirectory(projectPath, id),
                    "${revision(document).toString().padStart(4, '0')}-${hash.take(12)}.$extension",
                ),
            )
        }
    }

    /**
     * Atomically moves the reviewed canonical file into `.ares/recovery`. History is retained and
     * no source file is touched. A stale hash fails before any filesystem mutation.
     */
    fun remove(
        projectPath: String,
        rawDocumentId: String,
        expectedContentHash: String,
    ): RemovedProjectDocument {
        require(expectedContentHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid removal confirmation hash" }
        val id = ProjectDocumentId(rawDocumentId)
        val currentFile = currentFile(projectPath, id)
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
            val recoveryFile = File(
                recoveryDirectory(projectPath, id),
                "${revision(document).toString().padStart(4, '0')}-${currentHash.take(12)}.$extension",
            )
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
        val currentFile = currentFile(projectPath, id)
        val recoveryRoot = recoveryDirectory(projectPath, id).canonicalFile
        val recoveryFile = resolveProjectPath(projectPath, rawRecoveryPath).canonicalFile
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
        val diagnostics = mutableListOf<ProjectDocumentDiagnostic>()
        val revisions = historyDirectory(projectPath, id)
            .listFiles { file -> file.isFile && file.name.endsWith(".$extension", ignoreCase = true) }
            .orEmpty()
            .mapNotNull { file ->
                runCatching { decode(file.readText()) }
                    .onFailure { error ->
                        diagnostics += ProjectDocumentDiagnostic(
                            kind,
                            file,
                            error.message ?: "Revision could not be decoded"
                        )
                    }
                    .getOrNull()
                    ?.let { document ->
                        ProjectRevisionSummary(
                            revision(document),
                            contentHash(document),
                            displayName(document),
                            file
                        )
                    }
            }
        // History corruption is a recovery problem rather than an ignorable list entry.
        require(diagnostics.isEmpty()) {
            diagnostics.joinToString("; ") { "${it.file.name}: ${it.message}" }
        }
        return revisions.sortedWith(
            compareByDescending<ProjectRevisionSummary> { it.revision }
                .thenByDescending { it.contentHash }
        )
    }

    /** Restores historical content as a new revision while retaining a linear parent chain. */
    fun restore(
        projectPath: String,
        rawDocumentId: String,
        requestedHash: String
    ): SavedProjectRevision<T> {
        val id = ProjectDocumentId(rawDocumentId)
        require(requestedHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid revision hash" }
        val historicalFile = listRevisions(projectPath, id.value)
            .firstOrNull { it.contentHash == requestedHash }
            ?.file
            ?: error("Revision $requestedHash was not found for '${id.value}'")
        val historical = decode(historicalFile.readText())
        val current = load(projectPath, id.value)
        return save(
            projectPath,
            withRevision(historical, revision(current), contentHash(current))
        )
    }

    private fun projectDirectory(projectPath: String): File =
        resolveProjectPath(projectPath, ".ares/$directoryName")

    private fun historyDirectory(projectPath: String, id: ProjectDocumentId): File =
        resolveProjectPath(projectPath, ".ares/history/$historyName/${id.value}")

    private fun recoveryDirectory(projectPath: String, id: ProjectDocumentId): File =
        resolveProjectPath(projectPath, ".ares/recovery/$historyName/${id.value}")

    private fun currentFile(projectPath: String, id: ProjectDocumentId): File =
        File(projectDirectory(projectPath), "${id.value}.$extension")
}

private fun String.capitalizeForMessage(): String = replaceFirstChar { character ->
    if (character.isLowerCase()) character.titlecase() else character.toString()
}

private fun moveWithoutReplacement(source: File, destination: File) {
    try {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath())
    }
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
        val file = currentFile(projectPath)
        if (!file.isFile) return Result.failure(
            NoSuchElementException("${kind.displayName} does not exist at ${file.path}")
        )
        return runCatching { decode(file.readText()) }
    }

    fun save(projectPath: String, draft: T): SavedProjectRevision<T> {
        val validatedDraft = decode(encode(draft))
        val currentFile = currentFile(projectPath)
        return ProjectDocumentWriteLocks.withLock(currentFile) {
        val previous = currentFile.takeIf(File::isFile)?.let { decode(it.readText()) }
        val normalized = when {
            previous == null -> withRevision(validatedDraft, 1)
            sameContent(previous, validatedDraft) -> previous
            else -> withRevision(validatedDraft, revision(previous) + 1)
        }
        val encoded = encode(normalized)
        val hash = contentHash(normalized)
        val historyFile = File(
            historyDirectory(projectPath),
            "${revision(normalized).toString().padStart(4, '0')}-${hash.take(12)}.$extension"
        )
        val createdRevision = !historyFile.exists()
        if (createdRevision) AtomicProjectFileWriter.write(historyFile, encoded, replaceExisting = false)
        if (previous != normalized || !currentFile.exists()) {
            AtomicProjectFileWriter.write(currentFile, encoded, replaceExisting = true)
        }
        SavedProjectRevision(normalized, hash, currentFile, historyFile, createdRevision)
        }
    }

    fun listRevisions(projectPath: String): List<ProjectRevisionSummary> = historyDirectory(projectPath)
        .listFiles { file -> file.isFile && file.name.endsWith(".$extension", ignoreCase = true) }
        .orEmpty()
        .map { file ->
            val document = decode(file.readText())
            ProjectRevisionSummary(
                revision(document),
                contentHash(document),
                kind.displayName,
                file
            )
        }
        .sortedWith(
            compareByDescending<ProjectRevisionSummary> { it.revision }
                .thenByDescending { it.contentHash }
        )

    fun restore(projectPath: String, requestedHash: String): SavedProjectRevision<T> {
        require(requestedHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid revision hash" }
        val historicalFile = listRevisions(projectPath)
            .firstOrNull { it.contentHash == requestedHash }
            ?.file
            ?: error("Revision $requestedHash was not found for ${kind.displayName}")
        val historical = decode(historicalFile.readText())
        val current = load(projectPath).getOrThrow()
        return save(projectPath, withRevision(historical, revision(current)))
    }

    fun diagnostic(projectPath: String): ProjectDocumentDiagnostic? {
        val file = currentFile(projectPath)
        if (!file.isFile) return null
        return load(projectPath).exceptionOrNull()?.let { error ->
            ProjectDocumentDiagnostic(kind, file, error.message ?: "Document could not be decoded")
        }
    }

    private fun currentFile(projectPath: String): File = resolveProjectPath(projectPath, ".ares/$fileName")
    private fun historyDirectory(projectPath: String): File =
        resolveProjectPath(projectPath, ".ares/history/$historyName")
}

internal fun requireProjectRoot(projectPath: String): File {
    require(projectPath.isNotBlank()) { "Choose a project directory" }
    val root = File(projectPath).canonicalFile
    require(root.isDirectory) { "Project directory does not exist: ${root.path}" }
    return root
}

/** Resolves through existing symlinks and rejects any target that escapes the chosen repository. */
internal fun resolveProjectPath(projectPath: String, relativePath: String): File {
    val root = requireProjectRoot(projectPath)
    val target = File(root, relativePath).canonicalFile
    require(target.toPath().startsWith(root.toPath())) {
        "Project document path escapes the selected repository"
    }
    return target
}

/** Serializes revision allocation and current-file replacement for each canonical document. */
internal object ProjectDocumentWriteLocks {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun <T> withLock(file: File, block: () -> T): T {
        val lock = locks.computeIfAbsent(file.canonicalPath) { Any() }
        return synchronized(lock) { block() }
    }
}

internal object AtomicProjectFileWriter {
    fun write(file: File, content: String, replaceExisting: Boolean) {
        write(file, content.toByteArray(Charsets.UTF_8), replaceExisting)
    }

    /** Writes recovery evidence byte-for-byte so even malformed text remains recoverable. */
    fun write(file: File, content: ByteArray, replaceExisting: Boolean) {
        file.parentFile.mkdirs()
        val temporary = Files.createTempFile(file.parentFile.toPath(), ".${file.name}.", ".tmp")
        try {
            Files.write(temporary, content)
            val atomicOptions = if (replaceExisting) {
                arrayOf<java.nio.file.CopyOption>(
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } else {
                arrayOf<java.nio.file.CopyOption>(StandardCopyOption.ATOMIC_MOVE)
            }
            try {
                Files.move(temporary, file.toPath(), *atomicOptions)
            } catch (_: AtomicMoveNotSupportedException) {
                val fallback = if (replaceExisting) {
                    arrayOf<java.nio.file.CopyOption>(StandardCopyOption.REPLACE_EXISTING)
                } else {
                    emptyArray<java.nio.file.CopyOption>()
                }
                Files.move(temporary, file.toPath(), *fallback)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private val ProjectDocumentKind.displayName: String
    get() = name.lowercase().replace('_', ' ')
