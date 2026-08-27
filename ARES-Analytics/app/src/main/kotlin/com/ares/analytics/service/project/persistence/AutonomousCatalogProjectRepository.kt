package com.ares.analytics.service.project.persistence

import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.RoutineValidationSeverity
import com.areslib.routine.validateAutonomousCatalog

/** Offline autonomous choices, separate from reusable routine bodies. */
class AutonomousCatalogProjectRepository(
    private val routines: RoutineProjectRepository = RoutineProjectRepository()
) {
    fun load(projectPath: String): Result<AutonomousCatalogDocument> = store.load(projectPath).mapCatching { document ->
        requireValidReferences(projectPath, document)
        document
    }

    fun save(
        projectPath: String,
        document: AutonomousCatalogDocument
    ): SavedProjectRevision<AutonomousCatalogDocument> {
        requireValidReferences(projectPath, document)
        return store.save(projectPath, document)
    }

    fun listRevisions(projectPath: String): List<ProjectRevisionSummary> = store.listRevisions(projectPath)

    fun restore(
        projectPath: String,
        contentHash: String
    ): SavedProjectRevision<AutonomousCatalogDocument> {
        val historical = store.listRevisions(projectPath).firstOrNull { it.contentHash == contentHash }
            ?: error("Revision $contentHash was not found for autonomous catalog")
        val candidate = AutonomousCatalogCodec.decode(historical.file.readText())
        requireValidReferences(projectPath, candidate)
        return store.restore(projectPath, contentHash)
    }

    fun diagnostic(projectPath: String): ProjectDocumentDiagnostic? = load(projectPath).exceptionOrNull()?.let { error ->
        val file = resolveProjectPath(projectPath, ".ares/autonomous-catalog.json")
        if (file.isFile) {
            ProjectDocumentDiagnostic(
                ProjectDocumentKind.AUTONOMOUS_CATALOG,
                file,
                error.message ?: "Autonomous catalog could not be decoded"
            )
        } else {
            null
        }
    }

    private fun requireValidReferences(projectPath: String, document: AutonomousCatalogDocument) {
        val routineListing = routines.list(projectPath)
        require(routineListing.diagnostics.isEmpty()) {
            "Cannot validate autonomous choices while routine files are corrupt: " +
                routineListing.diagnostics.joinToString { it.file.name }
        }
        val routineIds = routineListing.documents.mapTo(linkedSetOf()) { it.documentId }
        val unknownRoutineErrors = document.entries
            .filter { it.routineId !in routineIds }
            .map { "Unknown routine '${it.routineId}'" }
        require(unknownRoutineErrors.isEmpty()) { unknownRoutineErrors.joinToString("; ") }
        val errors = validateAutonomousCatalog(document, routineIds)
            .filter { it.severity == RoutineValidationSeverity.ERROR }
        require(errors.isEmpty()) { errors.joinToString("; ") { it.message } }
    }

    private object store : SingletonProjectDocumentStore<AutonomousCatalogDocument>(
        kind = ProjectDocumentKind.AUTONOMOUS_CATALOG,
        fileName = "autonomous-catalog.json",
        historyName = "autonomous-catalog"
    ) {
        override fun encode(document: AutonomousCatalogDocument): String = AutonomousCatalogCodec.encode(document)
        override fun decode(json: String): AutonomousCatalogDocument = AutonomousCatalogCodec.decode(json)
        override fun contentHash(document: AutonomousCatalogDocument): String = AutonomousCatalogCodec.contentHash(document)
        override fun revision(document: AutonomousCatalogDocument): Int = document.revision
        override fun withRevision(document: AutonomousCatalogDocument, revision: Int): AutonomousCatalogDocument =
            document.copy(revision = revision)

        override fun sameContent(
            previous: AutonomousCatalogDocument,
            draft: AutonomousCatalogDocument
        ): Boolean = previous == draft.copy(
            schemaVersion = previous.schemaVersion,
            revision = previous.revision
        )
    }
}
