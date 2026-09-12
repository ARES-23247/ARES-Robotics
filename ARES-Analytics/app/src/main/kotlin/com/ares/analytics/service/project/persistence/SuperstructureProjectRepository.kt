package com.ares.analytics.service.project.persistence

import com.areslib.project.schema.ProjectDocumentKind

import com.areslib.project.schema.ProjectDocumentId
import com.areslib.subsystem.SubsystemDocument
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.SuperstructureDocumentCodec
import com.areslib.superstructure.SuperstructureIssueSeverity
import com.areslib.superstructure.validateSuperstructureProject
import java.io.File

data class SavedSuperstructureDocument(
    val document: SuperstructureDocument,
    val contentHash: String,
    val currentFile: File,
    val historyFile: File,
    val createdHistory: Boolean,
)

/**
 * Crash-safe store for generated superstructure coordinators.
 *
 * Superstructure schema v3 intentionally has no mutable revision counter. Concurrency is therefore
 * bound to the exact canonical content hash. The editor must reload after another writer changes a
 * document; it never silently replaces newer student work.
 */
class SuperstructureProjectRepository {
    fun list(projectPath: String): ProjectDocumentListing<SuperstructureDocument> {
        val paths = ProjectPathOwnership(projectPath)
        val directory = directory(paths)
        if (!directory.isDirectory) return ProjectDocumentListing(emptyList(), emptyList())
        val decoded = mutableListOf<Pair<File, SuperstructureDocument>>()
        val diagnostics = mutableListOf<ProjectDocumentDiagnostic>()
        directory.listFiles { file -> file.isFile && file.name.endsWith(".aressuperstructure", true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .forEach { file ->
                runCatching { SuperstructureDocumentCodec.decode(paths.check(file).readText()) }
                    .onSuccess { decoded += file to it }
                    .onFailure { error ->
                        diagnostics += ProjectDocumentDiagnostic(
                            ProjectDocumentKind.SUPERSTRUCTURE,
                            file,
                            error.message ?: "Superstructure document could not be decoded",
                        )
                    }
            }

        val byId = decoded.groupBy { it.second.superstructureId }
        val documents = mutableListOf<SuperstructureDocument>()
        decoded.forEach { (file, document) ->
            val fileId = file.name.removeSuffix(".aressuperstructure")
            val duplicateFiles = byId.getValue(document.superstructureId)
            var validIdentity = true
            if (fileId != document.superstructureId) {
                diagnostics += ProjectDocumentDiagnostic(
                    ProjectDocumentKind.SUPERSTRUCTURE,
                    file,
                    "File name '$fileId' does not match superstructureId '${document.superstructureId}'",
                )
                validIdentity = false
            }
            if (duplicateFiles.size > 1) {
                diagnostics += ProjectDocumentDiagnostic(
                    ProjectDocumentKind.SUPERSTRUCTURE,
                    file,
                    "Duplicate superstructureId '${document.superstructureId}' appears in ${duplicateFiles.joinToString { it.first.name }}",
                )
                validIdentity = false
            }
            if (validIdentity) documents += document
        }
        return ProjectDocumentListing(
            documents.sortedWith(compareBy<SuperstructureDocument> { it.displayName.lowercase() }.thenBy { it.superstructureId }),
            diagnostics.sortedBy { it.file.name.lowercase() },
        )
    }

    fun load(projectPath: String, rawId: String): SuperstructureDocument {
        val id = ProjectDocumentId(rawId)
        val paths = ProjectPathOwnership(projectPath)
        val file = currentFile(paths, id)
        require(file.isFile) { "Superstructure '${id.value}' does not exist" }
        return SuperstructureDocumentCodec.decode(file.readText()).also { document ->
            require(document.superstructureId == id.value) {
                "Superstructure file '${file.name}' declares '${document.superstructureId}'"
            }
        }
    }

    fun contentHash(document: SuperstructureDocument): String = SuperstructureDocumentCodec.contentHash(document)

    fun file(projectPath: String, rawId: String): File = currentFile(ProjectPathOwnership(projectPath), ProjectDocumentId(rawId))

    fun save(
        projectPath: String,
        draft: SuperstructureDocument,
        expectedContentHash: String?,
        subsystems: List<SubsystemDocument>,
        actionKeys: Set<String>,
        parameterlessActionKeys: Set<String> = actionKeys,
    ): SavedSuperstructureDocument {
        val validated = SuperstructureDocumentCodec.decode(SuperstructureDocumentCodec.encode(draft))
        val projectErrors = validateSuperstructureProject(validated, subsystems, actionKeys, parameterlessActionKeys)
            .filter { it.severity == SuperstructureIssueSeverity.ERROR }
        require(projectErrors.isEmpty()) {
            projectErrors.joinToString("; ") { "${it.path}: ${it.message}" }
        }
        val id = ProjectDocumentId(validated.superstructureId)
        val paths = ProjectPathOwnership(projectPath)
        val current = currentFile(paths, id)
        return ProjectDocumentWriteLocks.withLock(current) {
            val previous = current.takeIf(File::isFile)?.let { file ->
                SuperstructureDocumentCodec.decode(file.readText()).also {
                    require(it.superstructureId == id.value) {
                        "Superstructure file '${file.name}' declares '${it.superstructureId}'"
                    }
                }
            }
            val actualHash = previous?.let(SuperstructureDocumentCodec::contentHash)
            require(actualHash == expectedContentHash) {
                if (actualHash == null) {
                    "Superstructure '${id.value}' was removed. Reload before saving."
                } else {
                    "Superstructure '${id.value}' changed on disk. Reload before replacing newer work."
                }
            }

            val encoded = SuperstructureDocumentCodec.encode(validated)
            val hash = SuperstructureDocumentCodec.contentHash(validated)
            if (previous != null && actualHash != hash) {
                ensureHistoryCheckpoint(
                    historyFile(paths, id, requireNotNull(actualHash)),
                    SuperstructureDocumentCodec.encode(previous), actualHash,
                    SuperstructureDocumentCodec::decode, SuperstructureDocumentCodec::contentHash,
                )
            }
            val history = historyFile(paths, id, hash)
            val createdHistory = ensureHistoryCheckpoint(
                history, encoded, hash, SuperstructureDocumentCodec::decode, SuperstructureDocumentCodec::contentHash,
            )
            if (actualHash != hash || !current.exists()) {
                AtomicProjectFileWriter.write(current, encoded, replaceExisting = true)
            }
            SavedSuperstructureDocument(validated, hash, current, history, createdHistory)
        }
    }

    private fun directory(paths: ProjectPathOwnership): File = paths.resolve(".ares/superstructures")

    private fun currentFile(paths: ProjectPathOwnership, id: ProjectDocumentId): File =
        paths.check(File(directory(paths), "${id.value}.aressuperstructure"))

    private fun historyFile(paths: ProjectPathOwnership, id: ProjectDocumentId, hash: String): File =
        paths.check(File(paths.resolve(".ares/history/superstructures/${id.value}"), "$hash.aressuperstructure"))
}
